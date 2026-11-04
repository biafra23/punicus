package eth.serotine.ui.createsafe

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeCreation
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.BiometricUnlock
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.DeploySafe
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.PlanSafe
import eth.serotine.domain.usecase.QuoteSafeDeployment
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.WarmFeeHistory
import eth.serotine.ui.FactoryInputs
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeBalances
import eth.serotine.ui.FakeBroadcaster
import eth.serotine.ui.FakeNames
import eth.serotine.ui.FakeReceipts
import eth.serotine.ui.FakeReleases
import eth.serotine.ui.FakeSigner
import eth.serotine.ui.FakeTransactionInputs
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.MemorySentTransfers
import eth.serotine.ui.TEST_RELEASE
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NetworkChip
import eth.serotine.ui.components.NetworkLabel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** [CreateSafeViewModel] over fake ports, through the real use cases. */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateSafeViewModelTest {

    private val daily = Account(
        Network.SEPOLIA,
        Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )

    // EIP-55's own examples: 0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359 and 0xdbF03B40….
    private val savings = Account(
        Network.SEPOLIA,
        Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"),
        AccountKind.EOA,
        "Savings",
        AccountCustody.Watched,
    )
    private val spare = Account(
        Network.SEPOLIA,
        Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"),
        AccountKind.EOA,
        "Spare",
        AccountCustody.InKeyStore,
    )
    private val treasury =
        Account(
            Network.SEPOLIA,
            Address.of("0x" + "5a".repeat(20)),
            AccountKind.SAFE,
            "Treasury",
            AccountCustody.Watched,
        )
    private val friend = Address.of("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb")

    private val accounts = FakeAccounts(daily, savings, treasury)
    private val balances = FakeBalances()
    private val fakeInputs = FakeTransactionInputs()
    private val inputs = FactoryInputs(fakeInputs)
    private val names = FakeNames()
    private val signer = FakeSigner()
    private val broadcaster = FakeBroadcaster()
    private val sent = MemorySentTransfers()
    private val receipts = FakeReceipts()
    private val releases = FakeReleases()
    private val clock = TestTimeSource()
    private val kind = MutableStateFlow<KindReading>(KindReading.Verified(AddressKind.Eoa, 9_412_118))
    private val kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> = kind
    }
    private var salts = 0L
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

    private val networks = MemoryNetworkSettings()

    private fun TestScope.viewModel(): CreateSafeViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = CreateSafeViewModel(
            ObserveAccounts(accounts),
            DescribeKeyStore(keys),
            ResolveRecipient(names),
            PlanSafe(releases) { UInt256.of(++salts) },
            QuoteSafeDeployment(kinds, balances, inputs, sent) { 1_800_000_000 },
            DeploySafe(signer, broadcaster, sent) { 1_800_000_000 },
            RebroadcastTransfer(broadcaster, sent, receipts),
            WarmFeeHistory(inputs),
            ObserveEnabledNetworks(networks),
            SetNetworkEnabled(networks),
            clock,
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private val CreateSafeViewModel.owners get() = assertIs<CreateSafeUiState.Owners>(uiState.value)
    private val CreateSafeViewModel.threshold get() = assertIs<CreateSafeUiState.Threshold>(uiState.value)
    private val CreateSafeViewModel.review get() = assertIs<CreateSafeUiState.Review>(uiState.value)

    /** Where a Safe of [owners] at [threshold] with salt nonce [salt] is deployed, as the factory computes it. */
    private fun predicted(owners: List<Address>, threshold: Int, salt: Long): String = eip55(
        SafeCreation.proxyAddress(TEST_RELEASE, SafeCreation.setup(owners, threshold, Address.ZERO), UInt256.of(salt)),
    )

    private fun eip55(address: Address) = Eip55.format(address)

    /** Daily and Savings at 1 of 2, named "Family vault", under review. */
    private fun TestScope.reviewed(): CreateSafeViewModel {
        balances.of(daily.address).value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        val viewModel = viewModel()
        viewModel.onNameChange("Family vault")
        viewModel.onToggleOwner(eip55(savings.address))
        viewModel.onContinue()
        viewModel.onContinue()
        runCurrent()
        return viewModel
    }

    @Test
    fun theOwnersAreTheWalletsAccountsWithTheOneHeldHerePreselected() = runTest {
        val viewModel = viewModel()

        val state = viewModel.owners
        assertEquals("Sepolia", state.network)
        assertEquals(
            listOf(
                OwnerChoice("0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2", "Daily", "Key on this device", true),
                OwnerChoice(
                    "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
                    "Savings",
                    "Watch-only: its key is elsewhere",
                    false,
                ),
            ),
            state.accounts,
            "accounts with a key only: a Safe in the wallet is not offered",
        )
        assertEquals("1 owner", state.summary)
        assertTrue(state.canContinue)
        assertTrue("passkey signer" in state.deviceKeyNote, "this device's hardware key: said why it is not offered")

        viewModel.onToggleOwner(eip55(daily.address))
        assertEquals("No owner chosen yet", viewModel.owners.summary)
        assertFalse(viewModel.owners.canContinue)
        viewModel.onContinue()
        assertIs<CreateSafeUiState.Owners>(viewModel.uiState.value)
    }

    @Test
    fun withoutAKeyHereToPayNothingCanBeCreated() = runTest {
        accounts.accounts.value = listOf(savings, treasury)
        val viewModel = viewModel()

        val state = assertIs<CreateSafeUiState.CannotCreate>(viewModel.uiState.value)
        assertTrue("pay for its deployment" in state.message)
    }

    @Test
    fun choosingTheNetworkOnTheThresholdStepGoesBackToTheOwners() = runTest {
        val viewModel = viewModel()
        viewModel.onContinue()
        runCurrent()
        viewModel.threshold

        // Nothing is planned before the review: the network can still change, and the owners are looked at again.
        viewModel.onNetwork(Network.SEPOLIA.chainId)
        runCurrent()
        assertEquals(
            listOf(NetworkChip(NetworkLabel.of(Network.SEPOLIA), selected = true)),
            viewModel.owners.networks.filter { it.selected },
        )
    }

    @Test
    fun theNetworkComesFirstAndOneThatIsOffIsTurnedOnFromThere() = runTest {
        networks.on.value = emptySet()
        val viewModel = viewModel()

        // With none on, the first network stands chosen, greyed out with its reason, as every other is.
        val off = assertIs<CreateSafeUiState.CannotCreate>(viewModel.uiState.value)
        assertEquals("Ethereum is off in this wallet. Turn it on to create a Safe there.", off.message)
        assertEquals(
            NetworkChip(
                NetworkLabel.of(Network.SEPOLIA),
                selected = false,
                reason = "Sepolia is off in this wallet. Turn it on to create a Safe there.",
                off = true,
            ),
            off.networks.single { it.network.chainId == Network.SEPOLIA.chainId },
        )

        viewModel.onTurnOnNetwork(Network.SEPOLIA.chainId)
        runCurrent()
        assertEquals(
            listOf(NetworkChip(NetworkLabel.of(Network.SEPOLIA), selected = true)),
            viewModel.owners.networks.filter { it.selected },
        )
    }

    @Test
    fun aPayerGoneBeforeTheReviewSaysWhyAndNothingIsQuoted() = runTest {
        val viewModel = viewModel()
        viewModel.onNameChange("Family vault")
        viewModel.onToggleOwner(eip55(savings.address))
        viewModel.onContinue()
        assertIs<CreateSafeUiState.Threshold>(viewModel.uiState.value)

        // Daily is removed meanwhile: no account here can pay for the deployment any more.
        accounts.accounts.value = listOf(savings, treasury)
        viewModel.onContinue()
        runCurrent()

        val state = assertIs<CreateSafeUiState.CannotCreate>(viewModel.uiState.value, "said, not a dead tap")
        assertTrue("pay for its deployment" in state.message)
        accounts.accounts.value = listOf(daily, savings, treasury)
        runCurrent()
        assertIs<CreateSafeUiState.Threshold>(viewModel.uiState.value, "still at the threshold: no review was made")
    }

    @Test
    fun withoutAPinnedReleaseNothingCanBeCreated() = runTest {
        releases.release = null
        val viewModel = viewModel()

        assertIs<CreateSafeUiState.CannotCreate>(viewModel.uiState.value)
    }

    @Test
    fun aTypedAddressIsCheckedAtTheInputThenAdded() = runTest {
        val viewModel = viewModel()

        viewModel.onInputChange("0xdbf03b407c01E7cD3CBea99509d93f8DDDC8C6FB")
        assertTrue(viewModel.owners.inputError, "a checksum that fails")
        assertFalse(viewModel.owners.canAdd)

        viewModel.onInputChange("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb")
        assertEquals("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB", viewModel.owners.inputOwner?.address)
        assertTrue(viewModel.owners.canAdd)
        viewModel.onAddOwner()

        val state = viewModel.owners
        assertEquals("", state.input)
        assertEquals(
            listOf(OwnerChoice("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB", null, "Typed", true)),
            state.typed,
        )
        assertEquals("2 owners", state.summary)

        viewModel.onRemoveTyped("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB")
        assertEquals(emptyList(), viewModel.owners.typed)
    }

    @Test
    fun aWalletsAccountTypedIsChosenNotAddedTwice() = runTest {
        val viewModel = viewModel()

        viewModel.onInputChange(savings.address.hex)
        viewModel.onAddOwner()

        assertTrue(viewModel.owners.accounts.single { it.label == "Savings" }.selected)
        assertEquals(emptyList(), viewModel.owners.typed)
        viewModel.onInputChange(daily.address.hex)
        assertEquals("Already one of the owners.", viewModel.owners.inputNote)
        assertFalse(viewModel.owners.canAdd)
    }

    @Test
    fun aNameIsResolvedOnceTheTypingPausesAndAddedWithItsBlock() = runTest {
        names.names["friend.eth"] = NameResolution.Resolved("friend.eth", friend, 9_411_900)
        val viewModel = viewModel()

        viewModel.onInputChange("friend.eth")
        advanceTimeBy(1_000)
        runCurrent()
        viewModel.onAddOwner()

        assertEquals(
            OwnerChoice(
                "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
                "friend.eth",
                "Resolved from friend.eth at finalized block 9,411,900",
                true,
                simpleNote = "Resolved from friend.eth, checked on chain",
            ),
            viewModel.owners.typed.single(),
        )
    }

    @Test
    fun anAddressTheSafeReservesCannotOwnIt() = runTest {
        val viewModel = viewModel()

        viewModel.onInputChange("0x0000000000000000000000000000000000000001")
        viewModel.onAddOwner()

        val state = viewModel.owners
        assertEquals(
            "0x0000000000000000000000000000000000000001 cannot own a Safe: the Safe reserves it for its own lists.",
            state.error,
        )
        assertFalse(state.canContinue)
    }

    @Test
    fun aNameTooLongIsSaid() = runTest {
        val viewModel = viewModel()

        viewModel.onNameChange("x".repeat(41))

        assertEquals("At most 40 characters.", viewModel.owners.nameError)
        assertFalse(viewModel.owners.canContinue)
    }

    @Test
    fun theThresholdIsHeldToTheRecoveryShapes() = runTest {
        val viewModel = viewModel()
        viewModel.onToggleOwner(eip55(savings.address))
        viewModel.onContinue()

        assertEquals(1, viewModel.threshold.threshold, "one, up to two owners")
        assertEquals("1 of 2 owners sign each transaction", viewModel.threshold.shape)
        assertTrue(viewModel.threshold.canContinue)
        assertFalse(viewModel.threshold.canDecrease)

        viewModel.onThresholdChange(2)
        val all = viewModel.threshold
        assertEquals(2, all.threshold)
        assertTrue(all.refusal!!.startsWith("Not 2 of 2: with one owner lost"))
        assertFalse(all.canContinue)
        assertFalse(all.canIncrease)
        viewModel.onContinue()
        assertIs<CreateSafeUiState.Threshold>(viewModel.uiState.value, "not on to the review")

        viewModel.onThresholdChange(5)
        assertEquals(2, viewModel.threshold.threshold, "never above the owners")

        // A third owner: 2 of 3 is the shape offered first.
        viewModel.back()
        viewModel.onInputChange(friend.hex)
        viewModel.onAddOwner()
        viewModel.onContinue()
        assertEquals(2, viewModel.threshold.threshold)
        assertNull(viewModel.threshold.refusal)
        assertTrue(viewModel.threshold.canContinue)
    }

    @Test
    fun theReviewShowsTheAddressComputedHereAndTheVerifiedQuote() = runTest {
        val viewModel = reviewed()

        val review = viewModel.review
        assertEquals("Family vault", review.name)
        assertEquals(predicted(listOf(daily.address, savings.address), 1, salt = 1), review.address)
        assertEquals("1/2", review.signers)
        assertEquals("Safe 1.4.1", review.version)
        assertEquals("1 of 2 owners sign each transaction", review.shape)
        assertEquals(
            listOf("Daily", "Savings"),
            review.owners.map { it.label },
        )
        assertEquals(listOf(AccountChoice("Daily", "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2")), review.payers)
        assertEquals("0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2", review.payer)
        assertEquals(FeeLevel.NORMAL, review.fee)
        val quote = review.quote!!
        assertEquals("0.000462 ETH", quote.maxFee)
        assertEquals(
            BalanceChange("ETH balance of Daily", "1.5 ETH", "at least 1.499538 ETH", "verified at block 9,412,118"),
            quote.balance,
        )
        assertTrue("returns this address, which held no code at block 9,412,118" in review.prediction)
        // Simple mode's words for the same: no block (docs/simple-ui.md).
        assertTrue("block" !in review.simplePrediction.orEmpty() && review.simplePrediction != null)
        assertTrue("block" !in quote.simpleSimulation, quote.simpleSimulation)
        assertNull(review.error)
    }

    @Test
    fun aDeploymentIsSignedWithThePasswordAndRecordedAsTheSafeItCreates() = runTest {
        val viewModel = reviewed()
        val address = Address.of(viewModel.review.address)

        viewModel.onPasswordChange("correct horse battery")
        assertTrue(viewModel.review.canSign)
        viewModel.sign()
        runCurrent()

        assertIs<CreateSafeUiState.Sent>(viewModel.uiState.value)
        assertEquals(daily.address, signer.asked.single().first)
        assertEquals(listOf("correct horse battery"), signer.passwords)
        assertEquals(
            SentAction.SafeDeployment(address, listOf(daily.address, savings.address), 1, "1.4.1", "Family vault"),
            sent.transfers.value.single().action,
        )
    }

    @Test
    fun aWrongPasswordIsSaidAndNothingLeavesTheDevice() = runTest {
        signer.answer = Signing.WrongPassword
        val viewModel = reviewed()

        viewModel.onPasswordChange("mistyped")
        viewModel.sign()
        runCurrent()

        val review = viewModel.review
        assertEquals("That is not the wallet password. Nothing was signed.", review.unlock.error)
        assertEquals("", review.unlock.password.text, "the password is gone")
        assertTrue(broadcaster.sent.isEmpty())
    }

    @Test
    fun theFingerprintPromptNamesTheSafeInFull() = runTest {
        biometrics = BiometricUnlock.On
        val viewModel = reviewed()
        val address = viewModel.review.address
        assertTrue(viewModel.review.canSignWithBiometrics)

        viewModel.signWithBiometrics()
        runCurrent()

        val unlock = assertIs<Unlock.WithBiometrics>(signer.asked.single().second)
        assertEquals("Deploy a 1-of-2 Safe at $address, paid from Daily", unlock.reason)
        assertEquals(60.seconds, -unlock.notAfter!!.elapsedNow(), "the prompt gets what is left of the quote's minute")
    }

    @Test
    fun aFingerprintPromptAnsweredAfterTheQuotesAgeSignsNothingAndTheQuoteIsMadeAgain() = runTest {
        biometrics = BiometricUnlock.On
        val viewModel = reviewed()
        clock += 45.seconds
        signer.answer = Signing.TimedOut

        viewModel.signWithBiometrics()
        runCurrent()

        assertEquals(15.seconds, -(signer.asked.single().second as Unlock.WithBiometrics).notAfter!!.elapsedNow())
        assertTrue(broadcaster.sent.isEmpty(), "nothing signed, nothing sent")
        val review = viewModel.review
        assertEquals(
            "The fingerprint or face check took longer than the quote could wait, so nothing was signed and the " +
                "quote was made again: check it, then sign.",
            review.unlock.error,
        )
        assertTrue(review.canSignWithBiometrics, "quoted again, and offered again")
    }

    @Test
    fun aFactoryThatReturnsAnotherAddressIsNotSigned() = runTest {
        inputs.deployment = Simulation.Succeeded("0".repeat(24) + "ee".repeat(20), 100)
        val viewModel = reviewed()

        val review = viewModel.review
        assertNull(review.quote)
        val elsewhere = eip55(Address.of("0x" + "ee".repeat(20)))
        assertEquals(
            "The factory's verified run returns $elsewhere, not the address computed here: the Safe release " +
                "Serotine pins does not match the chain, so nothing is deployed.",
            review.error,
        )
        viewModel.onPasswordChange("correct horse battery")
        assertFalse(viewModel.review.canSign)
        viewModel.sign()
        assertTrue(signer.asked.isEmpty())
    }

    @Test
    fun aPayerWithoutTheFeeIsSaidAndAnotherCanPay() = runTest {
        accounts.accounts.value = listOf(daily, savings, spare)
        balances.of(spare.address).value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        balances.of(daily.address).value = BalanceReading.Verified(Wei.of("1000"), 9_412_118)
        val viewModel = viewModel()
        viewModel.onContinue()
        viewModel.onContinue()
        runCurrent()

        assertEquals(
            "Not enough ETH in this account for the most the fee can be. Transactions not mined yet count too.",
            viewModel.review.error,
        )
        val address = viewModel.review.address
        viewModel.onPayerChange(eip55(spare.address))
        runCurrent()

        assertEquals(eip55(spare.address), viewModel.review.payer)
        assertEquals("ETH balance of Spare", viewModel.review.quote?.balance?.what)
        assertEquals(address, viewModel.review.address, "the same plan, the same address")
    }

    @Test
    fun theFeeHistoryIsAskedForAsTheScreenOpens() = runTest {
        viewModel()
        runCurrent()

        assertEquals(1, fakeInputs.feeReads, "warm by the review")
    }

    @Test
    fun aRetryOfADeploymentMaybeSentTurnedAwayKeepsTryAgainAndBackClosed() = runTest {
        broadcaster.answers += Broadcast.Unavailable("no peer")
        broadcaster.answers += Broadcast.Rejected("already known")
        val viewModel = reviewed()
        viewModel.onPasswordChange("correct horse battery")
        viewModel.sign()
        runCurrent()

        viewModel.retry()
        runCurrent()

        val notSent = viewModel.review.notSent!!
        assertTrue(notSent.canRetry, "still maybe sent: the same bytes may be tried again")
        assertTrue(notSent.message.startsWith("Turned away this time (the peer said: already known)."))
        viewModel.back()
        assertIs<CreateSafeUiState.Review>(viewModel.uiState.value, "nothing new while the first may be out there")
    }

    @Test
    fun aDeploymentSignedButNotSentIsRetriedWithItsOwnBytes() = runTest {
        broadcaster.answers += Broadcast.Unavailable("no peer")
        val viewModel = reviewed()
        viewModel.onPasswordChange("correct horse battery")
        viewModel.sign()
        runCurrent()

        assertTrue(viewModel.review.notSent!!.canRetry)
        viewModel.back()
        assertIs<CreateSafeUiState.Review>(viewModel.uiState.value, "no step back from bytes that may be out there")
        viewModel.onFeeChange(FeeLevel.FAST)
        assertEquals(FeeLevel.NORMAL, viewModel.review.fee, "no new quote either")

        viewModel.retry()
        runCurrent()

        assertIs<CreateSafeUiState.Sent>(viewModel.uiState.value)
        assertEquals(1, signer.asked.size)
        assertContentEquals(broadcaster.sent[0], broadcaster.sent[1], "the very bytes, not a new quote")
    }

    @Test
    fun aQuoteTooOldAtTheTapIsMadeAgainBeforeAnythingIsSigned() = runTest {
        val viewModel = reviewed()
        viewModel.onPasswordChange("correct horse battery")
        clock += 61.seconds
        fakeInputs.nonce = 8

        viewModel.sign()
        runCurrent()

        assertTrue(signer.asked.isEmpty(), "nothing signed on the old quote")
        assertEquals(
            "The quote was over a minute old, so it was made again: check it, then sign.",
            viewModel.review.unlock.error,
        )
        viewModel.sign()
        runCurrent()
        assertEquals(8, sent.transfers.value.single().nonce, "the fresh quote is the one signed")
    }

    @Test
    fun thePasswordIsDroppedWhenTheScreenIsHiddenAndTheReviewIsQuotedAgainOnReturn() = runTest {
        val viewModel = reviewed()
        viewModel.onShown()
        viewModel.onPasswordChange("correct horse battery")

        viewModel.onHidden()
        assertEquals("", viewModel.review.unlock.password.text)

        fakeInputs.nonce = 8
        viewModel.onShown()
        runCurrent()
        viewModel.onPasswordChange("correct horse battery")
        viewModel.sign()
        runCurrent()
        assertEquals(8, sent.transfers.value.single().nonce, "the fresh quote is the one signed")
    }

    @Test
    fun backFromTheReviewDropsThePlanAndTheNextReviewPlansAnew() = runTest {
        val viewModel = reviewed()
        val first = viewModel.review.address
        viewModel.onPasswordChange("correct horse battery")

        viewModel.back()
        assertIs<CreateSafeUiState.Threshold>(viewModel.uiState.value)
        viewModel.onContinue()
        runCurrent()

        assertNotEquals(first, viewModel.review.address, "another salt, another address")
        assertEquals("", viewModel.review.unlock.password.text)
    }

    /** Daily and friend.eth at 1 of 2, under review. */
    private fun TestScope.reviewedWithAName(): CreateSafeViewModel {
        names.names["friend.eth"] = NameResolution.Resolved("friend.eth", friend, 9_411_900)
        balances.of(daily.address).value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        val viewModel = viewModel()
        viewModel.onInputChange("friend.eth")
        advanceTimeBy(1_000)
        runCurrent()
        viewModel.onAddOwner()
        viewModel.onContinue()
        viewModel.onContinue()
        runCurrent()
        return viewModel
    }

    @Test
    fun anOwnerAddedByANameIsResolvedAgainWithEveryQuote() = runTest {
        val viewModel = reviewedWithAName()
        assertEquals(listOf("friend.eth", "friend.eth"), names.asked, "once when added, again for the quote")
        assertTrue(viewModel.review.quote != null)

        names.names["friend.eth"] = NameResolution.Resolved("friend.eth", savings.address, 9_412_000)
        viewModel.onPasswordChange("correct horse battery")
        viewModel.refresh()
        runCurrent()

        val owners = viewModel.owners
        assertEquals(
            "“friend.eth” no longer resolves to the address added as an owner, " +
                "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB: check the owner, then take it out and add it again.",
            owners.error,
        )
        assertTrue(signer.asked.isEmpty())

        // Taking it out answers it.
        viewModel.onRemoveTyped("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB")
        assertNull(viewModel.owners.error)
    }

    @Test
    fun aNameThatCannotBeVerifiedAgainIsNotDeployedWith() = runTest {
        names.names["friend.eth"] = NameResolution.Resolved("friend.eth", friend, 9_411_900)
        val viewModel = viewModel()
        viewModel.onInputChange("friend.eth")
        advanceTimeBy(1_000)
        runCurrent()
        viewModel.onAddOwner()
        viewModel.onContinue()
        names.names["friend.eth"] = NameResolution.Unavailable(ReadUnavailable.UNPROVEN)
        viewModel.onContinue()
        runCurrent()

        val error = viewModel.owners.error!!
        assertTrue(error.startsWith("“friend.eth” could not be resolved with verified calls again"))
    }

    @Test
    fun aSecondSafeOfTheSameOwnersIsNotDeployedWhileTheFirstIsUnmined() = runTest {
        val first = reviewed()
        first.onPasswordChange("correct horse battery")
        first.sign()
        runCurrent()
        assertIs<CreateSafeUiState.Sent>(first.uiState.value)
        val deployed = (sent.transfers.value.single().action as SentAction.SafeDeployment).safe

        val second = reviewed()

        assertNotEquals(eip55(deployed), second.review.address, "planned anew, under another salt")
        assertEquals(
            "This wallet sent the deployment of a Safe with these owners and this threshold already, at " +
                "${eip55(deployed)}, and no block has it yet: it would be a second Safe of the same shape. Wait " +
                "for it, or change the owners or the threshold.",
            second.review.error,
        )
        assertNull(second.review.quote)
    }

    @Test
    fun anAddressWithCodeAlreadyIsRefused() = runTest {
        kind.value = KindReading.Verified(AddressKind.Contract, 9_412_118)
        val viewModel = reviewed()

        assertTrue(viewModel.review.error!!.startsWith("This address holds code already"))
        assertNull(viewModel.review.quote)
    }
}
