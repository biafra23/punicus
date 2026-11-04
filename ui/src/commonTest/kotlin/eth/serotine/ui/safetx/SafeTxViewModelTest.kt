package eth.serotine.ui.safetx

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.ApprovalReading
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
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.ApproveSafeTxOnChain
import eth.serotine.domain.usecase.CheckSafeSwap
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.EncodeSigningRequest
import eth.serotine.domain.usecase.ExecuteSafeTx
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveSafeTxQueue
import eth.serotine.domain.usecase.QuoteSafeApproval
import eth.serotine.domain.usecase.QuoteSafeExecution
import eth.serotine.domain.usecase.ReadSafeTxOwners
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.RemoveQueuedSafeTx
import eth.serotine.domain.usecase.SignSafeTx
import eth.serotine.domain.usecase.WarmFeeHistory
import eth.serotine.ui.DAY
import eth.serotine.ui.HEAD_TIME
import eth.serotine.ui.SafeWorld
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.SafeSwapItem
import eth.serotine.ui.components.ShareItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** [SafeTxViewModel] over fake ports, through the real use cases. */
@OptIn(ExperimentalCoroutinesApi::class)
class SafeTxViewModelTest {

    private val world = SafeWorld()
    private val clock = TestTimeSource()
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

    /** What `execTransaction` returns when the Safe's call succeeded. */
    private val executed = Simulation.Succeeded("0".repeat(63) + "1", 100)

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        request: SigningRequest,
        collect: Boolean = true,
        imported: Boolean = false,
    ): SafeTxViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val now = { 1_800_000_000L }
        val viewModel = SafeTxViewModel(
            AccountId(Network.SEPOLIA, world.safe),
            request.hash,
            ObserveAccounts(world.accounts),
            ObserveSafeTxQueue(world.queue, world.observeSafe, world.recovery, Network.SEPOLIA),
            world.knownTokens,
            ReadSafeTxOwners(world.observeSafe, world.recovery, world.approvals, Network.SEPOLIA),
            EncodeSigningRequest(world.codec),
            DescribeKeyStore(keys),
            SignSafeTx(world.signer, world.observeSafe, world.recovery, world.queue, Network.SEPOLIA),
            QuoteSafeApproval(
                world.observeSafe,
                world.approvals,
                world.balances,
                world.inputs,
                world.sent,
                Network.SEPOLIA,
                now,
            ),
            ApproveSafeTxOnChain(world.signer, world.broadcaster, world.sent, now),
            QuoteSafeExecution(
                world.observeSafe,
                world.recovery,
                world.approvals,
                world.balances,
                world.inputs,
                world.sent,
                Network.SEPOLIA,
                now,
            ),
            ExecuteSafeTx(world.signer, world.broadcaster, world.sent, now),
            RebroadcastTransfer(world.broadcaster, world.sent, world.receipts, now),
            RemoveQueuedSafeTx(world.queue),
            WarmFeeHistory(world.inputs),
            CheckSafeSwap(world.uniswap, world.lightClient, Network.SEPOLIA),
            clock,
            imported,
            formatTime = { "at $it" },
        )
        if (collect) backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private fun queued(request: SigningRequest): SigningRequest {
        world.queue.requests.value += request
        return request
    }

    private fun funded() {
        world.balances.of(world.alice.address).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        world.balances.of(world.dave.address).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
    }

    private val SafeTxViewModel.overview get() = assertIs<SafeTxUiState.Overview>(uiState.value)
    private val SafeTxViewModel.signing get() = assertIs<SafeTxUiState.Signing>(uiState.value)
    private val SafeTxViewModel.review get() = assertIs<SafeTxUiState.Review>(uiState.value)

    @Test
    fun theOverviewShowsTheTransactionDecodedHereItsNonceAndEveryOwnersStand() = runTest {
        val request = queued(world.request(world.usdcTransfer(4), world.carol))
        world.approvals.approved[world.bob.address] = ApprovalReading.Read(approved = true, blockNumber = 99)
        val viewModel = viewModel(request)

        val state = viewModel.overview
        assertEquals(SafeHeader("Treasury", Eip55.format(world.safe), "2/3"), state.safe)
        assertEquals("Send 1.5 USDC", state.action.title)
        assertEquals(
            listOf("To" to Eip55.format(world.recipient), "Token contract" to Eip55.format(world.usdc.address)),
            state.action.addresses.map { it.what to it.address },
        )
        assertEquals("Safe nonce 4 · next to run", state.nonce)
        assertEquals("0x" + request.hash.hex, state.hash)
        val owners = assertIs<OwnersItem.Shown>(state.owners)
        assertEquals(
            listOf(
                OwnerStateRow(
                    Eip55.format(world.alice.address),
                    "Alice",
                    heldHere = true,
                    OwnerState.MISSING,
                    "Not yet",
                ),
                OwnerStateRow(
                    Eip55.format(world.bob.address),
                    "Bob",
                    heldHere = false,
                    OwnerState.APPROVED,
                    "Approved on chain, verified at block 99",
                    simpleDetail = "Approved on chain",
                ),
                OwnerStateRow(Eip55.format(world.carol), null, heldHere = false, OwnerState.SIGNED, "Signed"),
            ),
            owners.rows,
        )
        assertEquals("2 of 3 owners must confirm", owners.threshold)
        assertEquals("2 of 2 confirmations", owners.confirmations)
        assertEquals("Owners checked on chain", owners.simpleVerifiedAt, "simple mode's words, no block")
        assertTrue(state.canSign, "Alice is held here and has not confirmed it")
        assertFalse(state.canApprove, "the confirmations reach the threshold already")
        assertTrue(state.canExecute)
        assertEquals(null, state.refusal)
        assertEquals(null, state.gasTerms)
    }

    @Test
    fun aSwapIsShownDecodedWithWhatItReturnsNowAgainstItsMinimumAndItsDeadline() = runTest {
        world.quoteSwap(out = 40_200_000_000_000_000)
        val viewModel = viewModel(queued(world.request(world.swap(4))))

        val state = viewModel.overview
        assertEquals("Swap 100 USDC for at least 0.04 ETH", state.action.title)
        assertEquals(
            listOf(
                "Router" to Eip55.format(world.uniswapDeployment.swapRouter02),
                "Token sold" to Eip55.format(world.usdc.address),
            ),
            state.action.addresses.map { it.what to it.address },
        )
        assertEquals(
            SafeSwapItem(
                listOf(
                    "Through Uniswap's SwapRouter02, its code proven by the light client.",
                    "Returns 0.0402 ETH now, by a verified QuoterV2 quote at block 100: it meets its minimum.",
                    "Refused by the router after at ${HEAD_TIME + DAY}.",
                ),
                // Simple mode's words for the same: no block (docs/simple-ui.md).
                simpleLines = listOf(
                    "Through Uniswap's SwapRouter02, its code proven by the light client.",
                    "Returns 0.0402 ETH now, as checked on chain: it meets its minimum.",
                    "Refused by the router after at ${HEAD_TIME + DAY}.",
                ),
            ),
            state.swap,
        )
    }

    @Test
    fun aSwapWorthProposingAgainIsGreyedOutWhereNoOwnerIsHeld() = runTest {
        world.quoteSwap(out = 50_000_000_000_000_000)
        world.state = world.state.copy(owners = listOf(world.bob.address, world.carol))
        val viewModel = viewModel(queued(world.request(world.swap(4))))

        val swap = viewModel.overview.swap
        assertEquals(true, swap?.proposeAgain)
        assertEquals(
            "No owner of this Safe has its key on this device, so the swap cannot be proposed again from here. " +
                "Import an owner's recovery phrase to propose and sign its transactions.",
            swap?.proposeAgainReason,
        )
    }

    @Test
    fun aSwapsMinimumFallenBehindIsSaidWithWhatItLeavesOpenAndAPassedDeadlineRefusesIt() = runTest {
        // 0.05 ETH now, less 0.5 % keeps 0.04975: a minimum of 0.04 leaves 0.01 open.
        world.quoteSwap(out = 50_000_000_000_000_000)
        val viewModel = viewModel(queued(world.request(world.swap(4), world.carol, world.bob.address)))
        assertTrue(viewModel.overview.canExecute, "still executable as signed (owner ruling, 2026-10-19)")
        assertEquals(
            "Its minimum has fallen behind the market: it returns 0.01 ETH more than its minimum now, more than " +
                "the default 0.5 % tolerance would leave, and a sandwich could take all of it. Propose it again " +
                "with a fresh minimum, or execute it as signed.",
            viewModel.overview.swap?.warning,
        )
        assertEquals(true, viewModel.overview.swap?.proposeAgain, "with the offer to propose it again")

        world.head.value =
            LightClientStatus.Synced(headBlock = 200, headTimestamp = HEAD_TIME + DAY + 1, finalized = null)
        viewModel.checkAgain()
        runCurrent()
        assertEquals(
            "Its deadline has passed: the router refuses it, so it can never run as signed. Replace it at its " +
                "nonce: propose the swap again, or reject it.",
            viewModel.overview.swap?.warning,
        )
        // Greyed out, saying why (owner ruling, 2026-10-18, #55), and not quoted on a tap.
        assertFalse(viewModel.overview.canExecute)
        assertEquals(
            "Its deadline has passed: the router refuses it, so it can never run as signed.",
            viewModel.overview.executeNote,
        )
        viewModel.startExecution()
        runCurrent()
        assertIs<SafeTxUiState.Overview>(viewModel.uiState.value)
    }

    @Test
    fun theSignStepShowsWhereTheSwapStandsBeforeTheKeySigns() = runTest {
        world.quoteSwap(out = 40_200_000_000_000_000)
        val viewModel = viewModel(queued(world.request(world.swap(4))))
        viewModel.startSigning()
        runCurrent()

        assertEquals(
            "Through Uniswap's SwapRouter02, its code proven by the light client.",
            viewModel.signing.swap?.lines?.first(),
        )
    }

    @Test
    fun anApprovalNamesUniswapsRouterAsItsSpender() = runTest {
        val viewModel = viewModel(queued(world.request(world.approval(4))))

        assertEquals("Allow a contract to take 100 USDC", viewModel.overview.action.title)
        assertEquals(
            listOf("The spender is Uniswap's SwapRouter02, its code proven by the light client."),
            viewModel.overview.swap?.lines,
        )
    }

    /** Alice's signing step, the password typed and Sign tapped while the Safe cannot be verified this time. */
    private fun TestScope.signingWhileTheSafeIsUnproven(): SafeTxViewModel {
        val viewModel = viewModel(queued(world.request(world.ether(4))))
        viewModel.startSigning()
        runCurrent()
        viewModel.onPasswordChange("made-up test password")
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        viewModel.sign()
        runCurrent()
        return viewModel
    }

    @Test
    fun whileTheSafeIsCheckedBeforeSigningTheStepSaysSoAndSignsOnceItPasses() = runTest {
        val viewModel = signingWhileTheSafeIsUnproven()

        advanceTimeBy(10.seconds)
        val checking = viewModel.signing
        assertTrue(checking.signing)
        assertTrue(checking.checking, "Checking the Safe on chain…")
        assertEquals(emptyList(), world.signer.passwords, "no key asked for while the Safe is checked")

        world.state = world.state // the next reading proves it
        runCurrent()

        assertEquals(listOf("made-up test password"), world.signer.passwords)
        assertTrue(viewModel.overview.message!!.startsWith("Signed as Alice."), viewModel.overview.message)
    }

    @Test
    fun leavingTheScreenWhileTheSafeIsCheckedStopsItAndAsksForNoKey() = runTest {
        val viewModel = signingWhileTheSafeIsUnproven()

        viewModel.onHidden()
        world.state = world.state // the Safe proven after all, once left
        advanceTimeBy(60.seconds)
        runCurrent()

        assertEquals(emptyList(), world.signer.asked, "no signer call, so no prompt over another screen")
        val left = viewModel.signing
        assertFalse(left.signing)
        assertFalse(left.checking)
        assertEquals("", left.unlock.password.text)
    }

    @Test
    fun backWhileTheSafeIsCheckedStopsItToo() = runTest {
        val viewModel = signingWhileTheSafeIsUnproven()

        viewModel.back()
        world.state = world.state
        advanceTimeBy(60.seconds)
        runCurrent()

        assertEquals(emptyList(), world.signer.asked)
        assertIs<SafeTxUiState.Overview>(viewModel.uiState.value)
    }

    @Test
    fun aTransactionJustImportedSaysWhichSafesQueueItWentToUntilAStepBegins() = runTest {
        val request = queued(world.request(world.ether(4), world.carol))
        val viewModel = viewModel(request, imported = true)

        assertEquals("Added to Treasury's queue on this device.", viewModel.overview.message)

        viewModel.startSigning()
        runCurrent()
        viewModel.back()
        assertEquals(null, viewModel.overview.message, "said once")

        // Opened any other way, it says nothing of the kind.
        assertEquals(null, viewModel(request).overview.message)
    }

    @Test
    fun shareRequestIsTheTextAnotherDeviceImportsInOneQrCode() = runTest {
        val request = queued(world.request(world.ether(4), world.carol))
        val viewModel = viewModel(request)

        viewModel.onShare()

        val share = assertIs<ShareItem>(viewModel.overview.share)
        assertEquals(world.codec.encode(request), share.text)
        assertTrue(share.qr)
        assertTrue("1 signature" in share.note, share.note)
        viewModel.onShareClosed()
        assertEquals(null, viewModel.overview.share)
    }

    @Test
    fun anOwnerHeldHereSignsTheHashComputedHereAndTheSignatureIsQueued() = runTest {
        val request = queued(world.request(world.ether(4)))
        val viewModel = viewModel(request)

        viewModel.startSigning()
        runCurrent()
        val signing = viewModel.signing
        assertEquals(listOf(SignerChoice("Alice", Eip55.format(world.alice.address))), signing.owners)
        assertEquals(Eip55.format(world.alice.address), signing.owner)
        viewModel.onPasswordChange("made-up test password")
        assertTrue(viewModel.signing.canSign)
        viewModel.sign()
        runCurrent()

        assertEquals(listOf(world.alice.address), world.queue.requests.value.single().signers)
        assertEquals(listOf("made-up test password"), world.signer.passwords)
        val state = viewModel.overview
        assertTrue(state.message!!.startsWith("Signed as Alice."), state.message)
        assertFalse(state.canSign, "Alice has signed")
        assertEquals("1 of 2 confirmations", assertIs<OwnersItem.Shown>(state.owners).confirmations)
    }

    @Test
    fun aWrongPasswordIsSaidAndNothingIsQueued() = runTest {
        val request = queued(world.request(world.ether(4)))
        val viewModel = viewModel(request)
        world.signer.answer = Signing.WrongPassword

        viewModel.startSigning()
        viewModel.onPasswordChange("not the password")
        viewModel.sign()
        runCurrent()

        val signing = viewModel.signing
        assertEquals("That is not the wallet password. Nothing was signed.", signing.unlock.error)
        assertEquals("", signing.unlock.password.text, "a password is used once")
        assertEquals(emptyList(), world.queue.requests.value.single().signers)
    }

    @Test
    fun aSignatureTheQueueCannotSaveStaysOnTheScreenToBeShared() = runTest {
        val request = queued(world.request(world.ether(4)))
        val viewModel = viewModel(request)
        world.queue.failSaves = true

        viewModel.startSigning()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        viewModel.onShare()

        val state = viewModel.overview
        assertTrue("cannot be saved" in state.message!!, state.message)
        assertTrue(state.share!!.text.endsWith(":1"), "the shared request carries the signature made here")
        assertEquals("1 of 2 confirmations", assertIs<OwnersItem.Shown>(state.owners).confirmations)
    }

    @Test
    fun aFallbackHandlerThatIsNotPinnedIsShownAsRefusedAndOffersNothingToSign() = runTest {
        val data = "f08a0323" + "0".repeat(24) + world.recipient.hex.removePrefix("0x")
        val setHandler = SafeTx.call(world.safe, UInt256.ZERO, data.hexBytes(), nonce = 4)
        val viewModel = viewModel(queued(world.request(setHandler)))

        val state = viewModel.overview
        assertEquals("Set the fallback handler", state.action.title)
        assertEquals(
            "It sets a fallback handler Serotine does not pin, ${Eip55.format(world.recipient)}: the handler answers " +
                "the signature checks other contracts make for the Safe, so only the canonical " +
                "CompatibilityFallbackHandler 1.4.1, or none, is signed, approved or executed here.",
            state.refusal,
        )
        assertFalse(state.canSign)
        assertFalse(state.canApprove)
        assertFalse(state.canExecute)
    }

    @Test
    fun enablingAModuleIsShownAsRefusedWithItsReasonAndOffersNothingToSign() = runTest {
        val data = "610b5925" + "0".repeat(24) + world.recipient.hex.removePrefix("0x")
        val enable = SafeTx.call(world.safe, UInt256.ZERO, data.hexBytes(), nonce = 4)
        val viewModel = viewModel(queued(world.request(enable)))

        val state = viewModel.overview
        assertEquals("Enable a module", state.action.title)
        assertEquals(
            "Enabling a module is refused: a module can move the Safe's funds without its owners. Which modules " +
                "may be enabled is still open (#54).",
            state.refusal,
        )
        assertFalse(state.canSign)
        assertFalse(state.canApprove)
        assertFalse(state.canExecute)
    }

    @Test
    fun aChangeThatLeavesEveryOwnerNeededIsShownAsRefusedAndOffersNothingToSign() = runTest {
        // The Safe is 2 of 3: a threshold of 3 leaves every owner needed, with no recovery module (#54).
        val data = "694e80c3" + "3".padStart(64, '0')
        val changeThreshold = SafeTx.call(world.safe, UInt256.ZERO, data.hexBytes(), nonce = 4)
        val viewModel = viewModel(queued(world.request(changeThreshold)))

        val state = viewModel.overview
        assertEquals(
            "It would leave all 3 owners needed to sign: with one of them lost, the rest could never reach the " +
                "threshold again. That needs a recovery module, and Serotine recognises none yet (#54), so it " +
                "does not sign, approve or execute it.",
            state.refusal,
        )
        assertFalse(state.canSign)
        assertFalse(state.canApprove)
        assertFalse(state.canExecute)
    }

    private fun String.hexBytes(): ByteArray =
        ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun aDelegateCallIsShownForWhatItIsAndOffersNothingToSign() = runTest {
        val delegate = SafeTx(
            to = world.recipient,
            value = UInt256.ZERO,
            data = byteArrayOf(1, 2, 3, 4),
            operation = SafeOperation.DELEGATECALL,
            safeTxGas = UInt256.ZERO,
            baseGas = UInt256.ZERO,
            gasPrice = UInt256.ZERO,
            gasToken = Address.ZERO,
            refundReceiver = Address.ZERO,
            nonce = 4,
        )
        val viewModel = viewModel(queued(world.request(delegate)))

        val state = viewModel.overview
        assertEquals("Delegate call", state.action.title)
        assertEquals(
            "A delegate call: Serotine does not sign, approve or execute one yet. Batches through the pinned " +
                "MultiSendCallOnly come later.",
            state.refusal,
        )
        assertFalse(state.canSign)
        assertFalse(state.canApprove)
        assertFalse(state.canExecute)
    }

    @Test
    fun gasAndRefundTermsAreShownAndOfferNothingToSign() = runTest {
        val refund = SafeTx(
            to = world.recipient,
            value = UInt256.of(1),
            data = ByteArray(0),
            operation = SafeOperation.CALL,
            safeTxGas = UInt256.of(50_000),
            baseGas = UInt256.ZERO,
            gasPrice = UInt256.of(1_000_000_000),
            gasToken = Address.ZERO,
            refundReceiver = world.carol,
            nonce = 4,
        )
        val viewModel = viewModel(queued(world.request(refund)))

        val state = viewModel.overview
        val terms = assertIs<eth.serotine.ui.components.GasTermsItem>(state.gasTerms)
        assertEquals("safeTxGas: 50,000", terms.lines.first())
        assertEquals(listOf(Eip55.format(world.carol)), terms.addresses.map { it.address })
        assertFalse(state.canSign)
    }

    @Test
    fun anApprovalOnChainIsQuotedReviewedSignedAndSent() = runTest {
        funded()
        val request = queued(world.request(world.ether(4)))
        val viewModel = viewModel(request)
        assertTrue(viewModel.overview.canApprove)

        viewModel.startApproval()
        runCurrent()
        val review = viewModel.review
        assertEquals(ReviewKind.APPROVAL, review.kind)
        assertEquals(
            "Checked on chain: the Safe's approveHash of this transaction should go through.",
            review.simpleSimulation,
        )
        assertEquals(Eip55.format(world.alice.address), review.account)
        assertEquals(FeeLevel.NORMAL, review.fee)
        assertEquals("ETH balance of Alice, which pays the fee", review.balance.what)
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        val sent = assertIs<SafeTxUiState.Sent>(viewModel.uiState.value)
        val recorded = world.sent.transfers.value.single()
        assertEquals(recorded.hash, sent.hash)
        assertEquals(SentAction.SafeApproval(request), recorded.action)
        assertEquals(world.alice.address, recorded.account)
    }

    @Test
    fun anExecutionCountsTheExecutingOwnersOwnConfirmation() = runTest {
        funded()
        world.inputs.simulation = executed
        val request = queued(world.request(world.ether(4), world.carol))
        val viewModel = viewModel(request)

        viewModel.startExecution()
        runCurrent()
        val review = viewModel.review
        assertEquals(ReviewKind.EXECUTION, review.kind)
        assertTrue(review.simpleSimulation.orEmpty().startsWith("Checked on chain: the whole execution"))
        assertTrue("block" !in review.simpleSimulation.orEmpty())
        assertEquals(Eip55.format(world.alice.address), review.account, "a held owner first")
        // Dave is no owner: from him, Carol's signature alone falls short.
        assertEquals(listOf(Eip55.format(world.alice.address)), review.accounts.map { it.address })
        assertEquals("Executed with: Alice, by sending the execution; 0x3333…3333's signature", review.confirmations)
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        assertIs<SafeTxUiState.Sent>(viewModel.uiState.value)
        assertEquals(SentAction.SafeExecution(request), world.sent.transfers.value.single().action)
    }

    @Test
    fun anyAccountHeldHereExecutesOnceTheSignaturesReachTheThreshold() = runTest {
        // Owner ruling, 2026-10-18, #56: owner or not, any account whose key is here may execute.
        funded()
        world.inputs.simulation = executed
        val viewModel = viewModel(queued(world.request(world.ether(4), world.bob.address, world.carol)))

        viewModel.startExecution()
        runCurrent()
        assertEquals(
            listOf(world.alice.address, world.dave.address).map(Eip55::format),
            viewModel.review.accounts.map { it.address },
            "a held owner first",
        )
        viewModel.onAccountChange(Eip55.format(world.dave.address))
        runCurrent()

        val review = viewModel.review
        assertEquals(Eip55.format(world.dave.address), review.account)
        assertEquals("Executed with: Bob's signature; 0x3333…3333's signature", review.confirmations)
        assertEquals("ETH balance of Dave, which pays the fee", review.balance.what)
    }

    @Test
    fun aSafeWithNoOwnerHeldHereIsExecutedFromAnotherAccountOfThisWallet() = runTest {
        funded()
        world.inputs.simulation = executed
        // Bob and Carol signed; Alice, held here, is no owner any more.
        world.state = world.state.copy(owners = listOf(world.bob.address, world.carol))
        val viewModel = viewModel(queued(world.request(world.ether(4), world.bob.address, world.carol)))

        val state = viewModel.overview
        assertTrue(state.canExecute, "Alice and Dave hold keys here, and either may pay")
        assertNull(state.executeNote)
    }

    @Test
    fun aTransactionNotNextIsNotOfferedForExecution() = runTest {
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(5), world.carol)))

        val state = viewModel.overview
        assertEquals("Safe nonce 5 · 1 transaction runs before it", state.nonce)
        assertFalse(state.canExecute)
        assertEquals(
            "Executed only once the transaction before it has run: only the Safe's next transaction can be " +
                "executed.",
            state.executeNote,
        )
    }

    @Test
    fun anExecutionShortOfTheThresholdSaysHowManyItHas() = runTest {
        val viewModel = viewModel(queued(world.request(world.ether(4))))

        val state = viewModel.overview
        assertFalse(state.canExecute)
        assertEquals("1 of the 2 confirmations it needs to be executed.", state.executeNote)
    }

    @Test
    fun theFeeHistoryIsAskedForAsTheScreenOpens() = runTest {
        viewModel(queued(world.request(world.ether(4))))
        runCurrent()

        assertEquals(1, world.inputs.feeReads, "warm by Approve or Execute")
    }

    @Test
    fun aRetryOfAnApprovalMaybeSentTurnedAwayKeepsTryAgainAndBackClosed() = runTest {
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(4))))
        world.broadcaster.answers += Broadcast.Unavailable("no peers")
        world.broadcaster.answers += Broadcast.Rejected("already known")
        viewModel.startApproval()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        viewModel.retry()
        runCurrent()

        val notSent = assertIs<eth.serotine.ui.components.NotSentItem>(viewModel.review.notSent)
        assertTrue(notSent.canRetry, "still maybe sent: the same bytes may be tried again")
        assertTrue(notSent.message.startsWith("Turned away this time (the peer said: already known)."))
        viewModel.back()
        assertIs<SafeTxUiState.Review>(viewModel.uiState.value, "only Try again may send those bytes")
    }

    @Test
    fun aBroadcastNoPeerTookIsRetriedWithItsOwnBytesAndBackWaitsForIt() = runTest {
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(4))))
        world.broadcaster.answers += Broadcast.Unavailable("no peers")

        viewModel.startApproval()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        val notSent = assertIs<eth.serotine.ui.components.NotSentItem>(viewModel.review.notSent)
        assertTrue(notSent.canRetry)
        viewModel.back()
        assertIs<SafeTxUiState.Review>(viewModel.uiState.value, "only Try again may send those bytes")

        viewModel.retry()
        runCurrent()

        assertIs<SafeTxUiState.Sent>(viewModel.uiState.value)
        assertEquals(2, world.broadcaster.sent.size)
        assertContentEquals(world.broadcaster.sent[0], world.broadcaster.sent[1])
        assertEquals(1, world.signer.asked.size, "nothing is signed twice")
    }

    @Test
    fun aQuotesAgeRunsFromAfterItsSafeCheckNotFromBeforeTheWait() = runTest {
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(4))))
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        viewModel.startApproval()
        runCurrent()
        // The Safe's check waits 40 s for a verified reading; the figures are read after it.
        clock += 40.seconds
        world.state = world.state
        runCurrent()
        viewModel.onPasswordChange("made-up test password")
        clock += 30.seconds // 70 s since the tap, 30 s since the figures

        viewModel.sign()
        runCurrent()

        assertEquals(listOf("made-up test password"), world.signer.passwords, "signed: the quote is 30 s old")
    }

    @Test
    fun aQuoteOverAMinuteOldIsMadeAgainAtTheTapThatWouldSignIt() = runTest {
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(4))))
        viewModel.startApproval()
        runCurrent()
        viewModel.onPasswordChange("made-up test password")
        clock += 61.seconds

        viewModel.sign()
        runCurrent()

        val review = viewModel.review
        assertEquals("The quote was over a minute old, so it was made again: check it, then sign.", review.unlock.error)
        assertEquals("made-up test password", review.unlock.password.text, "the same review, the password kept")
        assertEquals(emptyList(), world.signer.asked)
    }

    @Test
    fun thePasswordGoesWhenTheScreenIsHiddenAndTheReviewIsQuotedAgainWhenShown() = runTest {
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(4))))
        viewModel.onShown()
        viewModel.startApproval()
        runCurrent()
        viewModel.onPasswordChange("made-up test password")
        val quotes = world.inputs.estimated.size

        viewModel.onHidden()
        assertEquals("", viewModel.review.unlock.password.text)
        viewModel.onShown()
        runCurrent()

        assertEquals(quotes + 1, world.inputs.estimated.size, "quoted again")
        assertFalse(viewModel.review.refreshing)
        assertEquals(Eip55.format(world.alice.address), viewModel.review.account)
    }

    @Test
    fun aRefreshCutShortByTheQueueLeavesApproveOpenOnceItIsBack() = runTest {
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(4))))
        viewModel.startApproval()
        runCurrent()
        // A refresh that hangs on the engine; meanwhile the queue cannot be read, and the review ends.
        world.inputs.gate = CompletableDeferred()
        viewModel.refresh()
        runCurrent()
        world.queue.unreadable.value = true
        runCurrent()
        world.queue.unreadable.value = false
        runCurrent()
        assertIs<SafeTxUiState.Overview>(viewModel.uiState.value)

        // Back: Approve works, though the refresh that was cut short never answered.
        world.inputs.gate = null
        viewModel.startApproval()
        runCurrent()
        assertEquals(ReviewKind.APPROVAL, viewModel.review.kind)
    }

    @Test
    fun backFromAReviewReturnsToTheOverview() = runTest {
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(4))))
        viewModel.startApproval()
        runCurrent()

        viewModel.back()

        assertIs<SafeTxUiState.Overview>(viewModel.uiState.value)
    }

    @Test
    fun aRefusedQuoteIsSaidOnTheOverview() = runTest {
        // Alice holds no ether for the fee.
        world.balances.of(world.alice.address).value = BalanceReading.Verified(Wei.of("0"), 100)
        val viewModel = viewModel(queued(world.request(world.ether(4))))

        viewModel.startApproval()
        runCurrent()

        val state = viewModel.overview
        assertEquals(
            "Not enough ETH in this account for the most the fee can be. Transactions not mined yet count too.",
            state.message,
        )
    }

    @Test
    fun aQuoteThatRevertsIsSaidInSimpleModeWithNoBlockAndOnlyWhileItsMessageStands() = runTest {
        funded()
        world.inputs.simulation = Simulation.Reverted(100)
        val viewModel = viewModel(queued(world.request(world.ether(4))))

        viewModel.startApproval()
        runCurrent()
        val refused = viewModel.overview
        assertTrue("block 100" in refused.message.orEmpty(), refused.message)
        assertEquals("It reverts, checked on chain. Nothing was signed.", refused.simpleMessage)

        // Quoted again, it goes through: the message is cleared, and its simple words with it.
        world.inputs.simulation = Simulation.Succeeded("", 100)
        viewModel.startApproval()
        runCurrent()
        viewModel.back()
        runCurrent()
        val back = viewModel.overview
        assertEquals(null, back.message)
        assertEquals(null, back.simpleMessage, "no simple words for a message no longer shown")
    }

    @Test
    fun removingTakesItOutOfThisDevicesQueueAndTheScreenLeaves() = runTest {
        val viewModel = viewModel(queued(world.request(world.ether(4))))

        viewModel.remove()
        runCurrent()

        assertIs<SafeTxUiState.Removed>(viewModel.uiState.value)
        assertEquals(emptyList(), world.queue.requests.value)
    }

    @Test
    fun aRemovalUnderWayIsNotDroppedByAnApprovalOrAnExecutionAskedMeanwhile() = runTest {
        funded()
        world.inputs.simulation = executed
        val viewModel = viewModel(queued(world.request(world.ether(4), world.carol)))
        world.queue.removeGate = CompletableDeferred()

        viewModel.remove()
        runCurrent()
        viewModel.startExecution()
        viewModel.startApproval()
        runCurrent()
        assertEquals(emptyList(), world.inputs.estimated, "nothing quoted while the removal runs")

        world.queue.removeGate?.complete(Unit)
        runCurrent()
        assertIs<SafeTxUiState.Removed>(viewModel.uiState.value)
    }

    @Test
    fun anApprovalsBiometricPromptGetsWhatIsLeftOfItsQuoteAndOneAnsweredLateIsQuotedAgain() = runTest {
        biometrics = BiometricUnlock.On
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(4))))
        viewModel.startApproval()
        runCurrent()
        clock += 25.seconds

        world.signer.answer = Signing.TimedOut
        viewModel.signWithBiometrics()
        runCurrent()

        assertEquals(35.seconds, -(world.signer.asked.single().second as Unlock.WithBiometrics).notAfter!!.elapsedNow())
        assertEquals(emptyList(), world.broadcaster.sent, "nothing signed, nothing sent")
        assertEquals(2, world.inputs.estimated.size, "quoted again")
        assertEquals(
            "The fingerprint or face check took longer than the quote could wait, so nothing was signed and the " +
                "quote was made again: check it, then sign.",
            viewModel.review.unlock.error,
        )
    }

    @Test
    fun aTransactionTheSafesNoncePassedIsSaidToHaveRun() = runTest {
        val viewModel = viewModel(queued(world.request(world.ether(4))))
        assertIs<SafeTxUiState.Overview>(viewModel.uiState.value)

        world.state = world.state.copy(nonce = 5)
        runCurrent()

        val gone = assertIs<SafeTxUiState.NotQueued>(viewModel.uiState.value)
        assertEquals(
            "No longer pending: the Safe's nonce has passed it, so it ran, or another transaction used its nonce.",
            gone.message,
        )
    }

    @Test
    fun aHashNotInTheQueueSaysSo() = runTest {
        val viewModel = viewModel(world.request(world.ether(4)))

        assertEquals(
            SafeTxUiState.NotQueued("Sepolia", "This transaction is not in this device's queue."),
            viewModel.uiState.value,
        )
    }

    @Test
    fun theBiometricPromptSaysWhatIsSigned() = runTest {
        biometrics = BiometricUnlock.On
        val viewModel = viewModel(queued(world.request(world.ether(4))))

        viewModel.startSigning()
        runCurrent()
        assertTrue(viewModel.signing.unlock.biometrics)
        viewModel.signWithBiometrics()
        runCurrent()

        assertEquals(
            Unlock.WithBiometrics(
                "Sign Safe transaction 4 of Treasury: Send 0.1 ETH, to 0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
            ),
            world.signer.asked.single().second,
        )
    }

    @Test
    fun aRetriedRejectionThatCannotBeCheckedAgainstTheReceiptKeepsTryAgainOpen() = runTest {
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(4))))
        world.broadcaster.answers += Broadcast.Unavailable("no peers")
        world.broadcaster.answers += Broadcast.Rejected("nonce too low")
        viewModel.startApproval()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        val hash = SignedTransfer(world.broadcaster.sent.single()).hash
        world.receipts.of(hash).value = TransactionProgress.Unavailable(ReadUnavailable.UNPROVEN)

        viewModel.retry()
        runCurrent()

        val notSent = assertIs<NotSentItem>(viewModel.review.notSent)
        assertTrue(notSent.canRetry, "whether the first broadcast was mined is not known")
        assertTrue(notSent.message.startsWith("Rejected this time (nonce too low)"), notSent.message)
        viewModel.back()
        assertIs<SafeTxUiState.Review>(viewModel.uiState.value, "only Try again may send those bytes")
        world.receipts.of(hash).value = TransactionProgress.Included(130, succeeded = true)
        viewModel.retry()
        runCurrent()
        assertEquals(SafeTxUiState.Sent("Sepolia", hash), viewModel.uiState.value)
        assertEquals(1, world.signer.asked.size, "nothing is signed twice")
    }

    @Test
    fun aQuoteThatLandsWhileHiddenIsQuotedAgainWhenTheScreenIsShown() = runTest {
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(4))))
        viewModel.onShown()
        val gate = CompletableDeferred<Unit>()
        world.inputs.gate = gate

        viewModel.startApproval()
        runCurrent()
        viewModel.onHidden()
        gate.complete(Unit)
        runCurrent()
        val quotes = world.inputs.estimated.size
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        assertEquals(quotes + 1, world.inputs.estimated.size, "quoted before the hide: made again, not signed")
        assertEquals(emptyList(), world.signer.asked)
        viewModel.onShown()
        runCurrent()
        assertEquals(quotes + 1, world.inputs.estimated.size, "and not again once it was")
    }

    @Test
    fun aQuoteThatLandsAfterAHideAndAShowIsQuotedAgainAtOnce() = runTest {
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(4))))
        viewModel.onShown()
        viewModel.startApproval()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        world.inputs.gate = gate
        viewModel.refresh()
        runCurrent()

        viewModel.onHidden()
        viewModel.onShown()
        val quotes = world.inputs.estimated.size
        world.inputs.gate = null
        gate.complete(Unit)
        runCurrent()

        assertEquals(quotes + 1, world.inputs.estimated.size, "the refresh from before the hide is made again")
        assertFalse(viewModel.review.refreshing)
    }

    @Test
    fun aTokenThatOnlyTakesABuiltInSymbolIsSaidToBeAnotherContractUpToThePrompt() = runTest {
        biometrics = BiometricUnlock.On
        val impostor = Token(Address.of("0x" + "cd".repeat(20)), "USDC", "Not USD Coin", 6, icon = null)
        world.userTokens.add(Network.SEPOLIA, impostor)
        val transfer = SafeTx.call(
            impostor.address,
            UInt256.ZERO,
            Erc20.transferData(world.recipient, UInt256.of(40_000_000)),
            4,
        )
        val viewModel = viewModel(queued(world.request(transfer)))

        val action = viewModel.overview.action
        assertEquals("Send 40 USDC", action.title)
        assertEquals("Not the built-in USDC: another contract", action.warning)
        viewModel.startSigning()
        runCurrent()
        viewModel.signWithBiometrics()
        runCurrent()
        val prompt = assertIs<Unlock.WithBiometrics>(world.signer.asked.single().second).reason
        assertTrue("(Not the built-in USDC: another contract)" in prompt, prompt)
    }

    @Test
    fun aSigningUnderWayOutlivesTheQueuesReadingAndAStepThatIsNotEndsWithItsPassword() = runTest {
        val request = queued(world.request(world.ether(4)))
        val viewModel = viewModel(request)
        viewModel.startSigning()
        viewModel.onPasswordChange("made-up test password")
        val pending = CompletableDeferred<Signing>()
        world.signer.pending = pending
        viewModel.sign()
        runCurrent()

        world.queue.unreadable.value = true
        runCurrent()
        assertTrue(viewModel.signing.signing, "the signature under way stays on screen")
        pending.complete(Signing.WrongPassword)
        runCurrent()
        // Done signing, the step ends with the queue gone: no password is left without its field.
        world.signer.pending = null
        viewModel.onPasswordChange("made-up test password")
        world.queue.unreadable.value = false
        runCurrent()
        world.queue.unreadable.value = true
        runCurrent()

        assertIs<SafeTxUiState.NotQueued>(viewModel.uiState.value)
        world.queue.unreadable.value = false
        runCurrent()
        assertIs<SafeTxUiState.Overview>(viewModel.uiState.value, "the password went with the step")
    }

    @Test
    fun whileTheSafeIsNotVerifiedItsRequestIsNotShared() = runTest {
        val viewModel = viewModel(queued(world.request(world.ether(4), world.carol)))
        world.kind.value = KindReading.Syncing
        runCurrent()

        viewModel.onShare()

        val state = viewModel.overview
        assertFalse(state.canShare)
        assertEquals(null, state.share)
    }

    @Test
    fun anApprovalTheConfirmationsMadeNeedlessMeanwhileIsNotSigned() = runTest {
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(4), world.carol)))
        viewModel.startApproval()
        runCurrent()
        viewModel.onPasswordChange("made-up test password")

        world.approvals.approved[world.bob.address] = ApprovalReading.Read(approved = true, blockNumber = 130)
        viewModel.checkAgain()
        runCurrent()
        viewModel.sign()
        runCurrent()

        val review = viewModel.review
        assertEquals(
            "Its confirmations reach the threshold now: approving it is no longer needed. Go back to execute it.",
            review.notNeeded,
        )
        assertFalse(review.canSign)
        assertEquals(emptyList(), world.signer.asked)
    }

    @Test
    fun aReviewIsQuotedAgainOnReturnAfterAHideLongEnoughToLetTheQueuesReadingGo() = runTest {
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(4))), collect = false)
        val screen = backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        viewModel.onShown()
        viewModel.startApproval()
        runCurrent()
        assertIs<SafeTxUiState.Review>(viewModel.uiState.value)

        viewModel.onHidden()
        screen.cancel()
        advanceTimeBy(30.seconds)
        runCurrent()
        val quotes = world.inputs.estimated.size
        viewModel.onShown()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(quotes + 1, world.inputs.estimated.size, "quoted again on return, not at the first tap")
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        assertIs<SafeTxUiState.Sent>(viewModel.uiState.value, "the first tap signs")
    }

    /** [viewModel] after a hide long enough to let the queue's reading go, back with the Safe's reading held. */
    private fun TestScope.backAfterALongHide(viewModel: SafeTxViewModel, screen: Job) {
        viewModel.onHidden()
        screen.cancel()
        advanceTimeBy(30.seconds)
        runCurrent()
        world.kindGate = CompletableDeferred()
        viewModel.onShown()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
    }

    @Test
    fun theSigningStepsSignTapAfterALongHideSignsWhileTheSafesReadingIsOnItsWay() = runTest {
        val viewModel = viewModel(queued(world.request(world.ether(4))), collect = false)
        val screen = backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        viewModel.startSigning()
        runCurrent()

        backAfterALongHide(viewModel, screen)
        assertIs<SafeTxUiState.Signing>(viewModel.uiState.value, "drawn from the transaction as last shown")
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        world.kindGate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf("made-up test password"), world.signer.passwords, "the tap was not dropped")
        assertEquals(listOf(world.alice.address), world.queue.requests.value.single().signers)
    }

    @Test
    fun theBiometricTapAfterALongHideSignsWhileTheSafesReadingIsOnItsWay() = runTest {
        biometrics = BiometricUnlock.On
        val viewModel = viewModel(queued(world.request(world.ether(4))), collect = false)
        val screen = backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        viewModel.startSigning()
        runCurrent()

        backAfterALongHide(viewModel, screen)
        viewModel.signWithBiometrics()
        runCurrent()
        world.kindGate!!.complete(Unit)
        runCurrent()

        assertIs<Unlock.WithBiometrics>(world.signer.asked.single().second, "the tap was not dropped")
    }

    @Test
    fun aReviewWhoseBroadcastNoPeerTookOutlivesAQueueThatCannotBeReadButNotItsNoncePassing() = runTest {
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(4))))
        world.broadcaster.answers += Broadcast.Unavailable("no peers")
        viewModel.startApproval()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        world.queue.unreadable.value = true
        runCurrent()
        assertTrue(viewModel.review.notSent!!.canRetry, "the bytes stay to be sent again")
        world.queue.unreadable.value = false
        runCurrent()
        viewModel.retry()
        runCurrent()
        assertIs<SafeTxUiState.Sent>(viewModel.uiState.value)
    }

    @Test
    fun aReviewWhoseBroadcastNoPeerTookEndsOnceTheSafesNonceHasPassedIt() = runTest {
        funded()
        val viewModel = viewModel(queued(world.request(world.ether(4))))
        world.broadcaster.answers += Broadcast.Unavailable("no peers")
        viewModel.startApproval()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        world.state = world.state.copy(nonce = 5)
        runCurrent()

        assertIs<SafeTxUiState.NotQueued>(viewModel.uiState.value)
    }
}
