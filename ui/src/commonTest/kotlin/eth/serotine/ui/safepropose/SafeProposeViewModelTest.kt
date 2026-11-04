package eth.serotine.ui.safepropose

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.BiometricUnlock
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.EncodeSigningRequest
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.ObserveUsdPrice
import eth.serotine.domain.usecase.ProposeSafeTx
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.SignSafeTx
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.FakeNames
import eth.serotine.ui.MemoryCurrencySetting
import eth.serotine.ui.SafeWorld
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.SafeBalanceChange
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.ShareItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** [SafeProposeViewModel] over fake ports, through the real use cases. */
@OptIn(ExperimentalCoroutinesApi::class)
class SafeProposeViewModelTest {

    private val world = SafeWorld()
    private val clock = TestTimeSource()
    private var wallClock = 1_800_000_000L
    private val feeds = FakeFeeds()
    private var biometrics: BiometricUnlock = BiometricUnlock.NotOnThisDevice
    private val keys = object : KeyStore {
        override suspend fun describe() = KeyStoreDescription.Ready(true, KeyProtection.HARDWARE, biometrics)

        override suspend fun newMnemonic(): NewMnemonic = error("not used by these tests")

        override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport =
            error("not used by these tests")

        override suspend fun enableBiometrics(password: WalletPassword, confirmation: String): BiometricChange =
            error("not used by these tests")

        override suspend fun deriveAccount(from: Address, password: WalletPassword, held: Set<Address>) =
            KeyDerivation.Unavailable

        override suspend fun disableBiometrics(): BiometricChange = error("not used by these tests")

        override suspend fun delete(address: Address): KeyDeletion = error("not used by these tests")
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(from: Address = world.safe, asset: Address? = null): SafeProposeViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = SafeProposeViewModel(
            AccountId(Network.SEPOLIA, from),
            asset,
            ObserveAccounts(world.accounts),
            ObserveAccountValue(
                ObserveNativeBalance(world.balances),
                ObserveTokenHoldings(world.knownTokens, world.tokenBalances),
                ObserveUsdPrice(feeds),
                ObserveDisplayCurrencies(feeds, MemoryCurrencySetting(), world.accounts),
            ),
            world.observeSafe,
            DescribeKeyStore(keys),
            ResolveRecipient(FakeNames()),
            ProposeSafeTx(
                world.observeSafe,
                world.balances,
                world.tokenBalances,
                world.inputs,
                world.queue,
                Network.SEPOLIA,
            ),
            SignSafeTx(world.signer, world.observeSafe, world.recovery, world.queue, Network.SEPOLIA),
            EncodeSigningRequest(world.codec),
            clock,
            now = { wallClock },
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private fun holding() {
        world.balances.of(world.safe).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        world.tokenBalances.of(world.safe, world.usdc.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("5000000"), 99)
    }

    private val SafeProposeViewModel.form get() = assertIs<SafeProposeUiState.Compose>(uiState.value).form
    private val SafeProposeViewModel.review get() = assertIs<SafeProposeUiState.Review>(uiState.value)

    /** Composes 0.1 ETH to the recipient and asks for the review. */
    private fun TestScope.reviewed(): SafeProposeViewModel {
        holding()
        val viewModel = viewModel()
        viewModel.onToChange(Eip55.format(world.recipient))
        viewModel.onAmountChange("0.1")
        runCurrent()
        viewModel.review()
        runCurrent()
        return viewModel
    }

    @Test
    fun theFormIsSendsFromTheSafeWithItsAssetsAndNoFee() = runTest {
        holding()
        val viewModel = viewModel()

        val form = viewModel.form
        assertEquals(SendFrom("Treasury", Eip55.format(world.safe)), form.from)
        assertEquals(listOf("ETH", "USDC"), form.assets.map { it.symbol })
        assertEquals(
            listOf("Alice", "Bob", "Dave"),
            form.accounts.map { it.label },
            "the wallet's other accounts, the Safe not among them",
        )
    }

    @Test
    fun aTransferIsProposedAtTheSafesNonceAndSignedByAnOwnerHeldHereWhichQueuesIt() = runTest {
        val viewModel = reviewed()

        val review = viewModel.review
        assertEquals("Send 0.1 ETH", review.action.title)
        assertEquals("0.1 ETH", review.amount)
        assertEquals(Eip55.format(world.recipient), review.recipient.address)
        assertEquals("Safe nonce 4", review.nonce)
        assertEquals(null, review.queued)
        assertEquals(
            SafeBalanceChange("ETH balance of the Safe", "1 ETH", "0.9 ETH", "verified at block 100"),
            review.balance,
        )
        assertTrue(review.simulation.startsWith("The Safe's call, run as a verified call from the Safe at block 100"))
        assertTrue(review.simpleSimulation.orEmpty().startsWith("Checked on chain: the Safe's call should go through"))
        assertEquals(listOf(AccountChoice("Alice", Eip55.format(world.alice.address))), review.owners)
        assertEquals(emptyList(), world.queue.requests.value, "nothing is queued before it is signed")

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        val queued = world.queue.requests.value.single()
        assertEquals(listOf(world.alice.address), queued.signers)
        assertEquals(SafeProposeUiState.Proposed("Sepolia", "0x" + queued.hash.hex), viewModel.uiState.value)
        assertEquals(listOf("made-up test password"), world.signer.passwords)
    }

    @Test
    fun aProposalAfterTheQueueSaysWhatRunsBeforeItAndWhatThoseMove() = runTest {
        world.queue.requests.value = listOf(world.request(world.ether(4), world.carol))
        val viewModel = reviewed()

        val review = viewModel.review
        assertEquals("Safe nonce 5", review.nonce)
        assertEquals("1 transaction queued on this device runs before it.", review.queued)
        assertEquals("0.1 ETH moved by those queued before it", review.balance.queued)
        assertEquals("0.8 ETH", review.balance.after)
    }

    @Test
    fun anEngineThatWillNotRunACallFromTheSafeLeavesItNotSimulatedAndSaysSo() = runTest {
        world.inputs.simulation = Simulation.Refused
        val viewModel = reviewed()

        assertTrue(viewModel.review.simulation.startsWith("Not simulated:"), viewModel.review.simulation)
        assertEquals(null, viewModel.review.simpleSimulation, "it names no block: the same in both views")
    }

    @Test
    fun aProposalThatCannotWorkIsRefusedOnTheForm() = runTest {
        holding()
        val viewModel = viewModel()
        viewModel.onToChange(Eip55.format(world.recipient))
        viewModel.onAmountChange("2")
        runCurrent()

        viewModel.review()
        runCurrent()

        assertEquals(
            "The Safe holds less ETH than that, once the transactions queued on this device before it have moved " +
                "what they move.",
            viewModel.form.error,
        )
    }

    @Test
    fun aCallThatRevertsIsRefusedOnTheFormAndSimpleModeSaysSoWithNoBlock() = runTest {
        world.inputs.simulation = Simulation.Reverted(100)
        holding()
        val viewModel = viewModel()
        viewModel.onToChange(Eip55.format(world.recipient))
        viewModel.onAmountChange("0.1")
        runCurrent()

        viewModel.review()
        runCurrent()

        val form = viewModel.form
        assertTrue("at block 100" in form.error.orEmpty(), form.error)
        assertEquals(
            "The Safe's call reverts, checked on chain: the recipient or the token refuses it. Nothing was proposed.",
            form.simpleError,
        )
    }

    @Test
    fun eachBalanceSaysHowLongAgoItWasProvedAndIsDatedAnew() = runTest {
        holding()
        world.balances.of(world.safe).value =
            BalanceReading.Verified(Wei.of("1000000000000000000"), 100, provenAt = wallClock - 120)
        val viewModel = viewModel()
        runCurrent()
        assertEquals("updated 2 min ago", viewModel.form.asset?.age)

        wallClock += 60
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals("updated 3 min ago", viewModel.form.asset?.age, "the age is dated anew every second")
    }

    @Test
    fun aTransferToTheSafeItselfIsRefusedOnTheFormAsMovingNothing() = runTest {
        holding()
        val viewModel = viewModel()
        viewModel.onToChange(Eip55.format(world.safe))
        viewModel.onAmountChange("0.1")
        runCurrent()

        viewModel.review()
        runCurrent()

        assertEquals("The Safe itself: nothing would leave it, so it is refused.", viewModel.form.error)
        assertEquals(emptyList(), world.queue.requests.value, "nothing proposed")
    }

    @Test
    fun whileTheSafeIsCheckedBeforeSigningTheReviewSaysSoAndLeavingStopsIt() = runTest {
        val viewModel = reviewed()
        viewModel.onShown()
        viewModel.onPasswordChange("made-up test password")
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        viewModel.sign()
        runCurrent()
        advanceTimeBy(10.seconds)

        assertTrue(viewModel.review.signing)
        assertTrue(viewModel.review.checking, "Checking the Safe on chain…")
        assertEquals(emptyList(), world.signer.asked, "no key asked for while the Safe is checked")

        viewModel.onHidden()
        world.state = world.state // the Safe proven after all, once left
        advanceTimeBy(60.seconds)
        runCurrent()

        assertEquals(emptyList(), world.signer.asked, "no signer call, so no prompt over another screen")
        assertFalse(viewModel.review.signing)
        assertFalse(viewModel.review.checking)
        assertEquals(emptyList(), world.queue.requests.value)
    }

    @Test
    fun aProposalOverAMinuteOldIsMadeAgainAtTheTapThatWouldSignIt() = runTest {
        val viewModel = reviewed()
        viewModel.onPasswordChange("made-up test password")
        clock += 61.seconds

        viewModel.sign()
        runCurrent()

        assertEquals(
            "The quote was over a minute old, so it was made again: check it, then sign.",
            viewModel.review.unlock.error,
        )
        assertEquals(emptyList(), world.signer.asked)
    }

    @Test
    fun thePasswordGoesWhenTheScreenIsHidden() = runTest {
        val viewModel = reviewed()
        viewModel.onShown()
        viewModel.onPasswordChange("made-up test password")

        viewModel.onHidden()

        assertEquals("", viewModel.review.unlock.password.text)
    }

    @Test
    fun aSignatureTheQueueCannotSaveIsSaidAndNothingIsKept() = runTest {
        val viewModel = reviewed()
        world.queue.failSaves = true

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        assertTrue(viewModel.review.error!!.startsWith("Signed, but this device's queue cannot be saved"))
        assertEquals(emptyList(), world.queue.requests.value)
        // Not kept here, but shared from the review, the signature is not lost.
        val share = assertIs<ShareItem>(viewModel.review.share)
        assertTrue(share.text.endsWith(":1"), "the request carries the signature made here")
    }

    @Test
    fun backWithASignatureTheQueueCouldNotSaveAsksBeforeItIsLeftBehind() = runTest {
        val viewModel = reviewed()
        world.queue.failSaves = true
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        val shared = assertIs<ShareItem>(viewModel.review.share)

        viewModel.edit()
        assertTrue(viewModel.review.askingToDrop, "asked first: it is kept nowhere else")
        viewModel.keepUnsaved()
        assertFalse(viewModel.review.askingToDrop)
        assertEquals(shared, viewModel.review.share, "still there to be shared")

        viewModel.edit()
        viewModel.dropUnsaved()
        assertEquals("0.1", viewModel.form.amount)
    }

    @Test
    fun aSignatureNotSavedGoesWhenTheProposalIsMadeAgainAsAnotherTransaction() = runTest {
        val viewModel = reviewed()
        world.queue.failSaves = true
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        assertIs<ShareItem>(viewModel.review.share)

        // Proposed again with the same terms, it is the same transaction: the signature stays.
        viewModel.refresh()
        runCurrent()
        assertIs<ShareItem>(viewModel.review.share)

        // Another transaction took its nonce meanwhile: proposed again, it is another one.
        world.queue.requests.value = listOf(world.request(world.ether(4, wei = 1)))
        viewModel.refresh()
        runCurrent()

        val review = viewModel.review
        assertEquals("Safe nonce 5", review.nonce)
        assertEquals(null, review.share, "a signature of another transaction is not shared with this one")
        assertEquals(
            "Proposed again as another transaction, so the signature made for the earlier one, which this " +
                "device's queue could not save, was dropped: sign this one.",
            review.error,
        )
    }

    @Test
    fun theBiometricPromptGetsWhatIsLeftOfTheProposalsAgeAndOneAnsweredLateIsProposedAgain() = runTest {
        biometrics = BiometricUnlock.On
        val viewModel = reviewed()
        clock += 30.seconds
        world.signer.answer = Signing.TimedOut

        viewModel.signWithBiometrics()
        runCurrent()

        assertEquals(30.seconds, -(world.signer.asked.single().second as Unlock.WithBiometrics).notAfter!!.elapsedNow())
        assertEquals(emptyList(), world.queue.requests.value, "nothing signed, nothing queued")
        assertEquals(
            "The fingerprint or face check took longer than the quote could wait, so nothing was signed and the " +
                "quote was made again: check it, then sign.",
            viewModel.review.unlock.error,
        )
    }

    @Test
    fun aSlowCheckOfTheSafeBeforeTheSignerDoesNotStretchThePromptsDeadline() = runTest {
        biometrics = BiometricUnlock.On
        val viewModel = reviewed()
        clock += 30.seconds
        // The Safe's reading SignSafeTx makes before it asks the signer takes 20 s.
        val gate = CompletableDeferred<Unit>()
        world.kindGate = gate

        viewModel.signWithBiometrics()
        runCurrent()
        clock += 10.seconds
        gate.complete(Unit)
        runCurrent()

        val unlock = assertIs<Unlock.WithBiometrics>(world.signer.asked.single().second)
        assertEquals(20.seconds, -unlock.notAfter!!.elapsedNow(), "what is left of the proposal's minute, not 30 s")
    }

    @Test
    fun aCheckOfTheSafeThatEatsThePromptsTimeProposesAgainAndAsksForNoKey() = runTest {
        biometrics = BiometricUnlock.On
        val viewModel = reviewed()
        clock += 30.seconds // 30 s of the minute left: the check gets 15, the prompt keeps its least 15
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)

        viewModel.signWithBiometrics()
        runCurrent()
        clock += 16.seconds
        advanceTimeBy(16.seconds) // the check gives up at 15 s
        runCurrent()
        world.state = world.state // proven again: the new proposal is made
        runCurrent()

        assertEquals(emptyList(), world.signer.asked, "no prompt, so none to time out")
        assertEquals(
            "Checking the Safe on chain took the time the quote had left, so nothing was signed and the quote was " +
                "made again: check it, then sign.",
            viewModel.review.unlock.error,
        )
        assertFalse(viewModel.review.signing)
    }

    @Test
    fun aCheckOfTheSafeThatOutlastsTheProposalsMinuteProposesAgainRatherThanSign() = runTest {
        val viewModel = reviewed()
        clock += 50.seconds // 10 s of the minute left for the check
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        viewModel.onPasswordChange("made-up test password")

        viewModel.sign()
        runCurrent()
        clock += 11.seconds
        advanceTimeBy(11.seconds)
        runCurrent()
        world.state = world.state
        runCurrent()

        assertEquals(emptyList(), world.signer.asked, "nothing signed on a proposal past its minute")
        assertEquals(
            "Checking the Safe on chain took the time the quote had left, so nothing was signed and the quote was " +
                "made again: check it, then sign.",
            viewModel.review.unlock.error,
        )
    }

    @Test
    fun aRefusedCheckBeforeSigningLeavesTheReviewIdleSoALaterRefreshOutlivesBeingHidden() = runTest {
        val viewModel = reviewed()
        viewModel.onShown()
        viewModel.onPasswordChange("made-up test password")
        world.kind.value = KindReading.Unavailable(ReadUnavailable.NO_ENGINE)
        viewModel.sign()
        runCurrent()
        assertFalse(viewModel.review.signing)
        assertFalse(viewModel.review.checking, "the check is over, refused")

        // Proposed again while the Safe is unproven, and the screen hidden and shown meanwhile.
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        viewModel.refresh()
        runCurrent()
        assertTrue(viewModel.review.refreshing)
        viewModel.onHidden()
        viewModel.onShown()
        world.state = world.state
        runCurrent()

        assertFalse(viewModel.review.refreshing, "the refresh was not cancelled with the check long over")
        viewModel.onPasswordChange("made-up test password")
        assertTrue(viewModel.review.canSign, "proposed again, and open to sign")
    }

    @Test
    fun backDuringTheCheckWithAnUnsavedSignatureStopsTheCheckBeforeAskingAboutIt() = runTest {
        val viewModel = reviewed()
        viewModel.onShown()
        world.queue.failSaves = true
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        assertIs<ShareItem>(viewModel.review.share, "signed, not saved")
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        assertTrue(viewModel.review.checking)

        viewModel.edit()
        world.state = world.state // the Safe proven after Back
        advanceTimeBy(60.seconds)
        runCurrent()

        assertEquals(1, world.signer.asked.size, "the first signature only: none after Back")
        assertTrue(viewModel.review.askingToDrop)
        assertFalse(viewModel.review.signing)
        assertFalse(viewModel.review.checking)
        viewModel.dropUnsaved()
        assertIs<SafeProposeUiState.Compose>(viewModel.uiState.value)
    }

    @Test
    fun aTapTooLateInTheProposalsMinuteForThePromptMakesItAgainAndSaysWhy() = runTest {
        biometrics = BiometricUnlock.On
        val viewModel = reviewed()
        clock += 50.seconds

        viewModel.signWithBiometrics()
        runCurrent()

        assertEquals(emptyList(), world.signer.asked, "nothing asked of the signer")
        assertEquals(
            "Too little of the quote's minute was left for the fingerprint or face check, so the quote was made " +
                "again: check it, then sign.",
            viewModel.review.unlock.error,
        )
    }

    @Test
    fun backFromTheReviewReturnsToTheFormAsItWas() = runTest {
        val viewModel = reviewed()

        viewModel.edit()

        assertEquals("0.1", viewModel.form.amount)
    }

    @Test
    fun aSafeWithNoOwnerHeldHereCannotPropose() = runTest {
        world.state = world.state.copy(owners = listOf(world.bob.address, world.carol))
        val viewModel = viewModel()

        val cannot = assertIs<SafeProposeUiState.CannotPropose>(viewModel.uiState.value)
        assertTrue(cannot.message.startsWith("No owner of this Safe has its key on this device"), cannot.message)
    }

    @Test
    fun anAccountThatIsNoSafeCannotPropose() = runTest {
        val viewModel = viewModel(from = world.alice.address)

        assertEquals(
            SafeProposeUiState.CannotPropose(
                "Sepolia",
                "Only a Safe proposes a Safe transaction: this account sends with Send.",
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun aProposalMadeWhileHiddenIsMadeAgainBeforeItIsSigned() = runTest {
        holding()
        val viewModel = viewModel()
        viewModel.onShown()
        viewModel.onToChange(Eip55.format(world.recipient))
        viewModel.onAmountChange("0.1")
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        world.inputs.gate = gate

        viewModel.review()
        runCurrent()
        viewModel.onHidden()
        world.inputs.gate = null
        gate.complete(Unit)
        runCurrent()
        assertTrue(viewModel.review.idle)
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        assertEquals(emptyList(), world.signer.asked, "made before the hide: proposed again, not signed")
        assertTrue(viewModel.review.idle)
    }

    @Test
    fun theReviewStandsWhileTheSafesReadingIsGoneAndASignatureUnderWayLands() = runTest {
        val viewModel = reviewed()
        val pending = CompletableDeferred<Signing>()
        world.signer.pending = pending
        viewModel.onPasswordChange("made-up test password")

        world.kind.value = KindReading.Syncing
        runCurrent()
        assertEquals("Safe nonce 4", viewModel.review.nonce, "the review stands on its proposal")
        world.kind.value = KindReading.Verified(AddressKind.Safe(world.state), 100)
        runCurrent()
        viewModel.sign()
        runCurrent()
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        runCurrent()
        assertTrue(viewModel.review.signing, "the signature under way stays on screen")
        pending.complete(Signing.Signed(world.recovery.sign(world.alice.address, world.request(world.ether(4)).hash)))
        runCurrent()

        assertIs<SafeProposeUiState.Proposed>(viewModel.uiState.value)
    }

    @Test
    fun anAddedTokenThatTakesABuiltInSymbolIsSaidToBeAnotherContract() = runTest {
        val impostor = Token(Address.of("0x" + "cd".repeat(20)), "USDC", "Not USD Coin", 6, icon = null)
        world.userTokens.add(Network.SEPOLIA, impostor)
        holding()
        world.tokenBalances.of(world.safe, impostor.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("50000000"), 99)
        val viewModel = viewModel(asset = impostor.address)
        viewModel.onToChange(Eip55.format(world.recipient))
        viewModel.onAmountChange("40")
        runCurrent()

        viewModel.review()
        runCurrent()

        assertEquals("Not the built-in USDC: another contract", viewModel.review.action.warning)
    }

    @Test
    fun aSignatureTheQueueCouldNotSaveOutlivesAReProposalThatFails() = runTest {
        val viewModel = reviewed()
        viewModel.onShown()
        world.queue.failSaves = true
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        val shared = assertIs<ShareItem>(viewModel.review.share)

        // The share sheet hides the screen; back, the proposal is made again, and that fails.
        viewModel.onHidden()
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        viewModel.onShown()
        // The Safe's check waits out its budget for a reading it can verify, then says so.
        advanceTimeBy(46.seconds)
        runCurrent()

        val review = viewModel.review
        assertEquals(shared, review.share, "still there to be shared")
        assertEquals("The Safe's owners and nonce could not be verified this time: try again.", review.error)
    }
}
