package eth.serotine.ui.components

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.FreshCheckpoint
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a fetch of a fresh checkpoint stands for one network, while its light client is parked. */
sealed interface CheckpointFetchState {

    /** The checkpoint servers and the long-known peers are being asked. */
    data object Fetching : CheckpointFetchState

    /** One was applied: nothing more is offered until the light client stops refusing, a moment later. */
    data object Applied : CheckpointFetchState

    /** The last fetch changed nothing, for [reason]; [unconfirmed]: no long-known peer could confirm it. */
    data class Refused(val reason: String, val unconfirmed: Boolean) : CheckpointFetchState
}

/**
 * The fetches of fresh checkpoints a screen's ViewModel asked for (owner's request, 2026-11-14), one
 * per network at a time, run in [scope] (the ViewModel's). [states] is what each stale-checkpoint
 * warning draws: a network is absent until asked, and again once its light client no longer refuses
 * ([settled]), so a refusal, and its offer to use a checkpoint no long-known peer confirmed, is never
 * carried into a later park. An applied one is told to [onApplied] with its network, in words that
 * name who vouched for it, since that checkpoint is their word. A failure of the call is a refusal,
 * said as such.
 */
class FreshCheckpointFetches(
    private val scope: CoroutineScope,
    private val fetch: suspend (Network, useUnconfirmed: Boolean) -> FreshCheckpoint,
    private val onApplied: (Network, String) -> Unit,
) {
    private val fetches = MutableStateFlow<Map<Network, CheckpointFetchState>>(emptyMap())

    /** Each network's fetch, while one is running or after it was refused. */
    val states: StateFlow<Map<Network, CheckpointFetchState>> = fetches.asStateFlow()

    /** Asks for [network]'s fresh checkpoint; [useUnconfirmed] is the user's "Use it anyway". One at a time. */
    fun fetch(network: Network, useUnconfirmed: Boolean) {
        val current = fetches.value[network]
        if (current == CheckpointFetchState.Fetching || current == CheckpointFetchState.Applied) return
        fetches.update { it + (network to CheckpointFetchState.Fetching) }
        scope.launch {
            val outcome = try {
                fetch(network, useUnconfirmed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FreshCheckpoint.Refused("the checkpoint servers could not be asked (${e.message ?: e})")
            }
            when (outcome) {
                is FreshCheckpoint.Applied -> {
                    fetches.update { it + (network to CheckpointFetchState.Applied) }
                    onApplied(network, applied(network, outcome))
                }
                is FreshCheckpoint.Refused -> {
                    val refused = CheckpointFetchState.Refused(outcome.reason, outcome.unconfirmed)
                    fetches.update { it + (network to refused) }
                }
                FreshCheckpoint.NotRefused -> fetches.update { it - network }
            }
        }
    }

    /**
     * [network]'s light client no longer refuses its checkpoint: what was said about a fetch for it
     * goes. A fetch still running stays, and says its outcome when it ends.
     */
    fun settled(network: Network) {
        if (fetches.value[network].let { it != null && it != CheckpointFetchState.Fetching }) {
            fetches.update { it - network }
        }
    }

    companion object {
        /** What the user is told of [outcome]: where [network] starts over, and on whose word. */
        fun applied(network: Network, outcome: FreshCheckpoint.Applied): String {
            val servers = "every checkpoint server that answered named it (${outcome.operators.joinToString()})"
            val peers = when (outcome.confirmedBy.size) {
                0 -> "no peer Punicus has long known confirmed it; you chose to use it"
                1 -> "1 peer Punicus has long known confirmed it"
                else -> "${outcome.confirmedBy.size} peers Punicus has long known confirmed it"
            }
            return "${network.displayName} starts over from the checkpoint at epoch ${outcome.epoch}: $servers, " +
                "and $peers."
        }
    }
}
