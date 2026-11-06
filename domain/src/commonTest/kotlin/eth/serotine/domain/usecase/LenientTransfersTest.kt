package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeQuote
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.KeptFees
import eth.serotine.domain.repository.KeptNonce
import eth.serotine.domain.repository.KeptTransactionInputs
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.OutboxNotifier
import eth.serotine.domain.repository.QueueConflict
import eth.serotine.domain.repository.QueueRefusal
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.repository.prunable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

/** Lenient mode's drafts, the outbox's check and its follower (docs/lenient-mode.md). */
class LenientTransfersTest {

    private val alice = Address.of("0x" + "aa".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))
    private val usdc = Token(Address.of("0x" + "cc".repeat(20)), "USDC", "USD Coin", 6, icon = null)
    private val gwei = 1_000_000_000L
    private val eth = 1_000_000_000_000_000_000L
    private val network = Network.SEPOLIA

    // What the light client verified last: a balance of 1 ETH an hour ago, the count 7, a base fee of 10 gwei.
    private var native: BalanceReading = BalanceReading.LastKnown(Wei.of(eth.toString()), 90, asOf = 1_800_000_000)
    private var token: TokenBalanceReading = TokenBalanceReading.LastKnown(TokenUnits.of("5000000"), 90, 1_800_000_000)
    private val kept = FakeKept()
    private val sent = FakeSentTransfers()
    private val outbox = ListOutbox()
    private val orders = MemoryCowOrders()
    private val cowChain = FakeCowChain()
    private var now = 1_800_000_100L

    private val balances = object : NativeBalances {
        override fun observeBalance(network: Network, address: Address): Flow<BalanceReading> = flowOf(native)
    }
    private val tokens = object : TokenBalances {
        override fun observeBalance(network: Network, holder: Address, token: Address): Flow<TokenBalanceReading> =
            flowOf(this@LenientTransfersTest.token)
    }
    private val draft = DraftTransfer(kept, balances, tokens, sent, orders, cowChain, outbox, network) { now }
    private val signature = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), recoveryId = 0)
    private val unlock = Unlock.WithPassword(WalletPassword("correct horse battery"))
    private val queueLock = OutboxLock()

    // The check's fresh state: the head verifies, the same balance, count and fees.
    private val inputs = FakeInputs()
    private val light = FakeLightClient().also { it.status.value = LightClientStatus.Synced(100, 1_800_000_090, null) }
    private val quotes = QuoteTransfer(inputs, freshBalances(), freshTokens(), sent, orders, cowChain, light, network, outbox) { now }
    private val check = CheckQueued { quotes }

    private var freshNative = BalanceReading.Verified(Wei.of(eth.toString()), 100) as BalanceReading
    private fun freshBalances() = object : NativeBalances {
        override fun observeBalance(network: Network, address: Address): Flow<BalanceReading> = flowOf(freshNative)
    }
    private var freshToken: TokenBalanceReading = TokenBalanceReading.Verified(TokenUnits.of("5000000"), 100)
    private fun freshTokens() = object : TokenBalances {
        override fun observeBalance(network: Network, holder: Address, token: Address): Flow<TokenBalanceReading> =
            flowOf(freshToken)
    }

    @Test
    fun aDraftIsBuiltOnWhatWasVerifiedLastWithThePinnedGasCeilingAndItsAges() = runTest {
        val drafting = draft(alice, bob, TransferAsset.Native, UInt256.of(eth / 10), FeeLevel.NORMAL)

        val drafted = assertIs<TransferDrafting.Drafted>(drafting).draft
        val tx = drafted.transaction
        assertEquals(7, tx.nonce, "past the kept count")
        assertEquals(DraftGas.NATIVE, tx.gasLimit, "the pinned ceiling, not an estimate")
        assertEquals(UInt256.of(22 * gwei), tx.maxFeePerGas, "twice the kept base fee plus the tip")
        assertEquals(UInt256.of(2 * gwei), tx.maxPriorityFeePerGas)
        assertEquals(bob, tx.to)
        assertEquals(UInt256.of(22 * gwei * DraftGas.NATIVE), drafted.maxFee)
        assertEquals(1_800_000_000, drafted.builtOn.oldestAsOf)
        assertEquals(90, drafted.builtOn.balanceBlock)
        assertEquals(80, drafted.builtOn.nonceBlock)
    }

    @Test
    fun aDraftWaitsForWhatWasNeverVerifiedHere() = runTest {
        kept.nonce = null
        assertEquals(TransferDrafting.Waits(DraftInput.NONCE), draftTenth())
        kept.nonce = KeptNonce(7, 80, 1_800_000_000)
        kept.fees = null
        assertEquals(TransferDrafting.Waits(DraftInput.FEES), draftTenth())
        kept.fees = keptFees()
        native = BalanceReading.Syncing
        assertEquals(TransferDrafting.Waits(DraftInput.BALANCE), draftTenth())
    }

    @Test
    fun aDraftLeavesRoomForTheMostItsFeeCanBeAndForTransactionsNotMinedYet() = runTest {
        val maxFee = 22 * gwei * DraftGas.NATIVE
        // Exactly the balance less the most the fee can be: drafted.
        assertIs<TransferDrafting.Drafted>(draft(alice, bob, TransferAsset.Native, UInt256.of(eth - maxFee), FeeLevel.NORMAL))
        // One wei more: refused, as a peer would refuse the signed bytes.
        assertEquals(
            TransferDrafting.Refused(TransferQuoting.InsufficientFunds),
            draft(alice, bob, TransferAsset.Native, UInt256.of(eth - maxFee + 1), FeeLevel.NORMAL),
        )

        // An earlier transfer not mined yet takes the next nonce and what it may cost.
        sent.record(sentTransfer(nonce = 7, maxCost = UInt256.of(eth / 2)))
        val drafted = assertIs<TransferDrafting.Drafted>(draftTenth()).draft
        assertEquals(8, drafted.transaction.nonce)
        assertEquals(UInt256.of(eth / 2), drafted.pendingCost)
    }

    @Test
    fun aDraftIsRefusedWhileTheAccountHasSomethingQueuedOrAnEarlierOneMaybeSent() = runTest {
        sent.record(sentTransfer(nonce = 7, maxCost = UInt256.ZERO).copy(maybeSent = true))
        assertIs<TransferDrafting.Refused>(draftTenth()).also { assertIs<TransferQuoting.EarlierMaybeSent>(it.quoting) }

        sent.transfers.value = emptyList()
        val queued = queuedTenth()
        assertEquals(TransferDrafting.AccountBusy(queued.id), draftTenth())
    }

    @Test
    fun aTokenDraftNeedsTheTokenAndTheFeeInTheNetworksCurrency() = runTest {
        val drafted = assertIs<TransferDrafting.Drafted>(
            draft(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.of(5_000_000), FeeLevel.NORMAL),
        ).draft
        assertEquals(DraftGas.TOKEN, drafted.transaction.gasLimit)
        assertEquals(usdc.address, drafted.transaction.to)
        assertEquals(UInt256.ZERO, drafted.transaction.value)
        assertEquals(
            TransferDrafting.Refused(TransferQuoting.InsufficientToken),
            draft(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.of(5_000_001), FeeLevel.NORMAL),
        )
    }

    @Test
    fun queueingSignsTheDraftAndPutsItInTheOutboxSendingNothing() = runTest {
        val drafted = assertIs<TransferDrafting.Drafted>(draftTenth()).draft
        val signer = FakeSigner(Signing.Signed(signature))
        val queuing = QueueTransfer(signer, outbox, queueLock) { now }(drafted, unlock)

        val queued = assertIs<TransferQueuing.Queued>(queuing).queued
        assertEquals(drafted.transaction.signingDigest(), signer.asked.single().second)
        assertEquals(listOf(queued), outbox.queued.value)
        assertEquals(now, queued.signedAt)
        assertEquals(queued.signed?.hash, queued.id)
        assertEquals(SentAction.Transfer(bob, TransferAsset.Native, UInt256.of(eth / 10)), queued.action)

        // A second one from the same account is refused before anything is signed.
        assertEquals(TransferQueuing.AccountBusy(queued.id), QueueTransfer(signer, outbox, queueLock) { now }(drafted, unlock))
        assertEquals(1, signer.asked.size)
    }

    @Test
    fun aNewQuoteFromAnAccountWithSomethingQueuedIsHeldBackInEitherMode() = runTest {
        val queued = queuedTenth()
        assertEquals(
            TransferQuoting.EarlierMaybeSent(queued.id, queued = true),
            quotes(alice, bob, TransferAsset.Native, UInt256.of(1), FeeLevel.NORMAL),
        )
    }

    @Test
    fun theCheckPassesOnUnchangedStateAndUsesTheSignedFiguresForTheFunds() = runTest {
        // Drafted at Max: the balance less the most the signed fee can be.
        val maxFee = 22 * gwei * DraftGas.NATIVE
        val queued = queued(UInt256.of(eth - maxFee))
        assertEquals(QueuedCheck.Passes, check(queued))

        // Fees rose within the headroom: the fresh cap (2 × 10.5 + 2 gwei) times the fresh estimate
        // would not refuse, and the signed cap still covers 81/64 of 10.5 gwei plus the tip.
        inputs.fees = FeeReading.Quoted(feeQuote(base = 10_500_000_000L, block = 100))
        assertEquals(QueuedCheck.Passes, check(queued))
    }

    @Test
    fun eachRowOfTheCheckIsAConflictOfItsOwn() = runTest {
        val queued = queuedTenth()

        // Another transaction took nonce 7.
        inputs.pending = 8
        assertEquals(QueuedCheck.Conflicts(QueueConflict.NonceUsed(7, 8)), check(queued))
        // The count went back (a reorg, another wallet's replaced transaction): 7 would wait behind 6.
        inputs.pending = 6
        inputs.mined = 6
        assertEquals(QueuedCheck.Conflicts(QueueConflict.NonceGap(7, 6)), check(queued))
        inputs.pending = 7
        inputs.mined = 7

        inputs.gas = DraftGas.NATIVE + 1
        assertEquals(QueuedCheck.Conflicts(QueueConflict.NeedsMoreGas(DraftGas.NATIVE, DraftGas.NATIVE + 1)), check(queued))
        inputs.gas = 21_000

        // The signed cap of 22 gwei no longer covers 81/64 of 16 gwei plus 2.
        inputs.fees = FeeReading.Quoted(feeQuote(base = 16 * gwei, block = 100))
        assertIs<QueueConflict.FeesRose>(assertIs<QueuedCheck.Conflicts>(check(queued)).conflict)
        inputs.fees = FeeReading.Quoted(feeQuote(base = 10 * gwei, block = 100))

        // The balance fell below the signed upfront cost.
        freshNative = BalanceReading.Verified(Wei.of((eth / 10).toString()), 100)
        assertEquals(QueuedCheck.Conflicts(QueueConflict.InsufficientFunds), check(queued))
        freshNative = BalanceReading.Verified(Wei.of(eth.toString()), 100)

        inputs.simulation = Simulation.Reverted(100)
        assertEquals(QueuedCheck.Conflicts(QueueConflict.Refused(QueueRefusal.REVERTS)), check(queued))
    }

    @Test
    fun theCheckWaitsWhileItCannotBeVerified() = runTest {
        val queued = queuedTenth()
        freshNative = BalanceReading.Syncing
        assertEquals(QueuedCheck.Waits(TransferQuoting.Syncing), check(queued))
    }

    @Test
    fun aTokenTransferWhoseTokenBalanceFellIsAConflictInStrictWords() = runTest {
        val drafted = assertIs<TransferDrafting.Drafted>(
            draft(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.of(5_000_000), FeeLevel.NORMAL),
        ).draft
        val queued = assertIs<TransferQueuing.Queued>(QueueTransfer(FakeSigner(Signing.Signed(signature)), outbox, queueLock) { now }(drafted, unlock)).queued
        freshToken = TokenBalanceReading.Verified(TokenUnits.of("4000000"), 100)

        assertEquals(QueuedCheck.Conflicts(QueueConflict.Refused(QueueRefusal.INSUFFICIENT_TOKEN)), check(queued))
    }

    @Test
    fun theOutboxSendsWhatChecksOutRecordsItAndForgetsItsQueuedCopy() = runTest {
        val queued = queuedTenth()
        val broadcaster = FakeBroadcaster(Broadcast.Sent(queued.hash!!))
        val notifier = RecordingNotifier()
        follower(broadcaster, notifier).round()

        assertEquals(1, broadcaster.sent.size)
        assertEquals(emptyList(), outbox.queued.value)
        val record = (sent.observe().first() as SentTransferList.Loaded).transfers.single()
        assertEquals(queued.hash, record.hash)
        assertEquals(queued.action, record.action)
        assertEquals(listOf("sent ${queued.hash}"), notifier.told)
    }

    @Test
    fun aConflictDeletesTheSignedBytesKeepsTheRestAndIsToldOnce() = runTest {
        val queued = queuedTenth()
        inputs.pending = 8
        val broadcaster = FakeBroadcaster(Broadcast.Sent("0x"))
        val notifier = RecordingNotifier()
        val follow = follower(broadcaster, notifier)
        follow.round()
        follow.round()

        assertEquals(0, broadcaster.sent.size)
        val left = outbox.queued.value.single()
        assertNull(left.signed)
        assertEquals(queued.id, left.id)
        assertEquals(QueueConflict.NonceUsed(7, 8), left.conflict)
        assertEquals(listOf("conflict"), notifier.told)
    }

    @Test
    fun aRejectedBroadcastIsAConflictAndRecordsNothing() = runTest {
        queuedTenth()
        val broadcaster = FakeBroadcaster(Broadcast.Rejected("nonce too low"))
        follower(broadcaster, RecordingNotifier()).round()

        assertEquals(QueueConflict.Rejected("nonce too low"), outbox.queued.value.single().conflict)
        assertEquals(emptyList(), (sent.observe().first() as SentTransferList.Loaded).transfers)
    }

    @Test
    fun pastItsHourItWaitsForTheUsersTapAndNothingIsSentWhileTheLightClientSyncs() = runTest {
        val queued = queuedTenth()
        val broadcaster = FakeBroadcaster(Broadcast.Sent(queued.hash!!))
        val lock = OutboxLock()
        val follow = follower(broadcaster, RecordingNotifier(), lock)

        light.status.value = LightClientStatus.CatchingUp(1, 3)
        follow.round()
        assertEquals(0, broadcaster.sent.size)

        light.status.value = LightClientStatus.Synced(100, 1_800_000_090, null)
        now += QUEUED_UNATTENDED_SECONDS + 1
        follow.round()
        assertEquals(0, broadcaster.sent.size, "past its hour without a tap")

        assertTrue(ConfirmQueued(outbox, lock) { now }(queued.accountId))
        follow.round()
        assertEquals(1, broadcaster.sent.size)
    }

    @Test
    fun discardingForgetsItSignedBytesAndAll() = runTest {
        val queued = queuedTenth()
        assertTrue(DiscardQueued(outbox, OutboxLock())(queued.accountId))
        assertEquals(emptyList(), outbox.queued.value)
    }

    @Test
    fun aBroadcastNoPeerAnsweredIsRecordedAsMaybeSentAndLeavesTheOutbox() = runTest {
        val queued = queuedTenth()
        follower(FakeBroadcaster(Broadcast.Unavailable(null)), RecordingNotifier()).round()

        assertEquals(emptyList(), outbox.queued.value)
        val record = (sent.observe().first() as SentTransferList.Loaded).transfers.single()
        assertEquals(queued.hash, record.hash)
        assertTrue(record.maybeSent)
    }

    @Test
    fun oneFoundStillMarkedAsSendingIsRecordedAsMaybeSentNeverCheckedOrSentAgain() = runTest {
        val queued = queuedTenth()
        outbox.put(queued.copy(sending = true))
        // Its own nonce reads as used now: it was broadcast before the process ended.
        inputs.pending = 8
        val broadcaster = FakeBroadcaster(Broadcast.Sent(queued.hash!!))
        val notifier = RecordingNotifier()
        follower(broadcaster, notifier).round()

        assertEquals(0, broadcaster.sent.size, "never broadcast again by itself")
        assertEquals(emptyList(), outbox.queued.value)
        val record = (sent.observe().first() as SentTransferList.Loaded).transfers.single()
        assertEquals(queued.hash, record.hash)
        assertTrue(record.maybeSent, "its status offers to try the same bytes again")
        assertEquals(listOf("sent ${queued.hash}"), notifier.told, "never told as a conflict")
    }

    @Test
    fun aSendWhoseRecordCannotBeSavedStaysMarkedAndIsNeverTakenForAConflict() = runTest {
        val queued = queuedTenth()
        val broadcaster = FakeBroadcaster(Broadcast.Sent(queued.hash!!))
        val follow = follower(broadcaster, RecordingNotifier())
        sent.failRecords = true
        follow.round()
        assertEquals(1, broadcaster.sent.size)
        assertTrue(outbox.queued.value.single().sending)

        sent.failRecords = false
        inputs.pending = 8
        follow.round()
        assertEquals(1, broadcaster.sent.size)
        assertEquals(emptyList(), outbox.queued.value)
        assertEquals(queued.hash, (sent.observe().first() as SentTransferList.Loaded).transfers.single().hash)
    }

    @Test
    fun aConflictedEntryStillHoldsTheAccount() = runTest {
        val queued = queuedTenth()
        inputs.pending = 8
        follower(FakeBroadcaster(Broadcast.Sent("0x")), RecordingNotifier()).round()
        assertNull(outbox.queued.value.single().signed)
        inputs.pending = 7

        assertEquals(TransferDrafting.AccountBusy(queued.id), draftTenth())
        assertEquals(
            TransferQuoting.EarlierMaybeSent(queued.id, queued = true),
            quotes(alice, bob, TransferAsset.Native, UInt256.of(1), FeeLevel.NORMAL),
        )
    }

    @Test
    fun theSignedCapMustCoverExactlyEightyOneSixtyFourthsOfTheNextBaseFeePlusTheTip() = runTest {
        val queued = queuedTenth()
        // The signed cap is 22 gwei and the tip 2: the base fee may reach 20 gwei × 64/81.
        inputs.fees = FeeReading.Quoted(feeQuote(base = 15_802_469_135L, block = 100))
        assertEquals(QueuedCheck.Passes, check(queued))
        inputs.fees = FeeReading.Quoted(feeQuote(base = 15_802_469_136L, block = 100))
        assertIs<QueueConflict.FeesRose>(assertIs<QueuedCheck.Conflicts>(check(queued)).conflict)
    }

    @Test
    fun aTapGivesAnHourOfItsOwnAndThenAsksAgain() = runTest {
        val queued = queuedTenth()
        val broadcaster = FakeBroadcaster(Broadcast.Sent(queued.hash!!))
        val lock = OutboxLock()
        val follow = follower(broadcaster, RecordingNotifier(), lock)
        light.status.value = LightClientStatus.CatchingUp(1, 3)
        now += QUEUED_UNATTENDED_SECONDS + 1
        assertTrue(ConfirmQueued(outbox, lock) { now }(queued.accountId))

        // The light client catches up only after the tap's hour: it asks again.
        now += QUEUED_UNATTENDED_SECONDS + 1
        light.status.value = LightClientStatus.Synced(100, 1_800_000_090, null)
        follow.round()
        assertEquals(0, broadcaster.sent.size)
    }

    @Test
    fun theHeadroomIsEightyOneSixtyFourthsRoundedUp() {
        assertEquals(UInt256.of(81), UInt256.of(64).withHeadroom())
        assertEquals(UInt256.of(2), UInt256.of(1).withHeadroom())
        assertEquals(UInt256.ZERO, UInt256.ZERO.withHeadroom())
    }

    private suspend fun draftTenth() = draft(alice, bob, TransferAsset.Native, UInt256.of(eth / 10), FeeLevel.NORMAL)

    private suspend fun queued(amount: UInt256): QueuedTransaction {
        val drafted = assertIs<TransferDrafting.Drafted>(draft(alice, bob, TransferAsset.Native, amount, FeeLevel.NORMAL)).draft
        return assertIs<TransferQueuing.Queued>(QueueTransfer(FakeSigner(Signing.Signed(signature)), outbox, queueLock) { now }(drafted, unlock)).queued
    }

    private suspend fun queuedTenth() = queued(UInt256.of(eth / 10))

    private fun follower(broadcaster: Broadcaster, notifier: OutboxNotifier, lock: OutboxLock = OutboxLock()) =
        FollowOutbox(outbox, check, light, broadcaster, sent, notifier, lock, { now })

    private fun feeQuote(base: Long, block: Long) = FeeQuote(
        nextBaseFee = UInt256.of(base),
        tips = mapOf(FeeLevel.SLOW to UInt256.of(1 * gwei), FeeLevel.NORMAL to UInt256.of(2 * gwei), FeeLevel.FAST to UInt256.of(3 * gwei)),
        blockNumber = block,
    )

    private fun keptFees() = KeptFees(feeQuote(base = 10 * gwei, block = 80), 1_800_000_000)

    private fun sentTransfer(nonce: Long, maxCost: UInt256) = SentTransfer(
        network = network,
        account = alice,
        nonce = nonce,
        signed = SignedTransfer(byteArrayOf(nonce.toByte(), 1, 2)),
        action = SentAction.Transfer(bob, TransferAsset.Native, UInt256.of(1)),
        maxCost = maxCost,
        sentAt = now,
    )

    private inner class FakeKept : KeptTransactionInputs {
        var nonce: KeptNonce? = KeptNonce(7, 80, 1_800_000_000)
        var fees: KeptFees? = keptFees()

        override suspend fun minedNonce(network: Network, account: Address): KeptNonce? = nonce

        override suspend fun fees(network: Network): KeptFees? = fees
    }

    private inner class FakeInputs : TransactionInputs {
        var gas = 21_000L
        var simulation: Simulation = Simulation.Succeeded("", 100)
        var fees: FeeReading = FeeReading.Quoted(feeQuote(base = 10 * gwei, block = 100))
        var mined = 7L
        var pending = 7L

        override suspend fun nonce(network: Network, account: Address): NonceReading = NonceReading.Read(pending)

        override suspend fun minedNonce(network: Network, account: Address): NonceReading = NonceReading.Read(mined)

        override suspend fun fees(network: Network): FeeReading = fees

        override suspend fun estimateGas(network: Network, from: Address, to: Address, value: UInt256, data: ByteArray): GasEstimate =
            GasEstimate.Estimated(gas)

        override suspend fun simulate(network: Network, from: Address, to: Address, value: UInt256, data: ByteArray): Simulation =
            simulation
    }

    private class FakeSentTransfers : SentTransfers {
        val transfers = MutableStateFlow<List<SentTransfer>>(emptyList())
        var failRecords = false

        override fun observe(): Flow<SentTransferList> = transfers.map { SentTransferList.Loaded(it) }

        override suspend fun record(transfer: SentTransfer): Boolean {
            if (failRecords) return false
            transfers.value = transfers.value.filterNot { it.hash == transfer.hash } + transfer
            return true
        }

        override suspend fun prune(account: AccountId, minedCount: Long, unlistedBefore: Long): Boolean {
            transfers.value = transfers.value.filterNot { it.prunable(account, minedCount, unlistedBefore) }
            return true
        }
    }

    private class ListOutbox : Outbox {
        val queued = MutableStateFlow<List<QueuedTransaction>>(emptyList())

        override fun observe(): Flow<OutboxList> = queued.map { OutboxList.Loaded(it) }

        override suspend fun put(queued: QueuedTransaction): Boolean {
            this.queued.value = this.queued.value.filterNot { it.accountId == queued.accountId } + queued
            return true
        }

        override suspend fun remove(account: AccountId): Boolean {
            queued.value = queued.value.filterNot { it.accountId == account }
            return true
        }

        override suspend fun acknowledgeLost(): Boolean = true
    }

    private class FakeSigner(private val answer: Signing) : Signer {
        val asked = mutableListOf<Triple<Address, Digest, Unlock>>()

        override suspend fun sign(account: Address, digest: Digest, unlock: Unlock): Signing {
            asked += Triple(account, digest, unlock)
            return answer
        }
    }

    private class FakeBroadcaster(private val answer: Broadcast) : Broadcaster {
        val sent = mutableListOf<ByteArray>()

        override suspend fun send(network: Network, raw: ByteArray): Broadcast {
            sent += raw
            return answer
        }
    }

    private class RecordingNotifier : OutboxNotifier {
        val told = mutableListOf<String>()

        override fun sent(queued: QueuedTransaction, hash: String) {
            told += "sent $hash"
        }

        override fun conflict(queued: QueuedTransaction, conflict: QueueConflict) {
            told += "conflict"
        }
    }
}
