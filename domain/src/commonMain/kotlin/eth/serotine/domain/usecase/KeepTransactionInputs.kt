package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SyncPolicy
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.KeptTransactionInputs
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.SyncPolicySetting
import eth.serotine.domain.repository.TransactionInputs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Keeps what a lenient draft is built on recent (docs/lenient-mode.md, *What a draft is built
 * on*): while the policy is [SyncPolicy.LENIENT], each of [networks] whose light client serves
 * verified reads ([LightClientStatus.Synced]) and holds an EOA whose key is here has its fee
 * quote and the mined count of each such EOA read, at most once every [interval] (measured on
 * [timeSource]): at once when it first verifies, then every [interval] while it does. A light
 * client that stops verifying and starts again within the interval waits out the rest of it, so a
 * flapping one costs no more. The reads are [TransactionInputs]' own, verified; :data keeps each
 * answer ([KeptTransactionInputs]), so this use case only asks. In strict mode it asks nothing:
 * a quote keeps what it reads anyway.
 *
 * Run while the hosts run their background work (never `App`): the app in front, and on Android
 * any hold of the engine. Runs until cancelled; a port that breaks its promise not to throw costs
 * that round only.
 */
class KeepTransactionInputs(
    private val policy: SyncPolicySetting,
    private val accounts: AccountRepository,
    private val lightClient: LightClient,
    private val inputs: TransactionInputs,
    private val networks: List<Network> = Network.entries,
    private val interval: Duration = 10.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** Runs until cancelled. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend operator fun invoke() {
        policy.observe().distinctUntilChanged().collectLatest { chosen ->
            if (chosen != SyncPolicy.LENIENT) return@collectLatest
            coroutineScope { networks.forEach { network -> launch { follow(network) } } }
        }
    }

    /**
     * Reads [network]'s inputs while its light client verifies, at most once every [interval]: a
     * round run less than [interval] ago, before it stopped verifying, is waited out first.
     */
    private suspend fun follow(network: Network) {
        var lastRound: TimeMark? = null
        lightClient.observeStatus(network)
            .map { it is LightClientStatus.Synced }
            .distinctUntilChanged()
            .collectLatest { synced ->
                if (!synced) return@collectLatest
                lastRound?.let { mark -> (interval - mark.elapsedNow()).takeIf { it.isPositive() }?.let { delay(it) } }
                while (true) {
                    lastRound = timeSource.markNow()
                    read(network)
                    delay(interval)
                }
            }
    }

    private suspend fun read(network: Network) {
        try {
            val held = (accounts.observeAccounts().first() as? AccountList.Loaded)?.accounts.orEmpty()
                .filter { it.network == network && it.kind == AccountKind.EOA && it.custody == AccountCustody.InKeyStore }
            // A network with no account to draft from has nothing to keep.
            if (held.isEmpty()) return
            inputs.fees(network)
            held.forEach { inputs.minedNonce(network, it.address) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The ports promise not to throw; the next round asks again.
        }
    }
}
