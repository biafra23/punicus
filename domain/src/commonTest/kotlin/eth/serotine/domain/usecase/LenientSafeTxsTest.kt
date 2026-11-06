package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeCalls
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeSettingsCalls
import eth.serotine.domain.model.SafeSettingsChange
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeQuote
import eth.serotine.domain.repository.HeldConflict
import eth.serotine.domain.repository.HeldSafeSignature
import eth.serotine.domain.repository.KeptFees
import eth.serotine.domain.repository.KeptNonce
import eth.serotine.domain.repository.KeptSafe
import eth.serotine.domain.repository.KeptTransactionInputs
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.QueueConflict
import eth.serotine.domain.repository.QueueRefusal
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeDraftBasis
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.Unlock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Lenient mode's Safe transactions (docs/lenient-mode.md, *Safe transactions in lenient mode*): the
 * proposal and the co-signature drafted on the Safe last verified and held until their check, and the
 * approval on chain and the execution queued like a send, checked with the strict quote again.
 */
class LenientSafeTxsTest {

    private val world = SafeWorld()
    private val network = Network.SEPOLIA
    private val usdc = Token(Address.of("0x" + "cc".repeat(20)), "USDC", "USD Coin", 6, icon = null)
    private val unlock = Unlock.WithPassword(WalletPassword("correct horse battery"))
    private var now = 1_800_000_100L

    // The Safe as last verified, an hour and a half ago; the light client catches up meanwhile.
    private val keptSafes = FakeKeptSafes(KeptSafe(world.state, 90, 1_800_000_000))
    private val keptInputs = FakeKept()
    private val outbox = ListOutbox()
    private val lock = OutboxLock()
    private val light = FakeLightClient()
    private val notifier = RecordingNotifier()

    private val draftSafeTx = DraftSafeTx(
        keptSafes,
        world.accounts,
        world.nativeBalances,
        world.tokenBalances,
        world.queue,
        outbox,
        network,
    )
    private val hold = HoldSafeSignature(world.signer, keptSafes, world.recovery, outbox, lock, network) { now }
    private val checkHeld = CheckHeldSignature(world.observeSafe, world.recovery)
    private val draftCall = DraftSafeCall(
        keptSafes,
        keptInputs,
        world.recovery,
        world.nativeBalances,
        world.sent,
        outbox,
        network,
    ) { now }
    private val queueCall = QueueSafeCall(world.signer, outbox, lock) { now }
    private val approvalQuote = QuoteSafeApproval(
        world.observeSafe,
        world.approvals,
        world.nativeBalances,
        world.inputs,
        world.sent,
        network,
        outbox,
    ) { now }
    private val executionQuote = QuoteSafeExecution(
        world.observeSafe,
        world.recovery,
        world.approvals,
        world.nativeBalances,
        world.inputs,
        world.sent,
        network,
        outbox,
    ) { now }
    private val checkQueued = CheckQueued({ error("no transfer here") }, { approvalQuote }, { executionQuote })

    private fun follower() = FollowOutbox(
        outbox,
        checkQueued,
        light,
        world.broadcaster,
        world.sent,
        notifier,
        lock,
        checkHeld,
        world.queue,
        { now },
    )

    init {
        syncing()
    }

    /** The light client catches up: the Safe cannot be verified, and every balance is as last verified. */
    private fun syncing() {
        world.kind.value = KindReading.Syncing
        world.native = BalanceReading.LastKnown(Wei.of("1000000000000000000"), 90, asOf = 1_800_000_000)
        light.status.value = LightClientStatus.CatchingUp(1, 3)
    }

    /** It has caught up: the Safe verifies as [world]'s state says, the balances fresh. */
    private fun caughtUp() {
        world.state = world.state
        world.native = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        light.status.value = LightClientStatus.Synced(100, 1_800_000_090, null)
    }

    private fun usdcTransfer(nonce: Long) =
        SafeTx.call(usdc.address, UInt256.ZERO, Erc20.transferData(world.recipient, UInt256.of(1_500_000)), nonce)

    private suspend fun draftEth(amount: Long = 100_000_000_000_000_000) =
        draftSafeTx(world.safe, world.recipient, TransferAsset.Native, UInt256.of(amount))

    private suspend fun heldProposal(): HeldSafeSignature {
        val draft = assertIs<SafeTxDrafting.Drafted>(draftEth()).draft
        val ready = assertIs<HeldSignCheck.Ready>(hold.check(draft.request, world.alice.address))
        return assertIs<SafeSignatureHolding.Held>(hold.sign(ready, unlock, draft)).held
    }

    // The proposal

    @Test
    fun aProposalIsDraftedOnTheSafeLastVerifiedWithItsAgesAndNoSimulation() = runTest {
        val strict = ProposeSafeTx(
            world.observeSafe,
            world.nativeBalances,
            world.tokenBalances,
            world.inputs,
            world.queue,
            network,
            outbox,
        )
        assertEquals(
            SafeTxProposing.Refused(SafeTxRefusal.Syncing),
            strict(world.safe, world.recipient, TransferAsset.Native, UInt256.of(1)),
            "strict mode refuses while syncing",
        )

        val draft = assertIs<SafeTxDrafting.Drafted>(draftEth()).draft
        assertEquals(4, draft.request.nonce, "the kept nonce")
        assertEquals(SafeTx.call(world.recipient, UInt256.of(100_000_000_000_000_000), ByteArray(0), 4), draft.request.tx)
        assertEquals(listOf(world.alice.address), draft.ownersHeld)
        assertEquals(SafeDraftBasis(90, 1_800_000_000, 90, 1_800_000_000), draft.builtOn)
        assertTrue(world.inputs.simulated.isEmpty(), "nothing is run while syncing")
        assertTrue(world.queue.requests.value.isEmpty(), "nothing is queued by a draft")
    }

    @Test
    fun aProposalWaitsForASafeNeverVerifiedHereAndKeepsEveryRefusal() = runTest {
        assertEquals(SafeTxDrafting.Refused(SafeTxProposing.ToSafeItself), draftSafeTx(world.safe, world.safe, TransferAsset.Native, UInt256.ONE))
        assertEquals(
            SafeTxDrafting.Refused(SafeTxProposing.InsufficientFunds),
            draftEth(2_000_000_000_000_000_000),
        )
        keptSafes.kept.value = null
        assertEquals(SafeTxDrafting.Waits(DraftInput.SAFE), draftEth())
    }

    @Test
    fun aHeldSignatureTakesItsNonceAndWhatItMovesFromTheNextProposal() = runTest {
        heldProposal()
        val next = assertIs<SafeTxDrafting.Drafted>(draftEth()).draft
        assertEquals(5, next.request.nonce, "behind the held one")
        assertEquals(1, next.queuedAhead)
        assertEquals(UInt256.of(100_000_000_000_000_000), next.queuedAmount)
    }

    // Holding a signature

    @Test
    fun aSignatureIsHeldNotQueuedAndTakesNoPlaceOfTheOwnersAccount() = runTest {
        val held = heldProposal()

        assertTrue(held.proposed)
        assertEquals(world.alice.address, held.owner)
        assertEquals(world.alice.address, world.recovery.recover(held.hash, assertNotNull(held.signature)))
        assertEquals(listOf(held), outbox.held.value)
        assertTrue(world.queue.requests.value.isEmpty(), "the Safe's queue keeps only what was checked")
        assertTrue(outbox.queued.value.isEmpty(), "it spends no nonce of the owner's")
        assertFalse(assertNotNull(held.signature).r in held.toString(), "its toString never names the signature")
    }

    @Test
    fun aCoSignatureKeepsEverySafeRefusalAgainstTheKeptSafe() = runTest {
        fun request(tx: SafeTx) = SigningRequest.of(network, world.safe, tx)
        val module = SafeTx.call(world.safe, UInt256.ZERO, SafeSettingsCalls.ENABLE_MODULE + ByteArray(12) + ByteArray(20) { 0x77 }, 4)
        assertIs<SafeTxRefusal.ModuleEnabled>(assertIs<HeldSignCheck.Refused>(hold.check(request(module), world.alice.address)).refusal)
        val handler = Address.of("0x" + "66".repeat(20))
        val fallback = SafeTx.call(world.safe, UInt256.ZERO, SafeSettingsCalls.setFallbackHandler(handler), 4)
        assertEquals(
            HeldSignCheck.Refused(SafeTxRefusal.UnpinnedFallbackHandler(handler)),
            hold.check(request(fallback), world.alice.address),
        )
        val trailing = SafeTx.call(world.safe, UInt256.ZERO, SafeSettingsCalls.changeThreshold(1) + byteArrayOf(0), 4)
        assertEquals(HeldSignCheck.Refused(SafeTxRefusal.UndecodedSelfCall), hold.check(request(trailing), world.alice.address))
        val delegate = SafeTx(usdc.address, UInt256.ZERO, ByteArray(0), SafeOperation.DELEGATECALL, UInt256.ZERO, UInt256.ZERO, UInt256.ZERO, Address.ZERO, Address.ZERO, 4)
        assertEquals(HeldSignCheck.Refused(SafeTxRefusal.DelegateCall), hold.check(request(delegate), world.alice.address))
        // A swap's approval stays strict: its spender is checked against code proven now.
        val approval = SafeTx.call(usdc.address, UInt256.ZERO, Erc20.approveData(world.recipient, UInt256.ONE), 4)
        assertEquals(HeldSignCheck.Refused(SafeTxRefusal.SwapNeedsFreshCheck), hold.check(request(approval), world.alice.address))
        // Against the kept Safe: a used nonce, an address that is no kept owner, a signature it has already.
        assertEquals(HeldSignCheck.Refused(SafeTxRefusal.NonceUsed(4)), hold.check(request(usdcTransfer(3)), world.alice.address))
        assertEquals(
            HeldSignCheck.Refused(SafeTxRefusal.NotAnOwner(world.stranger)),
            hold.check(request(usdcTransfer(4)), world.stranger),
        )
        assertEquals(
            HeldSignCheck.Refused(SafeTxRefusal.AlreadySigned(world.alice.address)),
            hold.check(world.request(usdcTransfer(4), world.alice.address), world.alice.address),
        )
        assertTrue(world.signer.asked.isEmpty(), "no key is asked for")
    }

    @Test
    fun oneHeldSignaturePerSafeAndNonce() = runTest {
        val held = heldProposal()
        val other = world.request(usdcTransfer(4), world.carol)
        assertEquals(HeldSignCheck.NonceBusy(held.hash), hold.check(other, world.alice.address))
        assertIs<HeldSignCheck.Ready>(hold.check(world.request(usdcTransfer(5)), world.alice.address))
    }

    @Test
    fun aSignatureThatRecoversToAnotherAddressIsNeverHeld() = runTest {
        val ready = assertIs<HeldSignCheck.Ready>(hold.check(world.request(usdcTransfer(4)), world.alice.address))
        world.signer.signAs = world.carol
        assertEquals(
            SafeSignatureHolding.Refused(SafeTxRefusal.BadSignature(world.alice.address)),
            hold.sign(ready, unlock),
        )
        assertTrue(outbox.held.value.isEmpty())
    }

    // The check of a held signature

    @Test
    fun aHeldSignatureWaitsForTheLightClientAndPassesIntoTheQueueOnceChecked() = runTest {
        val held = heldProposal()
        follower().round()
        assertEquals(listOf(held), outbox.held.value, "not checked while syncing")

        caughtUp()
        follower().round()

        assertTrue(outbox.held.value.isEmpty())
        val queued = world.queue.requests.value.single()
        assertEquals(held.hash, queued.hash)
        assertEquals(listOf(world.alice.address), queued.signers)
        assertEquals(listOf("checked ${held.hash.hex}"), notifier.told)
    }

    @Test
    fun aNonceUsedMeanwhileDeletesTheSignatureAndAsksForANewOne() = runTest {
        val held = heldProposal()
        world.state = world.state.copy(nonce = 5)
        caughtUp()

        follower().round()

        val kept = outbox.held.value.single()
        assertNull(kept.signature, "deleted, never shown")
        assertEquals(HeldConflict.NonceUsed(5), kept.conflict)
        assertEquals(held.hash, kept.hash)
        assertTrue(world.queue.requests.value.isEmpty())
        assertEquals(listOf("held conflict ${HeldConflict.NonceUsed(5)}"), notifier.told)
    }

    @Test
    fun aSignerNoLongerAnOwnerIsDiscardedAndTold() = runTest {
        heldProposal()
        world.state = world.state.copy(owners = listOf(world.bob.address, world.carol))
        caughtUp()

        follower().round()

        assertEquals(HeldConflict.NotAnOwner, outbox.held.value.single().conflict)
        assertTrue(world.queue.requests.value.isEmpty())
    }

    @Test
    fun aStrictProposalNeverTakesTheNonceOfASignatureHeldHere() = runTest {
        heldProposal()
        caughtUp()
        val strict = ProposeSafeTx(
            world.observeSafe,
            world.nativeBalances,
            world.tokenBalances,
            world.inputs,
            world.queue,
            network,
            outbox,
        )
        val proposal = assertIs<SafeTxProposing.Proposed>(
            strict(world.safe, world.recipient, TransferAsset.Native, UInt256.of(100_000_000_000_000_000)),
        ).proposal
        assertEquals(5, proposal.request.nonce, "behind the held one")
        assertEquals(UInt256.of(100_000_000_000_000_000), proposal.queuedAmount)
    }

    @Test
    fun aProposalWhoseNonceAnotherTransactionTookInTheQueueIsProposedAgain() = runTest {
        val held = heldProposal()
        // Put in the queue meanwhile at the same nonce: an import, a strict proposal of another device's.
        world.queue.save(world.request(usdcTransfer(4), world.carol))
        caughtUp()

        follower().round()

        assertEquals(HeldConflict.NonceTaken, outbox.held.value.single().conflict)
        assertNull(outbox.held.value.single().signature)
        assertTrue(world.queue.requests.value.none { it.hash == held.hash }, "never saved beside the other")
    }

    @Test
    fun aSignatureSavedByARoundWhoseReleaseFailedIsOnlyReleased() = runTest {
        val held = heldProposal()
        caughtUp()
        outbox.failSaves = true
        follower().round()
        assertEquals(listOf(held), outbox.held.value, "saved to the queue, but not released")
        assertEquals(listOf(world.alice.address), world.queue.requests.value.single().signers)

        // The Safe's nonce moved on since: still released, never told as deleted.
        world.state = world.state.copy(nonce = 5)
        outbox.failSaves = false
        follower().round()

        assertTrue(outbox.held.value.isEmpty())
        assertEquals(listOf("checked ${held.hash.hex}"), notifier.told)
    }

    @Test
    fun aDiscardWaitsWhileACheckRunsAndNeverCutsItInHalf() = runTest {
        val held = heldProposal()
        light.status.value = LightClientStatus.Synced(100, 1_800_000_090, null)
        // The Safe cannot be verified this time: the check waits for the next reading, holding the lock.
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        val round = launch { follower().round() }
        runCurrent()
        val discard = async { DiscardHeld(outbox, lock)(held.safe, held.hash) }
        runCurrent()
        assertFalse(discard.isCompleted, "it waits for the check under way")

        caughtUp()
        runCurrent()
        round.join()

        assertEquals(HeldDiscard.Gone, discard.await(), "checked first: in the queue now, not discarded")
        assertEquals(listOf(world.alice.address), world.queue.requests.value.single().signers)
    }

    @Test
    fun anExecutionWhoseSignedCallDataIsNotItsRequestsIsAConflict() = runTest {
        val request = world.request(usdcTransfer(4), world.bob.address)
        val draft = assertIs<SafeCallDrafting.Drafted>(draftCall.execution(request, world.alice.address, FeeLevel.NORMAL)).draft
        val queued = assertIs<TransferQueuing.Queued>(queueCall(draft, unlock)).queued
        caughtUp()
        // Another transfer at the same nonce: its call data, which carries no nonce, differs in the recipient.
        val elsewhere = SafeTx.call(usdc.address, UInt256.ZERO, Erc20.transferData(world.stranger, UInt256.of(1_500_000)), 4)
        val other = queued.copy(action = SentAction.SafeExecution(world.request(elsewhere, world.bob.address)))
        assertEquals(QueuedCheck.Conflicts(QueueConflict.Refused(QueueRefusal.ENGINE_REFUSED)), checkQueued(other))
    }

    @Test
    fun oneExecutionOfASafeTransactionIsQueuedFromAnyAccountHere() = runTest {
        val request = world.request(usdcTransfer(4), world.bob.address, world.carol)
        val draft = assertIs<SafeCallDrafting.Drafted>(draftCall.execution(request, world.alice.address, FeeLevel.NORMAL)).draft
        assertIs<TransferQueuing.Queued>(queueCall(draft, unlock))
        assertEquals(
            SafeCallDrafting.Refused(SafeTxRefusal.AlreadySent),
            draftCall.execution(request, world.stranger, FeeLevel.NORMAL),
        )
    }

    @Test
    fun aDiscardDeletesTheSignatureAndNothingIsSavedOfIt() = runTest {
        val held = heldProposal()
        assertEquals(HeldDiscard.Discarded, DiscardHeld(outbox, lock)(held.safe, held.hash))
        assertTrue(outbox.held.value.isEmpty())
        assertEquals(HeldDiscard.Gone, DiscardHeld(outbox, lock)(held.safe, held.hash))
        caughtUp()
        follower().round()
        assertTrue(world.queue.requests.value.isEmpty(), "a discarded signature is never saved")
    }

    @Test
    fun aHeldSignatureTheQueueCannotSaveStaysHeld() = runTest {
        val held = heldProposal()
        caughtUp()
        world.queue.unreadable = true
        follower().round()
        assertEquals(listOf(held), outbox.held.value)
        world.queue.unreadable = false
        follower().round()
        assertTrue(outbox.held.value.isEmpty())
    }

    // Approving on chain and executing through the outbox

    @Test
    fun anApprovalIsDraftedOnWhatWasVerifiedLastWithItsCeilingAndQueuedLikeASend() = runTest {
        val request = world.request(usdcTransfer(4))
        val draft = assertIs<SafeCallDrafting.Drafted>(draftCall.approval(request, world.alice.address, FeeLevel.NORMAL)).draft

        val tx = draft.call.transaction
        assertEquals(7, tx.nonce, "past the kept count")
        assertEquals(DraftGas.SAFE_APPROVAL, tx.gasLimit, "the pinned ceiling, not an estimate")
        assertEquals(world.safe, tx.to)
        assertContentEquals(SafeCalls.approveHash(request.hash), tx.data)
        assertEquals(90, draft.call.builtOn.safeBlock)
        assertEquals(1_800_000_000, draft.call.builtOn.oldestAsOf)

        val queued = assertIs<TransferQueuing.Queued>(queueCall(draft, unlock)).queued
        assertEquals(SentAction.SafeApproval(request), queued.action)
        assertEquals(world.alice.address, queued.account)
        assertTrue(world.broadcaster.sent.isEmpty(), "nothing is broadcast")
    }

    @Test
    fun anApprovalIsCheckedWithTheStrictQuoteAgainAndSentOnceItPasses() = runTest {
        val request = world.request(usdcTransfer(4))
        val queued = queuedApproval(request)
        assertIs<QueuedCheck.Waits>(checkQueued(queued), "the Safe is not verifiable while syncing")

        caughtUp()
        assertEquals(QueuedCheck.Passes, checkQueued(queued))
        world.broadcaster.answer = Broadcast.Sent(queued.hash!!)
        follower().round()
        assertTrue(outbox.queued.value.isEmpty())
        val sent = (world.sent.observe().first() as SentTransferList.Loaded).transfers.single()
        assertEquals(SentAction.SafeApproval(request), sent.action)
    }

    @Test
    fun anApprovalWhoseOwnerWasRemovedOrWhoseNonceWasUsedIsAConflict() = runTest {
        val queued = queuedApproval(world.request(usdcTransfer(4)))
        caughtUp()
        world.state = world.state.copy(owners = listOf(world.bob.address, world.carol))
        assertEquals(
            QueuedCheck.Conflicts(QueueConflict.Refused(QueueRefusal.NOT_AN_OWNER)),
            checkQueued(queued),
        )
        world.state = world.state.copy(owners = listOf(world.alice.address, world.bob.address), nonce = 5)
        assertEquals(
            QueuedCheck.Conflicts(QueueConflict.Refused(QueueRefusal.SAFE_NONCE_USED)),
            checkQueued(queued),
        )
    }

    @Test
    fun anExecutionCountsTheSignaturesValidAgainstTheKeptOwnersAndWaitsWhenShort() = runTest {
        val short = world.request(usdcTransfer(4), world.bob.address)
        // Bob's signature and Alice's own sending: two of two.
        val draft = assertIs<SafeCallDrafting.Drafted>(draftCall.execution(short, world.alice.address, FeeLevel.NORMAL)).draft
        assertEquals(DraftGas.SAFE_EXECUTION, draft.call.transaction.gasLimit)
        assertEquals(world.alice.address, draft.signatures.first().owner, "the executor's own first")
        // A non-owner executing has only Bob's: approvals on chain cannot be read while syncing.
        val stranger = world.stranger
        assertEquals(
            SafeCallDrafting.ShortOfSignatures(1, 2),
            draftCall.execution(short, stranger, FeeLevel.NORMAL),
        )
        assertEquals(
            SafeCallDrafting.Refused(SafeTxRefusal.NotNext(4)),
            draftCall.execution(world.request(usdcTransfer(5), world.bob.address), world.alice.address, FeeLevel.NORMAL),
        )
    }

    @Test
    fun anExecutionIsCheckedRunningTheSignedCallDataAndConflictsOnceItsNonceIsUsed() = runTest {
        val request = world.request(usdcTransfer(4), world.bob.address)
        val draft = assertIs<SafeCallDrafting.Drafted>(draftCall.execution(request, world.alice.address, FeeLevel.NORMAL)).draft
        val queued = assertIs<TransferQueuing.Queued>(queueCall(draft, unlock)).queued
        assertEquals(SentAction.SafeExecution(request), queued.action)

        caughtUp()
        // execTransaction returns true.
        world.inputs.simulation = Simulation.Succeeded("0".repeat(63) + "1", 100)
        assertEquals(QueuedCheck.Passes, checkQueued(queued))
        assertContentEquals(queued.transaction.data, world.inputs.simulated.last().third, "the signed call data is run")

        world.state = world.state.copy(nonce = 5)
        assertEquals(
            QueuedCheck.Conflicts(QueueConflict.Refused(QueueRefusal.SAFE_NONCE_USED)),
            checkQueued(queued),
        )
    }

    @Test
    fun theChecksFundsRowTestsTheSignedFiguresOfASafeCall() = runTest {
        val queued = queuedApproval(world.request(usdcTransfer(4)))
        caughtUp()
        // The signed cap (21 gwei) times the ceiling, not the fresh estimate: just short of it.
        val upfront = 21_000_000_000L * DraftGas.SAFE_APPROVAL
        world.native = BalanceReading.Verified(Wei.of((upfront - 1).toString()), 100)
        assertEquals(QueuedCheck.Conflicts(QueueConflict.InsufficientFunds), checkQueued(queued))
        world.native = BalanceReading.Verified(Wei.of(upfront.toString()), 100)
        assertEquals(QueuedCheck.Passes, checkQueued(queued))
    }

    @Test
    fun anApprovalNeedsMoreGasThanItsCeilingIsAConflict() = runTest {
        val queued = queuedApproval(world.request(usdcTransfer(4)))
        caughtUp()
        world.inputs.gas = DraftGas.SAFE_APPROVAL + 1
        assertEquals(
            QueuedCheck.Conflicts(QueueConflict.NeedsMoreGas(DraftGas.SAFE_APPROVAL, DraftGas.SAFE_APPROVAL + 1)),
            checkQueued(queued),
        )
    }

    @Test
    fun anyOtherActionIsStillAConflict() = runTest {
        val queued = queuedApproval(world.request(usdcTransfer(4)))
        val swapLike = queued.copy(action = SentAction.Wrap(usdc, UInt256.ONE))
        assertEquals(QueuedCheck.Conflicts(QueueConflict.Refused(QueueRefusal.ENGINE_REFUSED)), checkQueued(swapLike))
    }

    @Test
    fun anAccountWithSomethingQueuedDraftsNoSafeCall() = runTest {
        val queued = queuedApproval(world.request(usdcTransfer(4)))
        assertEquals(
            SafeCallDrafting.AccountBusy(queued.id),
            draftCall.approval(world.request(usdcTransfer(5)), world.alice.address, FeeLevel.NORMAL),
        )
    }

    private suspend fun queuedApproval(request: SigningRequest): QueuedTransaction {
        val draft = assertIs<SafeCallDrafting.Drafted>(draftCall.approval(request, world.alice.address, FeeLevel.NORMAL)).draft
        return assertIs<TransferQueuing.Queued>(queueCall(draft, unlock)).queued
    }

    /** The account's count and the network's fees as last verified: 7, and a base fee of 10 gwei. */
    private class FakeKept : KeptTransactionInputs {
        override suspend fun minedNonce(network: Network, account: Address): KeptNonce = KeptNonce(7, 80, 1_800_000_000)

        override suspend fun fees(network: Network): KeptFees = KeptFees(
            FeeQuote(
                nextBaseFee = UInt256.of(10_000_000_000L),
                tips = FeeLevel.entries.associateWith { UInt256.of(1_000_000_000L) },
                blockNumber = 80,
            ),
            1_800_000_000,
        )
    }

    // The nonces held signatures take

    @Test
    fun aHeldProposalPastAGapIsProposedAgainNeverSavedBehindIt() = runTest {
        val first = heldProposal()
        val second = heldProposal()
        assertEquals(listOf(4L, 5L), listOf(first.nonce, second.nonce))
        // The one before it is discarded: nothing takes nonce 4.
        assertEquals(HeldDiscard.Discarded, DiscardHeld(outbox, lock)(first.safe, first.hash))
        caughtUp()

        follower().round()

        assertEquals(HeldConflict.NonceGap(4), outbox.held.value.single().conflict)
        assertNull(outbox.held.value.single().signature)
        assertTrue(world.queue.requests.value.none { it.hash == second.hash }, "never saved past the gap")
    }

    @Test
    fun heldProposalsAreSavedInTheirNoncesOrder() = runTest {
        val first = heldProposal()
        val second = heldProposal()
        // Listed the other way round: the one at nonce 4 is still checked first, and the one at 5 after it.
        outbox.held.value = outbox.held.value.reversed()
        caughtUp()

        follower().round()

        assertEquals(emptyList(), outbox.held.value)
        assertEquals(listOf(first.hash, second.hash), world.queue.requests.value.sortedBy { it.nonce }.map { it.hash })
    }

    @Test
    fun aConflictedHeldProposalTakesNoNonceAndOneSignedThereIsHeldInItsPlace() = runTest {
        val held = heldProposal()
        outbox.held.value = listOf(held.copy(signature = null, conflict = HeldConflict.NotAnOwner))

        val draft = assertIs<SafeTxDrafting.Drafted>(draftEth(200_000_000_000_000_000)).draft
        assertEquals(4, draft.request.nonce, "its signature deleted, it takes no nonce")
        val ready = assertIs<HeldSignCheck.Ready>(hold.check(draft.request, world.alice.address))
        val again = assertIs<SafeSignatureHolding.Held>(hold.sign(ready, unlock, draft)).held
        assertEquals(listOf(again), outbox.held.value, "held in the conflicted one's place")
    }

    @Test
    fun aStrictProposalOfAnyKindTakesItsNonceAfterTheSignaturesHeld() = runTest {
        heldProposal()
        caughtUp()
        val settings = ProposeSafeSettingsChange(world.observeSafe, world.inputs, world.queue, network, outbox)

        val change = assertIs<SafeSettingsProposing.Proposed>(
            settings(world.safe, SafeSettingsChange.ThresholdChanged(1)),
        ).proposal

        assertEquals(5, change.request.nonce, "behind the held one, never at its nonce")
    }

    @Test
    fun aSafeCallIsNotSignedForAnOutboxThatCannotBeRead() = runTest {
        val request = world.request(usdcTransfer(4), world.bob.address)
        val draft = assertIs<SafeCallDrafting.Drafted>(
            draftCall.execution(request, world.alice.address, FeeLevel.NORMAL),
        ).draft
        outbox.unreadable = true

        assertEquals(TransferQueuing.NotSaved, queueCall(draft, unlock))
        assertTrue(world.signer.asked.isEmpty(), "no key is asked for")
    }
}
