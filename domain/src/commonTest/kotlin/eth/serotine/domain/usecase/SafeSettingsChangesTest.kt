package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeSettingsCalls
import eth.serotine.domain.model.SafeSettingsChange
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class SafeSettingsChangesTest {

    private val world = SafeWorld()
    private val propose = ProposeSafeSettingsChange(world.observeSafe, world.inputs, world.queue, Network.SEPOLIA)
    private val dave = Address.of("0x" + "dd".repeat(20))

    private suspend fun proposed(change: SafeSettingsChange): SafeSettingsProposal =
        assertIs<SafeSettingsProposing.Proposed>(propose(world.safe, change)).proposal

    @Test
    fun aChangeIsTheSafesCallOnItselfAtItsNextNonceRunFromTheSafe() = runTest {
        val proposal = proposed(SafeSettingsChange.OwnerAdded(dave, 2))

        val tx = proposal.request.tx
        assertEquals(SafeTx.call(world.safe, UInt256.ZERO, SafeSettingsCalls.addOwner(dave, 2), 4), tx)
        assertEquals(SafeTxAction.SettingsChange(SafeSettingsChange.OwnerAdded(dave, 2)), proposal.tx.action)
        assertNull(proposal.tx.refusal)
        assertEquals(SafeCallSimulation.Ran(100), proposal.simulation)
        assertEquals(0, proposal.queuedAhead)
        // Run as the Safe calling itself: what lets its settings functions run.
        val (from, to, data) = world.inputs.simulated.single()
        assertEquals(world.safe to world.safe, from to to)
        assertEquals(tx.data.toHex(), data.toHex())
        // Nothing is queued before an owner signs it.
        assertEquals(emptyList(), world.queue.requests.value)
    }

    @Test
    fun theEntryBeforeAnOwnerOrModuleRemovedOrReplacedIsNamedFromTheVerifiedList() = runTest {
        val module = Address.of("0x" + "66".repeat(20))
        world.state = world.state.copy(modules = listOf(dave, module))

        assertEquals(
            SafeSettingsCalls.removeOwner(world.bob.address, world.carol, 1).toHex(),
            proposed(SafeSettingsChange.OwnerRemoved(world.carol, 1)).request.tx.data.toHex(),
        )
        assertEquals(
            SafeSettingsCalls.swapOwner(SafeSettingsCalls.SENTINEL, world.alice.address, dave).toHex(),
            proposed(SafeSettingsChange.OwnerSwapped(world.alice.address, dave)).request.tx.data.toHex(),
        )
        assertEquals(
            SafeSettingsCalls.disableModule(dave, module).toHex(),
            proposed(SafeSettingsChange.ModuleDisabled(module)).request.tx.data.toHex(),
        )
    }

    @Test
    fun whatTheSafeWouldRevertOnOrThatChangesNothingIsAProblemBeforeAnythingRuns() = runTest {
        suspend fun problem(change: SafeSettingsChange) =
            assertIs<SafeSettingsProposing.Problem>(propose(world.safe, change)).problem

        assertEquals(SafeSettingsProblem.AlreadyAnOwner(world.carol), problem(SafeSettingsChange.OwnerAdded(world.carol, 2)))
        assertEquals(SafeSettingsProblem.InvalidOwner(world.safe), problem(SafeSettingsChange.OwnerAdded(world.safe, 2)))
        assertEquals(SafeSettingsProblem.InvalidOwner(Address.ZERO), problem(SafeSettingsChange.OwnerSwapped(world.carol, Address.ZERO)))
        assertEquals(
            SafeSettingsProblem.InvalidOwner(SafeSettingsCalls.SENTINEL),
            problem(SafeSettingsChange.OwnerAdded(SafeSettingsCalls.SENTINEL, 1)),
        )
        assertEquals(SafeSettingsProblem.NotAnOwner(dave), problem(SafeSettingsChange.OwnerRemoved(dave, 1)))
        assertEquals(SafeSettingsProblem.NotAnOwner(dave), problem(SafeSettingsChange.OwnerSwapped(dave, world.stranger)))
        assertEquals(SafeSettingsProblem.ThresholdOutOfRange(4), problem(SafeSettingsChange.OwnerAdded(dave, 5)))
        assertEquals(SafeSettingsProblem.ThresholdOutOfRange(2), problem(SafeSettingsChange.OwnerRemoved(world.carol, 0)))
        assertEquals(SafeSettingsProblem.NoChange, problem(SafeSettingsChange.ThresholdChanged(2)))
        assertEquals(SafeSettingsProblem.NoSuchModule(dave), problem(SafeSettingsChange.ModuleDisabled(dave)))
        assertEquals(SafeSettingsProblem.NoChange, problem(SafeSettingsChange.GuardSet(null)))
        assertEquals(emptyList(), world.inputs.simulated)

        world.state = world.state.copy(owners = listOf(world.alice.address), threshold = 1)
        assertEquals(SafeSettingsProblem.LastOwner, problem(SafeSettingsChange.OwnerRemoved(world.alice.address, 1)))
    }

    @Test
    fun aChangeThatLeavesEveryOwnerNeededIsRefusedAsTheRulingSays() = runTest {
        // 2 of 3, one removed with the threshold kept: 2 of 2.
        assertEquals(
            SafeSettingsProposing.Problem(SafeSettingsProblem.NeedsRecoveryModule(2)),
            propose(world.safe, SafeSettingsChange.OwnerRemoved(world.carol, 2)),
        )
        assertEquals(
            SafeSettingsProposing.Problem(SafeSettingsProblem.NeedsRecoveryModule(3)),
            propose(world.safe, SafeSettingsChange.ThresholdChanged(3)),
        )
        // The same question the form asks as the user types.
        assertEquals(
            SafeSettingsProblem.NeedsRecoveryModule(4),
            SafeSettingsChange.OwnerAdded(dave, 4).problem(world.safe, world.state),
        )
        assertNull(SafeSettingsChange.OwnerRemoved(world.carol, 1).problem(world.safe, world.state))
    }

    @Test
    fun whatThisWalletSignsNoneOfIsRefusedAsItsSigningWould() = runTest {
        assertEquals(
            SafeSettingsProposing.Refused(SafeTxRefusal.ModuleEnabled(dave)),
            propose(world.safe, SafeSettingsChange.ModuleEnabled(dave)),
        )
        assertEquals(
            SafeSettingsProposing.Refused(SafeTxRefusal.UnpinnedFallbackHandler(dave)),
            propose(world.safe, SafeSettingsChange.FallbackHandlerSet(dave)),
        )
        assertEquals(emptyList(), world.inputs.simulated)
    }

    @Test
    fun aRevertAndAnUnverifiedSafeProposeNothing() = runTest {
        world.inputs.simulation = Simulation.Reverted(101)
        assertEquals(SafeSettingsProposing.Reverts(101), propose(world.safe, SafeSettingsChange.ThresholdChanged(1)))

        world.inputs.simulation = Simulation.Refused
        assertEquals(SafeCallSimulation.NotRun, proposed(SafeSettingsChange.ThresholdChanged(1)).simulation)

        world.kind.value = KindReading.Unavailable(ReadUnavailable.NO_ENGINE)
        assertEquals(
            SafeSettingsProposing.Refused(SafeTxRefusal.SafeUnavailable(ReadUnavailable.NO_ENGINE)),
            propose(world.safe, SafeSettingsChange.ThresholdChanged(1)),
        )
    }

    @Test
    fun aChangeQueuedBeforeItIsCountedForItsReview() = runTest {
        world.queue.save(world.request(SafeTx.call(world.safe, UInt256.ZERO, SafeSettingsCalls.changeThreshold(1), 4)))
        world.queue.save(world.request(SafeTx.call(world.recipient, UInt256.ONE, ByteArray(0), 5)))

        val proposal = proposed(SafeSettingsChange.OwnerAdded(dave, 2))

        assertEquals(6, proposal.request.nonce)
        assertEquals(2, proposal.queuedAhead)
        assertEquals(1, proposal.changesQueuedAhead)
    }
}
