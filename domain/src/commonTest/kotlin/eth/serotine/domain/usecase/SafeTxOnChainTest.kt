package eth.serotine.domain.usecase

import eth.serotine.domain.model.AbiWords
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PinnedFallbackHandlers
import eth.serotine.domain.model.SafeCalls
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.hexToBytes
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.ApprovalReading
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class SafeTxOnChainTest {

    private val world = SafeWorld()
    private val unlock = Unlock.WithBiometrics("Send the Safe transaction")
    private val payment = SafeTx.call(Address.of("0x" + "bb".repeat(20)), UInt256.of(5), ByteArray(0), nonce = 4)

    private val quoteApproval = QuoteSafeApproval(
        world.observeSafe,
        world.approvals,
        world.nativeBalances,
        world.inputs,
        world.sent,
        Network.SEPOLIA,
    ) { 1_800_000_000 }
    private val quoteExecution = QuoteSafeExecution(
        world.observeSafe,
        world.recovery,
        world.approvals,
        world.nativeBalances,
        world.inputs,
        world.sent,
        Network.SEPOLIA,
    ) { 1_800_000_000 }

    private val executed = "0".repeat(63) + "1"

    private val readOwners = ReadSafeTxOwners(world.observeSafe, world.recovery, world.approvals, Network.SEPOLIA)

    @Test
    fun theOwnersStandWaitsOutAnUnprovenReadingOfTheSafeForTheNextOne() = runTest {
        val request = world.request(payment, world.alice.address)
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        launch {
            delay(12.seconds)
            world.kind.value = KindReading.Verified(AddressKind.Safe(world.state), 101)
        }

        val reading = assertIs<SafeTxOwnersReading.Read>(readOwners(request))

        assertEquals(101, reading.blockNumber)
        assertEquals(12_000, currentTime, "read once the Safe was proven, not before")
    }

    @Test
    fun eachOwnerStandsAsItsSignatureOrItsApprovalOnChainSays() = runTest {
        val request = world.request(payment, world.alice.address, world.stranger)
        world.approvals.approved[world.bob.address] = ApprovalReading.Read(approved = true, blockNumber = 120)

        val reading = assertIs<SafeTxOwnersReading.Read>(
            ReadSafeTxOwners(world.observeSafe, world.recovery, world.approvals, Network.SEPOLIA)(request),
        )

        assertEquals(
            listOf(
                SafeTxOwner(world.alice.address, held = true, OwnerConfirmation.Signed),
                SafeTxOwner(world.bob.address, held = false, OwnerConfirmation.ApprovedOnChain(120)),
                SafeTxOwner(world.carol, held = false, OwnerConfirmation.Missing),
            ),
            reading.owners,
            "a stranger's signature counts for nobody",
        )
        assertEquals(2, reading.confirmations)
        assertEquals(2, reading.threshold)
        // As the execution counts them: an owner executing it confirms it by sending it.
        assertEquals(3, reading.confirmationsExecutedBy(world.carol))
        assertEquals(2, reading.confirmationsExecutedBy(world.alice.address), "it signed already")
        assertEquals(2, reading.confirmationsExecutedBy(world.stranger), "no owner")
    }

    @Test
    fun aSafeStillOnAPreOneThreeZeroSingletonIsNeitherApprovedNorExecutedThoughItsOwnersStand() = runTest {
        world.state = world.state.copy(version = "1.1.1")
        val request = world.request(payment, world.alice.address)

        val reading = assertIs<SafeTxOwnersReading.Read>(readOwners(request))
        assertEquals(SafeTxRefusal.TooOldToSign("1.1.1"), reading.refusal, "shown, with why it may not be acted on")
        assertEquals(1, reading.confirmations, "the owners still stand as the chain says")

        assertEquals(
            SafeApprovalQuoting.Refused(SafeTxRefusal.TooOldToSign("1.1.1")),
            quoteApproval(request, world.alice.address, FeeLevel.NORMAL),
        )
        assertEquals(
            SafeExecutionQuoting.Refused(SafeTxRefusal.TooOldToSign("1.1.1")),
            quoteExecution(request, world.alice.address, FeeLevel.NORMAL),
        )
    }

    @Test
    fun approvalAndExecutionQuotesSayWhenTheSafeWasCheckedBeforeTheirFiguresAreRead() = runTest {
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        launch {
            delay(10.seconds)
            world.kind.value = KindReading.Verified(AddressKind.Safe(world.state), 101)
        }
        val checked = mutableListOf<Long>()

        assertIs<SafeApprovalQuoting.Quoted>(
            quoteApproval(world.request(payment), world.alice.address, FeeLevel.NORMAL) { checked += currentTime },
        )
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        assertIs<SafeExecutionQuoting.Quoted>(
            quoteExecution(world.request(payment, world.carol), world.alice.address, FeeLevel.NORMAL) {
                checked += currentTime
            },
        )

        assertEquals(listOf(10_000L, 10_000L), checked, "each quote's age runs from after the Safe's wait")
    }

    @Test
    fun anOwnerApprovesTheHashOnChainFromItsAccount() = runTest {
        val request = world.request(payment)

        val quote = assertIs<SafeApprovalQuoting.Quoted>(
            quoteApproval(request, world.alice.address, FeeLevel.NORMAL),
        ).quote

        val tx = quote.call.transaction
        assertEquals(world.safe, tx.to)
        assertEquals(UInt256.ZERO, tx.value)
        assertContentEquals(SafeCalls.approveHash(request.hash), tx.data)
        assertEquals(7, tx.nonce, "the account's own nonce")
        assertEquals(world.alice.address to world.safe, world.inputs.simulated.single().let { it.first to it.second })

        val sending = ApproveSafeTxOnChain(world.signer, world.broadcaster, world.sent) { 1_800_000_001 }
        assertIs<TransferSending.Sent>(sending(quote, unlock))
        assertEquals(world.alice.address to tx.signingDigest(), world.signer.asked.single())
        val record = world.sent.transfers.value.single()
        assertEquals(SentAction.SafeApproval(request), record.action)
        assertEquals(7, record.nonce)
        assertEquals(quote.call.maxFee, record.maxCost)
    }

    @Test
    fun anApprovalIsOnlyAnOwnersOnceForATransactionThatCanStillRun() = runTest {
        val request = world.request(payment)
        suspend fun refusal(request: SigningRequest, owner: Address) =
            assertIs<SafeApprovalQuoting.Refused>(quoteApproval(request, owner, FeeLevel.NORMAL)).refusal

        assertEquals(SafeTxRefusal.NotAnOwner(world.stranger), refusal(request, world.stranger))
        val passed = world.request(SafeTx.call(world.safe, UInt256.ZERO, ByteArray(0), nonce = 3))
        assertEquals(SafeTxRefusal.NonceUsed(4), refusal(passed, world.alice.address))
        world.approvals.approved[world.alice.address] = ApprovalReading.Read(approved = true, blockNumber = 120)
        assertEquals(SafeTxRefusal.AlreadyApproved, refusal(request, world.alice.address))
        world.approvals.approved[world.alice.address] = ApprovalReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN), refusal(request, world.alice.address))
        assertEquals(emptyList(), world.inputs.simulated, "nothing quoted")
    }

    @Test
    fun anExecutingOwnerConfirmsItAndTheSignaturesArePackedByOwner() = runTest {
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        val request = world.request(payment, world.carol)

        val quote = assertIs<SafeExecutionQuoting.Quoted>(
            quoteExecution(request, world.alice.address, FeeLevel.FAST),
        ).quote

        val carols = request.signatures.single()
        assertEquals(listOf(SafeSignature.ApprovedHash(world.alice.address), carols), quote.signatures)
        assertEquals(emptyList(), world.approvals.asked, "two of two without asking the chain for approvals")
        val tx = quote.call.transaction
        assertEquals(world.safe, tx.to)
        assertContentEquals(SafeCalls.execTransaction(payment, quote.signatures), tx.data)
        assertEquals(world.alice.address, world.inputs.simulated.single().first, "run from the executor")

        val execute = ExecuteSafeTx(world.signer, world.broadcaster, world.sent) { 1_800_000_002 }
        val sent = assertIs<TransferSending.Sent>(execute(quote, unlock))
        val record = world.sent.transfers.value.single()
        assertEquals(SentAction.SafeExecution(request), record.action)
        assertEquals(Eip1559Transaction.hashOf(world.broadcaster.sent.single()), record.hash)
        assertEquals(record.hash, sent.hash)
    }

    @Test
    fun anOwnerExecutesWithApprovalsOnChainMakingUpTheThreshold() = runTest {
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        world.state = world.state.copy(threshold = 3)
        world.approvals.approved[world.bob.address] = ApprovalReading.Read(approved = true, blockNumber = 120)
        val request = world.request(payment, world.carol)

        val quote = assertIs<SafeExecutionQuoting.Quoted>(
            quoteExecution(request, world.alice.address, FeeLevel.NORMAL),
        ).quote

        assertEquals(
            listOf(
                SafeSignature.ApprovedHash(world.alice.address),
                request.signatures.single(),
                SafeSignature.ApprovedHash(world.bob.address),
            ),
            quote.signatures,
        )
        assertEquals(listOf(world.bob.address), world.approvals.asked, "only the owner not confirming yet")
    }

    @Test
    fun anyAccountHeldHereExecutesWithApprovalsOnChainMakingUpTheThreshold() = runTest {
        // Owner ruling, 2026-10-18, #56: any account whose key is here may send it, owner or not.
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        world.approvals.approved[world.bob.address] = ApprovalReading.Read(approved = true, blockNumber = 120)
        val request = world.request(payment, world.carol)

        val quote = assertIs<SafeExecutionQuoting.Quoted>(
            quoteExecution(request, world.stranger, FeeLevel.NORMAL),
        ).quote

        // No confirmation of its own: the stranger is no owner.
        assertEquals(
            listOf(request.signatures.single(), SafeSignature.ApprovedHash(world.bob.address)),
            quote.signatures,
        )
        assertEquals(listOf(world.alice.address, world.bob.address), world.approvals.asked)
    }

    @Test
    fun anExecutionShortOfTheThresholdOrOutOfTurnIsRefused() = runTest {
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        suspend fun refusal(request: SigningRequest, executor: Address = world.alice.address) =
            assertIs<SafeExecutionQuoting.Refused>(quoteExecution(request, executor, FeeLevel.NORMAL)).refusal

        // Alice by sending it and Carol's signature: two of three.
        world.state = world.state.copy(threshold = 3)
        assertEquals(SafeTxRefusal.ThresholdNotMet(2, 3), refusal(world.request(payment, world.carol)))
        // A former owner's signature counts for nothing.
        world.state = world.state.copy(owners = listOf(world.alice.address, world.bob.address), threshold = 2)
        assertEquals(SafeTxRefusal.ThresholdNotMet(1, 2), refusal(world.request(payment, world.carol)))
        world.state = world.state.copy(
            owners = listOf(world.alice.address, world.bob.address, world.carol),
            threshold = 3,
        )

        world.approvals.approved[world.bob.address] = ApprovalReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN), refusal(world.request(payment, world.carol)))

        val later = SafeTx.call(payment.to, payment.value, ByteArray(0), nonce = 5)
        assertEquals(SafeTxRefusal.NotNext(4), refusal(world.request(later, world.alice.address, world.carol)))
        val passed = SafeTx.call(payment.to, payment.value, ByteArray(0), nonce = 3)
        assertEquals(SafeTxRefusal.NonceUsed(4), refusal(world.request(passed, world.alice.address, world.carol)))
        val delegate = SafeTx(
            payment.to,
            UInt256.ZERO,
            ByteArray(0),
            SafeOperation.DELEGATECALL,
            UInt256.ZERO,
            UInt256.ZERO,
            UInt256.ZERO,
            Address.ZERO,
            Address.ZERO,
            nonce = 4,
        )
        assertEquals(SafeTxRefusal.DelegateCall, refusal(world.request(delegate, world.alice.address, world.carol)))
        assertEquals(emptyList(), world.inputs.simulated, "nothing quoted")
    }

    @Test
    fun anExecutionThatWouldFailOrRevertStopsAtTheQuote() = runTest {
        val request = world.request(payment, world.alice.address, world.carol)

        // false: the Safe's call failed under a gas price or safeTxGas, and its nonce would be used up.
        world.inputs.simulation = Simulation.Succeeded("0".repeat(64), 101)
        assertEquals(
            SafeExecutionQuoting.Refused(SafeTxRefusal.NotQuoted(CallRefusal.Reverts(101))),
            quoteExecution(request, world.alice.address, FeeLevel.NORMAL),
        )
        world.inputs.estimate = GasEstimate.Reverted("")
        assertEquals(
            SafeExecutionQuoting.Refused(SafeTxRefusal.NotQuoted(CallRefusal.Reverts(null))),
            quoteExecution(request, world.alice.address, FeeLevel.NORMAL),
        )
    }

    @Test
    fun aFallbackHandlerThatIsNotPinnedIsShownButNeverSignedApprovedOrExecutedHere() = runTest {
        // Owner ruling, 2026-10-18, #63: only the pinned CompatibilityFallbackHandler 1.4.1, or none.
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        fun setHandler(handler: Address) = SafeTx.call(
            world.safe,
            UInt256.ZERO,
            ("f08a0323" + "0".repeat(24) + handler.hex.removePrefix("0x")).hexToBytes(),
            nonce = 4,
        )
        val sign = SignSafeTx(world.signer, world.observeSafe, world.recovery, world.queue, Network.SEPOLIA)
        val hostile = world.request(setHandler(world.recipient), world.carol)
        val refusal = SafeTxRefusal.UnpinnedFallbackHandler(world.recipient)

        assertEquals(refusal, DecodedSafeTx.of(hostile).refusal, "said before anyone asks")
        assertEquals(SafeTxSigning.Refused(refusal), sign(hostile, world.alice.address, unlock))
        assertEquals(SafeApprovalQuoting.Refused(refusal), quoteApproval(hostile, world.alice.address, FeeLevel.NORMAL))
        assertEquals(
            SafeExecutionQuoting.Refused(refusal),
            quoteExecution(hostile, world.alice.address, FeeLevel.NORMAL),
        )
        assertEquals(emptyList(), world.inputs.simulated)

        // The pinned handler, and none at all (removing it, with its warning), may be signed.
        val pinned = world.request(setHandler(PinnedFallbackHandlers.COMPATIBILITY_1_4_1))
        assertEquals(null, DecodedSafeTx.of(pinned).refusal)
        assertEquals(null, DecodedSafeTx.of(world.request(setHandler(Address.ZERO))).refusal)
        assertIs<SafeTxSigning.Signed>(sign(pinned, world.alice.address, unlock))
    }

    /** [request] refused for [refusal] by signing, approval and execution, and before anyone asks if [before]. */
    private suspend fun assertRefusedEverywhere(request: SigningRequest, refusal: SafeTxRefusal, before: Boolean) {
        val sign = SignSafeTx(world.signer, world.observeSafe, world.recovery, world.queue, Network.SEPOLIA)
        assertEquals(if (before) refusal else null, DecodedSafeTx.of(request).refusal, "said before anyone asks")
        assertEquals(SafeTxSigning.Refused(refusal), sign(request, world.alice.address, unlock))
        assertEquals(SafeApprovalQuoting.Refused(refusal), quoteApproval(request, world.alice.address, FeeLevel.NORMAL))
        assertEquals(
            SafeExecutionQuoting.Refused(refusal),
            quoteExecution(request, world.alice.address, FeeLevel.NORMAL),
        )
    }

    @Test
    fun aSelfCallThatIsNotDecodedExactlyIsNeverSignedApprovedOrExecutedHere() = runTest {
        // Solidity ignores trailing call data and the Safe's decoder masks an address word: on chain
        // each of the first two would set the handler, though the strict decoding reads none of them.
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        val handler = "0".repeat(24) + world.recipient.hex.removePrefix("0x")
        fun selfCall(data: String, value: Long = 0) = world.request(
            SafeTx.call(world.safe, UInt256.of(value), data.hexToBytes(), nonce = 4),
            world.carol,
        )

        for (
            request in listOf(
                selfCall("f08a0323" + handler + "00"), // a trailing byte
                selfCall("f08a0323" + "ff".repeat(12) + world.recipient.hex.removePrefix("0x")), // dirty upper bytes
                selfCall("deadbeef" + handler), // a selector this wallet does not know
                selfCall("", value = 1), // ether to itself: no rejection
            )
        ) {
            assertRefusedEverywhere(request, SafeTxRefusal.UndecodedSelfCall, before = true)
        }
        assertEquals(emptyList(), world.inputs.simulated)

        // The empty, zero-value rejection, and a settings change decoded exactly, are not refused for it.
        assertEquals(null, DecodedSafeTx.of(selfCall("")).refusal)
        assertEquals(null, DecodedSafeTx.of(selfCall("694e80c3" + "0".repeat(63) + "1")).refusal)
    }

    @Test
    fun enablingAModuleIsShownButNeverSignedApprovedOrExecutedHere() = runTest {
        // Owner ruling, 2026-10-18, on #54: play it safe, whatever the module.
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        val sign = SignSafeTx(world.signer, world.observeSafe, world.recovery, world.queue, Network.SEPOLIA)
        val sentinel = Address.of("0x" + "0".repeat(39) + "1")
        fun change(data: ByteArray) = world.request(SafeTx.call(world.safe, UInt256.ZERO, data, nonce = 4), world.carol)

        val enable = change(AbiWords.selector("enableModule(address)") + AbiWords.word(world.recipient))
        assertRefusedEverywhere(enable, SafeTxRefusal.ModuleEnabled(world.recipient), before = true)
        assertEquals(emptyList(), world.inputs.simulated)

        // Disabling one keeps its warning and is not refused.
        world.state = world.state.copy(modules = listOf(world.recipient))
        val disable = change(
            AbiWords.selector("disableModule(address,address)") + AbiWords.word(sentinel) +
                AbiWords.word(world.recipient),
        )
        assertEquals(null, DecodedSafeTx.of(disable).refusal)
        assertIs<SafeTxSigning.Signed>(sign(disable, world.alice.address, unlock))
    }

    @Test
    fun aChangeThatLeavesEveryOwnerNeededIsRefusedWithoutARecoveryModule() = runTest {
        // Owner ruling, 2026-10-18, #54, the change half: the Safe is 2 of 3 (alice, bob, carol).
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        val sign = SignSafeTx(world.signer, world.observeSafe, world.recovery, world.queue, Network.SEPOLIA)
        val sentinel = Address.of("0x" + "0".repeat(39) + "1")
        fun change(data: ByteArray) = world.request(SafeTx.call(world.safe, UInt256.ZERO, data, nonce = 4), world.carol)
        fun changeThreshold(t: Long) = change(AbiWords.selector("changeThreshold(uint256)") + AbiWords.word(t))
        fun removeCarol(t: Long) = change(
            AbiWords.selector("removeOwner(address,address,uint256)") +
                AbiWords.word(sentinel) + AbiWords.word(world.carol) + AbiWords.word(t),
        )
        fun addStranger(t: Long) = change(
            AbiWords.selector("addOwnerWithThreshold(address,uint256)") + AbiWords.word(world.stranger) +
                AbiWords.word(t),
        )

        assertRefusedEverywhere(changeThreshold(3), SafeTxRefusal.NeedsRecoveryModule(owners = 3), before = false)
        assertRefusedEverywhere(removeCarol(2), SafeTxRefusal.NeedsRecoveryModule(owners = 2), before = false)
        assertRefusedEverywhere(addStranger(4), SafeTxRefusal.NeedsRecoveryModule(owners = 4), before = false)
        // Above the owner count too: it reverts today, and catches a threshold queued behind an owner added.
        assertRefusedEverywhere(changeThreshold(4), SafeTxRefusal.NeedsRecoveryModule(owners = 3), before = false)
        assertRefusedEverywhere(addStranger(5), SafeTxRefusal.NeedsRecoveryModule(owners = 4), before = false)
        assertRefusedEverywhere(removeCarol(3), SafeTxRefusal.NeedsRecoveryModule(owners = 2), before = false)
        // Screen 17 reads it with the owners, before anyone asks.
        assertEquals(
            SafeTxRefusal.NeedsRecoveryModule(owners = 3),
            assertIs<SafeTxOwnersReading.Read>(readOwners(changeThreshold(3))).refusal,
        )
        assertEquals(null, assertIs<SafeTxOwnersReading.Read>(readOwners(changeThreshold(1))).refusal)
        // A module the Safe has is no recovery module this wallet recognises.
        world.state = world.state.copy(modules = listOf(world.recipient))
        assertRefusedEverywhere(changeThreshold(3), SafeTxRefusal.NeedsRecoveryModule(owners = 3), before = false)

        // Shapes that leave an owner to spare, or one owner alone, may be signed.
        for (request in listOf(changeThreshold(1), removeCarol(1), addStranger(3))) {
            assertIs<SafeTxSigning.Signed>(sign(request, world.alice.address, unlock))
        }
        world.state = world.state.copy(owners = listOf(world.alice.address, world.carol), threshold = 1)
        assertIs<SafeTxSigning.Signed>(sign(removeCarol(1), world.alice.address, unlock), "1 of 1: no n of n")
    }

    @Test
    fun aSafeTxWithGasOrRefundTermsIsShownButNeverSignedApprovedOrExecutedHere() = runTest {
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        fun withTerms(safeTxGas: Long = 0, baseGas: Long = 0, gasPrice: Long = 0, gasToken: Address = Address.ZERO) =
            SafeTx(
                payment.to,
                payment.value,
                ByteArray(0),
                SafeOperation.CALL,
                UInt256.of(safeTxGas),
                UInt256.of(baseGas),
                UInt256.of(gasPrice),
                gasToken,
                Address.ZERO,
                nonce = 4,
            )
        val sign = SignSafeTx(world.signer, world.observeSafe, world.recovery, world.queue, Network.SEPOLIA)

        for (
            tx in listOf(
                withTerms(safeTxGas = 50_000), // a failing call would use up the nonce
                withTerms(gasPrice = 1), // its call gets no gas, and the Safe pays a refund all the same
                withTerms(baseGas = 1_000_000_000, gasPrice = 1), // a refund bounded by nothing but the Safe
                withTerms(safeTxGas = 50_000, gasPrice = 1, gasToken = world.recipient),
            )
        ) {
            val request = world.request(tx, world.carol)
            assertEquals(SafeTxRefusal.GasTerms, DecodedSafeTx.of(request).refusal, "said before anyone asks")
            assertEquals(SafeTxSigning.Refused(SafeTxRefusal.GasTerms), sign(request, world.alice.address, unlock))
            assertEquals(
                SafeApprovalQuoting.Refused(SafeTxRefusal.GasTerms),
                quoteApproval(request, world.alice.address, FeeLevel.NORMAL),
            )
            assertEquals(
                SafeExecutionQuoting.Refused(SafeTxRefusal.GasTerms),
                quoteExecution(request, world.alice.address, FeeLevel.NORMAL),
            )
        }
        assertEquals(emptyList(), world.inputs.simulated)
        assertEquals(null, DecodedSafeTx.of(world.request(payment)).refusal, "a plain call may be signed")
    }

    @Test
    fun anExecutionOrApprovalHoldsItsNonceAndItsCostLikeATransfer() = runTest {
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        val first = assertIs<SafeExecutionQuoting.Quoted>(
            quoteExecution(world.request(payment, world.carol), world.alice.address, FeeLevel.NORMAL),
        ).quote
        ExecuteSafeTx(world.signer, world.broadcaster, world.sent) { 1_800_000_000 }(first, unlock)

        world.inputs.simulation = Simulation.Succeeded("", 100)
        val next = world.request(SafeTx.call(world.safe, UInt256.ZERO, ByteArray(0), nonce = 5))
        val approval = assertIs<SafeApprovalQuoting.Quoted>(
            quoteApproval(next, world.alice.address, FeeLevel.NORMAL),
        ).quote

        assertEquals(8, approval.call.transaction.nonce, "after the execution not mined yet")
        assertEquals(first.call.maxCost, approval.call.pendingCost)
    }

    @Test
    fun anExecutionOrApprovalSentAndNotMinedIsNotSentAgain() = runTest {
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        val request = world.request(payment, world.carol)
        val first = assertIs<SafeExecutionQuoting.Quoted>(
            quoteExecution(request, world.alice.address, FeeLevel.NORMAL),
        ).quote
        ExecuteSafeTx(world.signer, world.broadcaster, world.sent) { 1_800_000_000 }(first, unlock)

        // At the head the Safe's nonce is still 4: the run passes, and the second would revert once mined.
        assertEquals(
            SafeExecutionQuoting.Refused(SafeTxRefusal.AlreadySent),
            quoteExecution(request, world.alice.address, FeeLevel.NORMAL),
        )
        assertEquals(
            SafeExecutionQuoting.Refused(SafeTxRefusal.AlreadySent),
            quoteExecution(request, world.stranger, FeeLevel.NORMAL),
            "from another account of this wallet just the same: the nonce is the Safe's",
        )
        // A block took it and it reverted (an owner swapped meanwhile): the Safe's nonce is still 4,
        // the sender's count has passed it, and a corrected execution is not held back.
        world.inputs.mined = 8
        assertIs<SafeExecutionQuoting.Quoted>(quoteExecution(request, world.alice.address, FeeLevel.NORMAL))

        world.inputs.simulation = Simulation.Succeeded("", 100)
        val later = world.request(SafeTx.call(world.safe, UInt256.ZERO, ByteArray(0), nonce = 5))
        val approval = assertIs<SafeApprovalQuoting.Quoted>(
            quoteApproval(later, world.alice.address, FeeLevel.NORMAL),
        ).quote
        ApproveSafeTxOnChain(world.signer, world.broadcaster, world.sent) { 1_800_000_000 }(approval, unlock)
        assertEquals(
            SafeApprovalQuoting.Refused(SafeTxRefusal.AlreadySent),
            quoteApproval(later, world.alice.address, FeeLevel.NORMAL),
        )
    }

    @Test
    fun anEarlierTransactionMaybeSentRefusesAnApprovalOrAnExecutionBeforeTheSafeIsRead() = runTest {
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        val maybe = SentTransfer(
            Network.SEPOLIA,
            world.alice.address,
            7,
            SignedTransfer(byteArrayOf(2, 7)),
            SentAction.SafeApproval(world.request(payment)),
            UInt256.ONE,
            sentAt = 1_800_000_000,
            maybeSent = true,
        )
        world.sent.transfers.value = listOf(maybe)
        val request = world.request(payment, world.carol)
        val refused = SafeTxRefusal.NotQuoted(CallRefusal.EarlierMaybeSent(maybe.hash))

        assertEquals(SafeApprovalQuoting.Refused(refused), quoteApproval(request, world.alice.address, FeeLevel.NORMAL))
        assertEquals(
            SafeExecutionQuoting.Refused(refused),
            quoteExecution(request, world.alice.address, FeeLevel.NORMAL),
        )
        assertEquals(emptyList(), world.approvals.asked, "the Safe's approvals not read")
        assertEquals(emptyList(), world.inputs.simulated, "nothing run")
        // Another account of this wallet is not held back by Alice's.
        val signed = world.request(payment, world.alice.address, world.carol)
        assertIs<SafeExecutionQuoting.Quoted>(quoteExecution(signed, world.stranger, FeeLevel.NORMAL))
    }
}
