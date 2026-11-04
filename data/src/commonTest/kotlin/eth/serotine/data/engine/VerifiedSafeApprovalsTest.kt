package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.ApprovalReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedSafeApprovalsTest {

    private val chain = FakeChain()
    private val safe = Address.of("0x" + "5a".repeat(20))
    private val owner = Address.of("0x" + "11".repeat(20))
    private val hash = Digest.of("0x" + "ab".repeat(32))

    private fun TestScope.approvals() = VerifiedSafeApprovals(
        Network.SEPOLIA,
        chain,
        StandardTestDispatcher(testScheduler),
    )

    private fun answer(outcome: CallOutcome) {
        chain.calls[safe to Abi.approvedHashes(owner, hash).toHex()] = outcome
    }

    @Test
    fun theCallIsTheSafesOwnGetter() {
        // cast calldata "approvedHashes(address,bytes32)" 0x1111…1111 0xabab…abab
        assertEquals(
            "7d832974" + "0".repeat(24) + "11".repeat(20) + "ab".repeat(32),
            Abi.approvedHashes(owner, hash).toHex(),
        )
    }

    @Test
    fun aWordOtherThanZeroIsAnApprovalAtItsBlock() = runTest {
        chain.snapshot = running()
        answer(CallOutcome.Returned("0".repeat(63) + "1", 120))
        assertEquals(
            ApprovalReading.Read(approved = true, blockNumber = 120),
            approvals().approval(Network.SEPOLIA, safe, owner, hash),
        )

        answer(CallOutcome.Returned("0".repeat(64), null))
        assertEquals(
            ApprovalReading.Read(approved = false, null),
            approvals().approval(Network.SEPOLIA, safe, owner, hash),
        )
    }

    @Test
    fun anythingButOneWordIsNoAnswer() = runTest {
        chain.snapshot = running()
        answer(CallOutcome.Returned("", 120))
        assertEquals(
            ApprovalReading.Unavailable(ReadUnavailable.UNPROVEN),
            approvals().approval(Network.SEPOLIA, safe, owner, hash),
        )
        answer(CallOutcome.Reverted(120))
        assertEquals(
            ApprovalReading.Unavailable(ReadUnavailable.UNPROVEN),
            approvals().approval(Network.SEPOLIA, safe, owner, hash),
        )
        answer(CallOutcome.Refused)
        assertEquals(
            ApprovalReading.Unavailable(ReadUnavailable.REFUSED),
            approvals().approval(Network.SEPOLIA, safe, owner, hash),
        )
    }

    @Test
    fun nothingIsAskedWhileReadsCannotBeVerified() = runTest {
        answer(CallOutcome.Returned("0".repeat(63) + "1", 120))
        chain.snapshot = running(phase = EngineSnapshot.Phase.SYNCING)
        assertEquals(
            ApprovalReading.Unavailable(ReadUnavailable.SYNCING),
            approvals().approval(Network.SEPOLIA, safe, owner, hash),
        )

        chain.snapshot = EngineSnapshot.Down
        assertEquals(
            ApprovalReading.Unavailable(ReadUnavailable.NO_ENGINE),
            approvals().approval(Network.SEPOLIA, safe, owner, hash),
        )

        chain.snapshot = running()
        chain.failure = IllegalStateException("the engine failed")
        assertEquals(
            ApprovalReading.Unavailable(ReadUnavailable.UNPROVEN),
            approvals().approval(Network.SEPOLIA, safe, owner, hash),
        )
        assertEquals(
            ApprovalReading.Unavailable(ReadUnavailable.NO_ENGINE),
            VerifiedSafeApprovals(
                Network.SEPOLIA,
                null,
                StandardTestDispatcher(testScheduler),
            ).approval(Network.SEPOLIA, safe, owner, hash),
        )
    }
}
