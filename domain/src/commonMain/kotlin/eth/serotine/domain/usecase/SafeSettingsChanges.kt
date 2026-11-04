package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeSettingsCalls
import eth.serotine.domain.model.SafeSettingsChange
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.SafeTxQueue
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TransactionInputs
import kotlinx.coroutines.flow.first

/**
 * Why [this] change cannot be made to the Safe at [safe], now as [state] says (docs/wallet-ux.md,
 * screen 28), or null when it can: what the Safe's own function would revert on, a change that
 * changes nothing, and the threshold the owners' ruling refuses ([SafeSettingsProblem.NeedsRecoveryModule];
 * 2026-10-18, #54). The form asks it as the user types, and [ProposeSafeSettingsChange] again
 * against the state it verifies. What this wallet signs none of whatever the Safe says (a module
 * enabled, a fallback handler not pinned) is its signing's refusal, [SigningRequest.unsignable].
 */
fun SafeSettingsChange.problem(safe: Address, state: SafeState): SafeSettingsProblem? {
    val owners = state.owners
    fun newOwner(owner: Address): SafeSettingsProblem? = when {
        owner == Address.ZERO || owner == SafeSettingsCalls.SENTINEL || owner == safe ->
            SafeSettingsProblem.InvalidOwner(owner)
        owner in owners -> SafeSettingsProblem.AlreadyAnOwner(owner)
        else -> null
    }
    fun threshold(threshold: Long, max: Int): SafeSettingsProblem? =
        SafeSettingsProblem.ThresholdOutOfRange(max).takeIf { threshold !in 1..max.toLong() }

    val problem = when (this) {
        is SafeSettingsChange.OwnerAdded -> newOwner(owner) ?: threshold(threshold, owners.size + 1)
        is SafeSettingsChange.OwnerRemoved -> when {
            owner !in owners -> SafeSettingsProblem.NotAnOwner(owner)
            owners.size == 1 -> SafeSettingsProblem.LastOwner
            else -> threshold(threshold, owners.size - 1)
        }
        is SafeSettingsChange.OwnerSwapped ->
            if (old !in owners) SafeSettingsProblem.NotAnOwner(old) else newOwner(new)
        is SafeSettingsChange.ThresholdChanged -> threshold(threshold, owners.size)
            ?: SafeSettingsProblem.NoChange.takeIf { threshold == state.threshold.toLong() }
        is SafeSettingsChange.ModuleEnabled ->
            SafeSettingsProblem.AlreadyEnabled(module).takeIf { module in state.modules }
        is SafeSettingsChange.ModuleDisabled -> SafeSettingsProblem.NoSuchModule(module).takeIf { module !in state.modules }
        is SafeSettingsChange.GuardSet -> SafeSettingsProblem.NoChange.takeIf { guard == state.guard }
        // The handler is not read, so whether it changes is not known here.
        is SafeSettingsChange.FallbackHandlerSet -> null
    }
    return problem ?: withoutRecovery(owners.size)?.let { SafeSettingsProblem.NeedsRecoveryModule(it.owners) }
}

/**
 * The call data of [this] change for a Safe in [state]: the Safe's own function, the entry before
 * the one removed or replaced named from its list ([SafeSettingsCalls.previous]); null when that
 * entry is not in it, which [problem] says first, and for a module enabled, which is refused
 * before it would be built.
 */
internal fun SafeSettingsChange.callData(state: SafeState): ByteArray? {
    fun previous(list: List<Address>, entry: Address) = SafeSettingsCalls.previous(list, entry)
    return when (this) {
        is SafeSettingsChange.OwnerAdded -> SafeSettingsCalls.addOwner(owner, threshold)
        is SafeSettingsChange.OwnerRemoved ->
            SafeSettingsCalls.removeOwner(previous(state.owners, owner) ?: return null, owner, threshold)
        is SafeSettingsChange.OwnerSwapped ->
            SafeSettingsCalls.swapOwner(previous(state.owners, old) ?: return null, old, new)
        is SafeSettingsChange.ThresholdChanged -> SafeSettingsCalls.changeThreshold(threshold)
        // Refused before anything is built: never encoded here.
        is SafeSettingsChange.ModuleEnabled -> null
        is SafeSettingsChange.ModuleDisabled ->
            SafeSettingsCalls.disableModule(previous(state.modules, module) ?: return null, module)
        is SafeSettingsChange.GuardSet -> SafeSettingsCalls.setGuard(guard)
        is SafeSettingsChange.FallbackHandlerSet -> SafeSettingsCalls.setFallbackHandler(handler)
    }
}

/** Why a change of a Safe's settings cannot be made to it now ([problem]). */
sealed interface SafeSettingsProblem {

    /**
     * [address] cannot be an owner: the zero address, which nobody holds the key of, the Safe's
     * list sentinel `0x1`, or the Safe itself. The Safe reverts on each.
     */
    data class InvalidOwner(val address: Address) : SafeSettingsProblem

    /** [address] is an owner already. */
    data class AlreadyAnOwner(val address: Address) : SafeSettingsProblem

    /** [address] is not one of the Safe's owners. */
    data class NotAnOwner(val address: Address) : SafeSettingsProblem

    /** The Safe's one owner cannot be removed: replace it instead. */
    data object LastOwner : SafeSettingsProblem

    /** The threshold must be from 1 to [max], the owners the Safe has after the change. */
    data class ThresholdOutOfRange(val max: Int) : SafeSettingsProblem

    /** It would leave the setting as it is. */
    data object NoChange : SafeSettingsProblem

    /** [module] is enabled already. */
    data class AlreadyEnabled(val module: Address) : SafeSettingsProblem

    /** [module] is not one of the Safe's modules. */
    data class NoSuchModule(val module: Address) : SafeSettingsProblem

    /**
     * It would leave every one of the Safe's [owners] owners needed, more than one: refused
     * without a recovery module ([SafeTxRefusal.NeedsRecoveryModule], the owners' ruling,
     * 2026-10-18, #54).
     */
    data class NeedsRecoveryModule(val owners: Int) : SafeSettingsProblem
}

/**
 * Proposes a change of a Safe's own settings (docs/wallet-ux.md, screen 28): [SafeSettingsChange]
 * as a call of the Safe on itself, with no ether, as a plain call with no refund ([SafeTx.call])
 * at the Safe's next nonce, as [ProposeSafeTx] finds it. Nothing is proposed on less than the
 * Safe's verified state ([ObserveSafe]): the change is checked against it ([problem]), and the
 * entry before an owner or module removed or replaced is named from its lists as verified. What
 * this wallet signs none of ([SigningRequest.unsignable]: a module enabled, a fallback handler not
 * pinned) is refused here too.
 *
 * Simulated where the engine allows: the call is run as a verified call from the Safe to itself at
 * the head, which is what lets the Safe's settings functions run, and a revert stops it here. The
 * Safe's own checks of the owners' signatures and nonce and its guard run when its execution is
 * quoted. A change queued before it on this device, which may change the owners or modules this
 * one names its neighbours from, is counted ([SafeSettingsProposal.changesQueuedAhead]): this one is
 * built against the lists as they are now.
 *
 * Nothing is saved: signing it ([SignSafeTx]) queues it. On [network] only.
 */
class ProposeSafeSettingsChange(
    private val observeSafe: ObserveSafe,
    private val inputs: TransactionInputs,
    private val queue: SafeTxQueue,
    private val network: Network,
) {
    /**
     * Proposes [change] of [safe]'s settings. [safeChecked] is called once the Safe is verified
     * ([ObserveSafe.check], which may wait out an unproven reading), before its queue and the run
     * are read: where a review measures the proposal's age from.
     */
    suspend operator fun invoke(
        safe: Address,
        change: SafeSettingsChange,
        safeChecked: () -> Unit = {},
    ): SafeSettingsProposing = cancellingReads {
        val reading = when (val check = observeSafe.check(AccountId(network, safe))) {
            is SafeCheck.Verified -> check.reading
            is SafeCheck.Refused -> return@cancellingReads SafeSettingsProposing.Refused(check.refusal)
        }
        safeChecked()
        val state = reading.state
        if (change is SafeSettingsChange.ModuleEnabled) {
            return@cancellingReads SafeSettingsProposing.Refused(SafeTxRefusal.ModuleEnabled(change.module))
        }
        change.problem(safe, state)?.let { return@cancellingReads SafeSettingsProposing.Problem(it) }
        // problem() has found the entry removed or replaced in its list: there is a neighbour to name.
        val data = checkNotNull(change.callData(state)) { "an entry problem() found is in its list" }
        val pending = pendingOf(queue.observe(AccountId(network, safe)).first(), state.nonce, network)
        val nonce = nextNonce(pending, state.nonce)
        val request = SigningRequest.of(network, safe, SafeTx.call(safe, UInt256.ZERO, data, nonce))
        request.unsignable()?.let { return@cancellingReads SafeSettingsProposing.Refused(it) }
        val simulation = when (val run = inputs.simulate(network, safe, safe, UInt256.ZERO, data)) {
            is Simulation.Succeeded -> SafeCallSimulation.Ran(run.blockNumber)
            is Simulation.Reverted -> return@cancellingReads SafeSettingsProposing.Reverts(run.blockNumber)
            Simulation.Refused -> SafeCallSimulation.NotRun
            is Simulation.Unavailable ->
                return@cancellingReads SafeSettingsProposing.Refused(SafeTxRefusal.Unavailable(run.reason))
        }
        queue.prune(AccountId(network, safe), state.nonce)
        val ahead = pending.filter { it.nonce < nonce }
        SafeSettingsProposing.Proposed(
            SafeSettingsProposal(
                tx = DecodedSafeTx.of(request),
                safe = reading,
                queuedAhead = (nonce - state.nonce).toInt(),
                changesQueuedAhead = ahead
                    .filter { SafeTxAction.of(it.safe, it.tx) is SafeTxAction.SettingsChange }
                    .distinctBy { it.nonce }
                    .size,
                simulation = simulation,
            ),
        )
    }
}

/**
 * A change of a Safe's settings proposed for review: [tx] at the Safe's next nonce, after
 * [queuedAhead] nonces queued before it, of which [changesQueuedAhead] hold a change of its
 * settings; the Safe's verified state ([safe]) it was built against; and whether the call was run
 * as a verified call from the Safe ([simulation]). Only [ProposeSafeSettingsChange] makes one.
 */
@ConsistentCopyVisibility
data class SafeSettingsProposal internal constructor(
    val tx: DecodedSafeTx,
    val safe: SafeReading.Verified,
    val queuedAhead: Int,
    val changesQueuedAhead: Int,
    val simulation: SafeCallSimulation,
) {
    /** The request its owners sign. */
    val request: SigningRequest
        get() = tx.request
}

/** The outcome of [ProposeSafeSettingsChange]. */
sealed interface SafeSettingsProposing {

    /** [proposal] is ready for review. */
    data class Proposed(val proposal: SafeSettingsProposal) : SafeSettingsProposing

    /** The change cannot be made to the Safe as verified now: [problem]. */
    data class Problem(val problem: SafeSettingsProblem) : SafeSettingsProposing

    /** The Safe's call reverts, run as a verified call from the Safe at [blockNumber]. */
    data class Reverts(val blockNumber: Long?) : SafeSettingsProposing

    /**
     * Not proposed: the Safe is none, or its state cannot be verified now, or this wallet signs
     * none of it ([refusal]).
     */
    data class Refused(val refusal: SafeTxRefusal) : SafeSettingsProposing
}
