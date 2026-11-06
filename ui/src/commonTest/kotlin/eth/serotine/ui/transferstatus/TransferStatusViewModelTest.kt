package eth.serotine.ui.transferstatus

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptLog
import eth.serotine.domain.repository.SafeListing
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveSentTransfer
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeBroadcaster
import eth.serotine.ui.FakeReceipts
import eth.serotine.ui.MemoryTransactionHistory
import eth.serotine.ui.MemorySentTransfers
import eth.serotine.ui.components.Eip55
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.LightClient
import eth.serotine.ui.MemoryOutbox
import eth.serotine.domain.usecase.OutboxLock
import eth.serotine.domain.usecase.ConfirmQueued
import eth.serotine.domain.usecase.DiscardQueued
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveOutbox
import eth.serotine.ui.outbox.conflictReason
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.QueueConflict
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.DraftBasis
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.domain.repository.Outbox

@OptIn(ExperimentalCoroutinesApi::class)
class TransferStatusViewModelTest {

    private val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc")
    private val record = SentTransfer(
        network = Network.SEPOLIA,
        account = Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        nonce = 7,
        signed = SignedTransfer(byteArrayOf(2, 1, 9)),
        action = SentAction.Transfer(
            to = Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"),
            asset = TransferAsset.Erc20Token(usdc),
            amount = UInt256.of(40_500_000),
        ),
        maxCost = UInt256.of(1_000_000),
        sentAt = 1_800_000_000,
    )
    private val sent = MemorySentTransfers()
    private val history = MemoryTransactionHistory()
    private val receipts = FakeReceipts()
    private val accounts = FakeAccounts()
    private val broadcaster = FakeBroadcaster()

    /** Lenient mode's outbox, empty unless a test queues something, and its one lock. */
    private val outbox = MemoryOutbox()
    private val lock = OutboxLock()

    /** The network's light client, catching up unless a test says otherwise. */
    private val clientStatus = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)
    private val light = object : LightClient {
        override val datesFinalizedBlocks = true
        override fun observeStatus(network: Network): Flow<LightClientStatus> = clientStatus
    }

    /** The networks on: Sepolia, as in every build. */
    private val networks = MemoryNetworkSettings()

    /** Now, in unix seconds, as a queued transfer's hour is dated. */
    private var wallClock = 1_800_000_600L

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(hash: String = record.hash): TransferStatusViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = TransferStatusViewModel(
            hash,
            ObserveSentTransfer(sent, receipts, history),
            ObserveAccounts(accounts),
            RebroadcastTransfer(broadcaster, sent, receipts) { 1_800_000_500 },
            Network.SEPOLIA,
            ObserveOutbox(racing),
            ObserveLightClient(light),
            ObserveEnabledNetworks(networks),
            DiscardQueued(racing, lock),
            ConfirmQueued(racing, lock, clock = { wallClock }),
            formatTime = { "at $it" },
            clock = { wallClock },
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    private val TransferStatusViewModel.shown get() = assertIs<TransferStatusUiState.Shown>(uiState.value)

    @Test
    fun whatWasSentAndTheStepsAsTheVerifiedReceiptMovesOn() = runTest {
        sent.transfers.value = listOf(record)
        val viewModel = viewModel()
        runCurrent()

        val pending = viewModel.shown
        assertEquals(
            SentSummary(
                amount = "40.5 USDC",
                symbol = "USDC",
                icon = "usdc",
                to = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
                from = "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2",
            ),
            pending.sent,
        )
        assertEquals(
            listOf(
                StatusStep("Signed", StepState.DONE, "On this device"),
                StatusStep(
                    "Broadcast",
                    StepState.DONE,
                    "Handed to the network's peers, at 1800000000",
                    simpleLabel = "Sent",
                    simpleDetail = "at 1800000000",
                ),
                StatusStep(
                    "Included",
                    StepState.WAITING,
                    "Waiting for a block to include it",
                    simpleLabel = "Confirmed",
                    simpleDetail = "Waiting to be confirmed",
                ),
                StatusStep(
                    "Finalized",
                    StepState.WAITING,
                    "Once the beacon chain finalizes its block",
                    simpleLabel = "Final",
                    simpleDetail = "Once the network finalizes it, its outcome can't be reversed",
                ),
            ),
            pending.steps,
        )
        assertNull(pending.note)

        receipts.of(record.hash).value = TransactionProgress.Included(9_412_120, succeeded = true)
        assertEquals(
            StatusStep(
                "Included",
                StepState.DONE,
                "In block 9,412,120, its receipt verified",
                simpleLabel = "Confirmed",
                simpleDetail = "Its receipt checked on chain",
            ),
            viewModel.shown.steps[2],
        )
        assertFalse(viewModel.shown.finished)

        receipts.of(record.hash).value = TransactionProgress.Finalized(9_412_120, succeeded = true)
        assertEquals(
            StatusStep(
                "Finalized",
                StepState.DONE,
                "Block 9,412,120 is finalized",
                simpleLabel = "Final",
                simpleDetail = "Its outcome can't be reversed by the network",
            ),
            viewModel.shown.steps[3],
        )
        assertTrue(viewModel.shown.finished)
    }

    @Test
    fun aTransferThatRevertedIsSaidToHaveFailed() = runTest {
        sent.transfers.value = listOf(record)
        val viewModel = viewModel()
        receipts.of(record.hash).value = TransactionProgress.Included(9_412_120, succeeded = false)
        runCurrent()

        assertEquals(
            StatusStep(
                "Included",
                StepState.FAILED,
                "In block 9,412,120, its receipt verified: it reverted",
                simpleLabel = "Failed",
                simpleDetail = "Its receipt checked on chain: it reverted",
            ),
            viewModel.shown.steps[2],
        )
        assertEquals(
            "It failed: the transfer reverted in its block, so nothing was moved. Its fee is paid all the same.",
            viewModel.shown.note,
        )
    }

    @Test
    fun anApprovalOrAnExecutionOfASafeTransactionSaysWhatItIsOfWhichSafeAndNonce() = runTest {
        val safe = Address.of("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb")
        val request = SigningRequest.of(
            Network.SEPOLIA,
            safe,
            SafeTx.call(Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"), UInt256.of(1), ByteArray(0), 12),
        )
        val approval =
            record.copy(signed = SignedTransfer(byteArrayOf(2, 1, 8)), action = SentAction.SafeApproval(request))
        val execution =
            record.copy(signed = SignedTransfer(byteArrayOf(2, 1, 7)), action = SentAction.SafeExecution(request))
        sent.transfers.value = listOf(approval, execution)

        val approved = viewModel(approval.hash)
        val executed = viewModel(execution.hash)
        receipts.of(execution.hash).value = TransactionProgress.Included(9_412_120, succeeded = false)
        runCurrent()

        assertNull(approved.shown.sent)
        assertEquals(
            SafeTxSummary(
                "Approval of a Safe transaction",
                "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
                "Safe nonce 12",
                "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2",
            ),
            approved.shown.safeTx,
        )
        assertEquals("Execution of a Safe transaction", executed.shown.safeTx?.title)
        assertEquals(
            "It failed: the transaction reverted in its block, so it did nothing. Its fee is paid all the same.",
            executed.shown.note,
        )
    }

    @Test
    fun aReceiptThatCannotBeVerifiedReadsAsSuchAndAMissingRecordStillShowsTheSteps() = runTest {
        val hash = "0x" + "ab".repeat(32)
        val viewModel = viewModel(hash)
        receipts.of(hash).value = TransactionProgress.Unavailable(ReadUnavailable.STALE_ANCHOR)
        runCurrent()

        val shown = viewModel.shown
        assertNull(shown.sent, "no record on this device")
        assertNull(shown.safeTx)
        assertEquals(StatusStep("Signed", StepState.DONE, null), shown.steps[0])
        assertEquals(listOf(StepState.UNKNOWN, StepState.UNKNOWN), shown.steps.drop(2).map { it.state })
        assertEquals("Where it stands cannot be verified right now: the light client refuses to sync.", shown.note)
        assertEquals(hash, shown.hash)
    }

    private val safe = Address.of("0x2ca0226086a7c24808217f7f8707827608b17dc5")
    private val deployment = record.copy(
        signed = SignedTransfer(byteArrayOf(2, 1, 10)),
        action = SentAction.SafeDeployment(
            safe = safe,
            owners = listOf(record.account, Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359")),
            threshold = 1,
            version = "1.4.1",
            label = "Family vault",
        ),
    )

    /** [deployment] with its listing ended as [listed]. */
    private fun ended(listed: SafeListing) = deployment.copy(
        action = (deployment.action as SentAction.SafeDeployment).copy(listed = listed),
    )

    @Test
    fun aSafesDeploymentShowsWhereItsListingStandsWithoutDrivingIt() = runTest {
        sent.transfers.value = listOf(deployment)
        val viewModel = viewModel(deployment.hash)
        runCurrent()

        val pending = assertNotNull(viewModel.shown.deployment)
        assertEquals("Deployment of a Safe", pending.title)
        assertEquals("0x2Ca0226086a7C24808217F7F8707827608B17Dc5", pending.safe)
        assertEquals("1/2", pending.signers)
        assertEquals("1 of 2 owners · Safe 1.4.1", pending.shape)
        assertEquals("0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2", pending.from)
        assertEquals(
            "Added to your wallet once a block has it and the light client verifies the Safe there, whether this " +
                "screen is open or not.",
            pending.listing,
        )
        assertNull(viewModel.shown.sent)

        receipts.of(deployment.hash).value = TransactionProgress.Included(9_412_120, succeeded = true)
        runCurrent()
        assertEquals(
            "Checking the new Safe with the light client before it is listed…",
            viewModel.shown.deployment?.listing,
        )
        assertEquals(emptyList(), accounts.accounts.value, "this screen lists nothing itself")

        // Listed by the app's follower: the record says so, the list has its name now.
        accounts.accounts.value = listOf(
        Account(Network.SEPOLIA, safe, AccountKind.SAFE, "Vault", AccountCustody.Watched),
    )
        sent.transfers.value = listOf(ended(SafeListing.LISTED))
        runCurrent()
        assertEquals("In your wallet as Vault.", viewModel.shown.deployment?.listing)
        assertFalse(viewModel.shown.deployment!!.listingFailed)
    }

    @Test
    fun aDeploymentThatRevertedWithNoSafeAtItsAddressSaysSo() = runTest {
        sent.transfers.value = listOf(deployment)
        val viewModel = viewModel(deployment.hash)
        receipts.of(deployment.hash).value = TransactionProgress.Included(9_412_120, succeeded = false)
        runCurrent()
        assertEquals("Checking whether a Safe is at its address all the same…", viewModel.shown.deployment?.listing)

        sent.transfers.value = listOf(ended(SafeListing.NO_SAFE))
        runCurrent()

        val shown = viewModel.shown
        assertEquals(
            "It failed: this transaction reverted in its block and created no Safe itself. Its fee is paid all " +
                "the same.",
            shown.note,
        )
        assertEquals(
            "This transaction created no Safe, and none is at its address: nothing is added to your wallet.",
            shown.deployment?.listing,
        )
        assertTrue(shown.deployment!!.listingFailed)
    }

    @Test
    fun aDeploymentThatRevertedBehindACopyOfItSaysTheCopyCreatedTheSafe() = runTest {
        sent.transfers.value = listOf(ended(SafeListing.LISTED))
        val viewModel = viewModel(deployment.hash)
        receipts.of(deployment.hash).value = TransactionProgress.Included(9_412_120, succeeded = false)
        runCurrent()

        assertEquals(
            "A Safe with these owners and threshold is at its address all the same: another sender's copy of " +
                "the same deployment created it. In your wallet as Family vault.",
            viewModel.shown.deployment?.listing,
        )
    }

    @Test
    fun somethingElseAtTheAddressIsSaid() = runTest {
        sent.transfers.value = listOf(ended(SafeListing.NOT_THE_SAFE))
        val viewModel = viewModel(deployment.hash)
        receipts.of(deployment.hash).value = TransactionProgress.Included(9_412_120, succeeded = true)
        runCurrent()

        assertTrue(viewModel.shown.deployment!!.listingFailed)
    }

    @Test
    fun aTransferMaybeSentIsRetriedWithItsOwnBytesFromItsStatus() = runTest {
        val maybe = record.copy(maybeSent = true)
        sent.transfers.value = listOf(maybe)
        broadcaster.answers += Broadcast.Unavailable(null)
        val viewModel = viewModel()
        runCurrent()

        assertEquals(StepState.UNCONFIRMED, viewModel.shown.steps[1].state, "no peer confirmed its broadcast")
        assertTrue(assertNotNull(viewModel.shown.notSent).canRetry)

        // No peer again: still maybe sent, from this broadcast on, and Try again stays.
        viewModel.retry()
        runCurrent()
        assertEquals(listOf(maybe.copy(sentAt = 1_800_000_500)), sent.transfers.value)
        assertTrue(assertNotNull(viewModel.shown.notSent).canRetry)

        // A peer takes it: an ordinary record, and nothing left to retry.
        viewModel.retry()
        runCurrent()
        assertEquals(2, broadcaster.sent.size)
        assertTrue(broadcaster.sent.all { it.contentEquals(record.signed.raw) }, "the very bytes, never a new one")
        assertFalse(sent.transfers.value.single().maybeSent)
        assertNull(viewModel.shown.notSent)
        assertEquals(StepState.DONE, viewModel.shown.steps[1].state)
    }

    @Test
    fun aRetryTurnedAwayKeepsItsRecordAndSaysThePeersWord() = runTest {
        val maybe = record.copy(maybeSent = true)
        sent.transfers.value = listOf(maybe)
        broadcaster.answers += Broadcast.Rejected("nonce too low")
        val viewModel = viewModel()
        runCurrent()

        viewModel.retry()
        runCurrent()

        // "Nonce too low" may be a block past the light client's head: nothing sure of the first.
        assertEquals(listOf(maybe), sent.transfers.value, "kept as it was")
        val shown = viewModel.shown
        val notSent = assertNotNull(shown.notSent)
        assertTrue(notSent.canRetry)
        assertTrue(notSent.message.startsWith("Turned away this time (the peer said: nonce too low)."))
        assertEquals(StepState.UNCONFIRMED, shown.steps[1].state, "never shown as handed to the peers")
        assertNotNull(shown.sent, "what was sent stays")
    }

    @Test
    fun aTransferMinedIsNotRetriedEvenWhenItsBroadcastFoundNoPeer() = runTest {
        sent.transfers.value = listOf(record.copy(maybeSent = true))
        val viewModel = viewModel()
        receipts.of(record.hash).value = TransactionProgress.Included(9_412_120, succeeded = true)
        runCurrent()

        assertNull(viewModel.shown.notSent)
        viewModel.retry()
        runCurrent()
        assertEquals(emptyList(), broadcaster.sent)
    }

    @Test
    fun aCowOrdersCancellationMaybeSentIsCalledOne() = runTest {
        val settlement = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41")
        val uid = CowOrderUid.of("0x" + "ab".repeat(32) + record.account.hex.removePrefix("0x") + "6ad9e7f4")
        val cancellation = record.copy(
            signed = SignedTransfer(byteArrayOf(2, 1, 9)),
            action = SentAction.CowCancellation(settlement, uid),
            maybeSent = true,
        )
        sent.transfers.value = listOf(cancellation)
        val viewModel = viewModel(cancellation.hash)
        runCurrent()

        val notSent = assertNotNull(viewModel.shown.notSent)
        assertTrue("No peer answered when this cancellation was broadcast" in notSent.message, notSent.message)
        assertEquals(
            CancellationSummary(
                title = "Cancellation of a CoW order on chain",
                order = uid.hex,
                settlement = "0x9008D19f58AAbD9eD0D60971565AA8510560ab41",
                from = Eip55.format(record.account),
            ),
            viewModel.shown.cancellation,
        )
        assertNull(viewModel.shown.sent)
    }

    @Test
    fun aSwapShowsWhatItSoldAndTheLeastItBuysAndWhyItMayFail() = runTest {
        val router = Address.of("0x3bfa4769fb09eefc5a80d6e87c3b9c650f7ae48e")
        val link = Token(Address.of("0x779877a7b0d9e8603169ddbd7836e478b4624789"), "LINK", "ChainLink", 18, "link")
        val swap = record.copy(
            signed = SignedTransfer(byteArrayOf(2, 1, 6)),
            action = SentAction.Swap(
                router = router,
                sell = TransferAsset.Erc20Token(usdc),
                amountIn = UInt256.of(100_000_000),
                buy = TransferAsset.Erc20Token(link),
                minimumOut = UInt256.of("4288450000000000000"),
            ),
        )
        sent.transfers.value = listOf(swap)
        val viewModel = viewModel(swap.hash)
        runCurrent()

        assertEquals(
            SwapSummary(
                title = "Swap of 100 USDC for at least 4.28845 LINK on Uniswap",
                sold = "100 USDC",
                soldSymbol = "USDC",
                soldIcon = "usdc",
                bought = "at least 4.28845 LINK",
                boughtSymbol = "LINK",
                boughtIcon = "link",
                router = "0x3bFA4769FB09eefC5a80d6E87c3B9C650f7Ae48E",
                from = "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2",
            ),
            viewModel.shown.swap,
        )
        assertNull(viewModel.shown.sent)

        receipts.of(swap.hash).value = TransactionProgress.Included(9_412_120, succeeded = false)
        assertEquals(
            "It failed: the swap reverted in its block, so nothing was swapped: the price moved past its minimum, " +
                "or its deadline passed. Its fee is paid all the same.",
            viewModel.shown.note,
        )
        assertNull(viewModel.shown.swap?.received, "a reverted swap paid nothing")

        // Went through: LINK's own Transfer log to the account says what it paid.
        val paid = ReceiptLog(
            link.address,
            listOf(
                "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                "0x" + "00".repeat(32),
                "0x" + "00".repeat(12) + record.account.hex.drop(2),
            ),
            "0x" + UInt256.of("4309120000000000000").toBytes32().joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            },
        )
        receipts.of(swap.hash).value = TransactionProgress.Included(9_412_120, succeeded = true, logs = listOf(paid))
        val received = viewModel.shown.swap
        assertEquals("Swap of 100 USDC for 4.30912 LINK on Uniswap", received?.title)
        assertEquals("4.30912 LINK", received?.bought)
        assertEquals(
            "Received, as the verified receipt's logs say; it was signed for at least 4.28845 LINK.",
            received?.received,
        )
    }

    @Test
    fun anApprovalShowsTheAmountAndTheSpenderItLetsTakeIt() = runTest {
        val approval = record.copy(
            signed = SignedTransfer(byteArrayOf(2, 1, 5)),
            action = SentAction.TokenApproval(
                usdc,
                Address.of("0x3bfa4769fb09eefc5a80d6e87c3b9c650f7ae48e"),
                UInt256.of(100_000_000),
            ),
        )
        sent.transfers.value = listOf(approval)
        val viewModel = viewModel(approval.hash)
        runCurrent()

        assertEquals(
            ApprovalSummary(
                title = "Approval for a swap",
                amount = "100 USDC",
                symbol = "USDC",
                icon = "usdc",
                spenderLabel = "May be taken by the swap's spender",
                spender = "0x3bFA4769FB09eefC5a80d6E87c3B9C650f7Ae48E",
                from = "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2",
            ),
            viewModel.shown.approval,
        )
        assertNull(viewModel.shown.swap)
    }

    @Test
    fun aTransactionTheRecordForgotIsShownFromTheHistory() = runTest {
        history.entries.value = listOf(HistoryEntry(record, SentOutcome.Included(9_412_118, succeeded = true)))
        val viewModel = viewModel()
        runCurrent()

        val shown = viewModel.shown
        assertEquals("40.5 USDC", shown.sent?.amount)
        assertEquals(StepState.DONE, shown.steps[2].state, "the block the history saw, its receipt no longer found")
        assertEquals("In block 9,412,118, its receipt verified", shown.steps[2].detail)
    }

    @Test
    fun aNonceUsedWhileNotWatchingIsSaidSoAndNotRetried() = runTest {
        val maybe = record.copy(maybeSent = true)
        sent.transfers.value = listOf(maybe)
        history.entries.value = listOf(HistoryEntry(maybe, SentOutcome.NonceUsed))
        val viewModel = viewModel()
        runCurrent()

        val shown = viewModel.shown
        assertEquals(StepState.UNCONFIRMED, shown.steps[1].state, "still no peer's word that it was taken")
        assertEquals("No peer confirmed it, at 1800000000", shown.steps[1].detail)
        assertEquals(StepState.UNKNOWN, shown.steps[2].state)
        assertEquals(StepState.UNKNOWN, shown.steps[3].state)
        assertNull(shown.notSent, "its nonce is used: there is nothing to retry")

        // Not verifiable right now (the light client still syncing): the used nonce is still said.
        receipts.of(maybe.hash).value = TransactionProgress.Unavailable(ReadUnavailable.UNPROVEN)
        assertTrue(viewModel.shown.steps[2].detail.orEmpty().startsWith("Its nonce was used on chain"))
        assertNull(viewModel.shown.notSent)
        viewModel.retry()
        runCurrent()
        assertEquals(emptyList(), broadcaster.sent)
    }

    // A transfer queued in lenient mode (docs/lenient-mode.md, *The outbox*; owner rulings, 2026-11-06).

    private val queuedBytes = SignedTransfer(byteArrayOf(2, 7, 7))

    /** 40.5 USDC to Savings, signed at [signedAt] and queued under the hash of its bytes. */
    private fun queued(signedAt: Long = wallClock - 10 * 60, conflict: QueueConflict? = null) = QueuedTransaction(
        network = Network.SEPOLIA,
        account = record.account,
        id = queuedBytes.hash,
        action = record.action,
        level = FeeLevel.FAST,
        transaction = Eip1559Transaction(
            chainId = Network.SEPOLIA.chainId,
            nonce = 7,
            maxPriorityFeePerGas = UInt256.of(1),
            maxFeePerGas = UInt256.of(21),
            gasLimit = 100_000,
            to = usdc.address,
            value = UInt256.ZERO,
        ),
        signed = queuedBytes.takeIf { conflict == null },
        maxCost = UInt256.of(2_100_000),
        signedAt = signedAt,
        builtOn = DraftBasis(9_412_118, wallClock - 900, 9_412_117, wallClock - 900, 9_412_110, null, 9_412_100, null),
        conflict = conflict,
    )

    private val TransferStatusViewModel.queuedState get() = assertIs<TransferStatusUiState.Queued>(uiState.value)

    @Test
    fun aQueuedTransferHasAStepAfterSignedThatWaitsForTheNetwork() = runTest {
        outbox.list.value = OutboxList.Loaded(listOf(queued()))
        val viewModel = viewModel(hash = queuedBytes.hash)
        runCurrent()

        val state = viewModel.queuedState
        assertEquals("40.5 USDC", state.sent?.amount)
        assertEquals(listOf("Signed", "Queued", "Broadcast"), state.steps.map { it.label })
        assertEquals(
            "Waits for Sepolia to catch up. Keep Serotine open to send it sooner.",
            state.steps[1].detail,
        )
        assertEquals("Edit", state.againLabel)
        assertNull(state.question)
        assertNull(state.busy)

        clientStatus.value =
            LightClientStatus.Synced(headBlock = 9_412_200, headTimestamp = wallClock, finalized = null)
        runCurrent()
        assertEquals(
            "Sepolia has caught up: it's checked on chain, and sent once it checks out.",
            viewModel.queuedState.steps[1].detail,
        )
    }

    @Test
    fun pastItsHourItAsksStillSendThisAndSendNowConfirmsIt() = runTest {
        outbox.list.value = OutboxList.Loaded(listOf(queued(signedAt = wallClock - 2 * 60 * 60)))
        val viewModel = viewModel(hash = queuedBytes.hash)
        runCurrent()

        assertEquals("Still send this? You signed it 2 h ago.", viewModel.queuedState.question)
        viewModel.confirm()
        runCurrent()

        assertEquals(wallClock, outbox.queued.single().confirmedAt, "its hour starts again at the tap")
        assertNull(viewModel.queuedState.question)
    }

    @Test
    fun aConflictIsSaidInTheNotificationsWordsWithSignAgainAndDiscard() = runTest {
        val conflict = QueueConflict.NonceUsed(signed = 7, fresh = 8)
        outbox.list.value = OutboxList.Loaded(listOf(queued(conflict = conflict)))
        val viewModel = viewModel(hash = queuedBytes.hash)
        runCurrent()

        val state = viewModel.queuedState
        val step = state.steps[1]
        assertEquals("Needs your signature again", step.label)
        assertEquals(StepState.FAILED, step.state)
        assertTrue(step.detail!!.startsWith(conflictReason(conflict, queued(conflict = conflict))), step.detail)
        assertEquals("Sign again", state.againLabel)
        assertEquals(
            SendAgain(Network.SEPOLIA.chainId, record.account.hex, usdc.address.hex, record.action.let {
                (it as SentAction.Transfer).to.hex
            }, "40.5", FeeLevel.FAST),
            state.again,
        )
    }

    @Test
    fun signAgainDiscardsItThenOpensSendFilledIn() = runTest {
        outbox.list.value = OutboxList.Loaded(listOf(queued(conflict = QueueConflict.InsufficientFunds)))
        val viewModel = viewModel(hash = queuedBytes.hash)
        runCurrent()
        val again = viewModel.queuedState.again

        viewModel.signAgain()
        runCurrent()

        assertTrue(outbox.queued.isEmpty(), "discarded first")
        val discarded = assertIs<TransferStatusUiState.Discarded>(viewModel.uiState.value)
        assertEquals(again, discarded.reopen)
    }

    @Test
    fun discardDeletesItAndSaysNothingWasSent() = runTest {
        outbox.list.value = OutboxList.Loaded(listOf(queued()))
        val viewModel = viewModel(hash = queuedBytes.hash)
        runCurrent()

        viewModel.discard()
        runCurrent()

        assertEquals(listOf(record.accountId), outbox.removed)
        assertEquals(
            TransferStatusUiState.Discarded("Sepolia", "Discarded: its signature was deleted, and nothing was sent."),
            viewModel.uiState.value,
        )
    }

    @Test
    fun aDiscardThatCannotBeSavedLeavesItQueuedAndSaysSo() = runTest {
        outbox.list.value = OutboxList.Loaded(listOf(queued()))
        val viewModel = viewModel(hash = queuedBytes.hash)
        runCurrent()
        outbox.failSaves = true

        viewModel.discard()
        runCurrent()

        assertEquals("The outbox could not be changed, so it is still queued. Try again.", viewModel.queuedState.error)
    }

    @Test
    fun whileItIsBeingSentDiscardAndEditWaitAndSayWhy() = runTest {
        outbox.list.value = OutboxList.Loaded(listOf(queued().copy(sending = true)))
        val viewModel = viewModel(hash = queuedBytes.hash)
        runCurrent()

        val state = viewModel.queuedState
        assertEquals("Sending…", state.steps[1].label)
        assertEquals("It's being checked and sent.", state.busy)
        viewModel.discard()
        runCurrent()
        assertTrue(outbox.removed.isEmpty(), "a check and its broadcast are not cut in half")
    }

    @Test
    fun onceSentTheSameIdIsShownAsTheSentTransfer() = runTest {
        outbox.list.value = OutboxList.Loaded(listOf(queued()))
        val viewModel = viewModel(hash = queuedBytes.hash)
        runCurrent()
        // The follower sent it: recorded under the same hash, then gone from the outbox.
        sent.transfers.value = listOf(record.copy(signed = queuedBytes))
        outbox.list.value = OutboxList.Loaded(emptyList())
        runCurrent()

        val shown = assertIs<TransferStatusUiState.Shown>(viewModel.uiState.value)
        assertEquals("40.5 USDC", shown.sent?.amount)
        assertEquals(listOf("Signed", "Broadcast", "Included", "Finalized"), shown.steps.map { it.label })
    }

    @Test
    fun anEditThatCameAfterThePeerTookItShowsItSentAndOpensNoSend() = runTest {
        outbox.list.value = OutboxList.Loaded(listOf(queued()))
        val viewModel = viewModel(hash = queuedBytes.hash)
        runCurrent()
        // The follower held the lock: it sent it, recorded it and let it go before the discard could look.
        racing.beforeDiscard = {
            sent.transfers.value = listOf(record.copy(signed = queuedBytes))
            outbox.list.value = OutboxList.Loaded(emptyList())
        }

        viewModel.signAgain()
        runCurrent()

        val shown = assertIs<TransferStatusUiState.Shown>(viewModel.uiState.value)
        assertEquals("It was sent before it could be discarded.", shown.note)
        assertTrue(outbox.removed.isEmpty(), "nothing of the account's was deleted")
    }

    @Test
    fun aDiscardWhileItsBroadcastRunsIsRefusedAndNeverToldAsDiscarded() = runTest {
        outbox.list.value = OutboxList.Loaded(listOf(queued()))
        val viewModel = viewModel(hash = queuedBytes.hash)
        runCurrent()
        // The check passed while the discard waited: its broadcast is under way, and may reach the network.
        racing.beforeDiscard = { outbox.list.value = OutboxList.Loaded(listOf(queued().copy(sending = true))) }

        viewModel.discard()
        runCurrent()

        val state = viewModel.queuedState
        assertEquals("Sending…", state.steps[1].label)
        assertEquals(
            "It's being checked and sent, so it was not discarded: it may reach the network. Its status follows.",
            state.error,
        )
        assertEquals(1, outbox.queued.size, "left for the outbox to record as sent, or maybe sent")
    }

    @Test
    fun aQueuedTransferOnANetworkThatIsOffSaysHowToGetItSent() = runTest {
        networks.on.value = emptySet()
        outbox.list.value = OutboxList.Loaded(listOf(queued()))
        val viewModel = viewModel(hash = queuedBytes.hash)
        runCurrent()

        assertEquals(
            "Sepolia is off, so nothing checks or sends it: turn Sepolia on in Settings to send it.",
            viewModel.queuedState.steps[1].detail,
        )
    }

    /**
     * The outbox, with a moment a test can use: [beforeDiscard] runs as a discard reads it, under the
     * outbox's lock, as what the follower did while the discard waited for that lock.
     */
    private inner class RacingOutbox : Outbox by outbox {
        var beforeDiscard: (() -> Unit)? = null
        private var reads = 0

        override fun observe(): Flow<OutboxList> {
            // The first read is the screen's own, from the start; a later one is a discard's.
            if (reads++ > 0) beforeDiscard?.invoke()?.also { beforeDiscard = null }
            return outbox.observe()
        }
    }

    private val racing = RacingOutbox()
}
