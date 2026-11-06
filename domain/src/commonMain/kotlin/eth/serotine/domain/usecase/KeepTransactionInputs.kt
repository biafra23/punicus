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

/**
 * Keeps what a lenient draft is built on recent (docs/lenient-mode.md, *What a draft is built
 * on*): while the policy is [SyncPolicy.LENIENT], each of [networks] whose light client serves
 * verified reads ([LightClientStatus.Synced]) has its fee quote and the mined count of each EOA
 * whose key is here read, at once and every [interval] while it stays so. The reads are
 * [TransactionInputs]' own, verified; :data keeps each answer ([KeptTransactionInputs]), so this
 * use case only asks. In strict mode it asks nothing: a quote keeps what it reads anyway.
 *
 * Run for as long as the app is open (the hosts start it, never `App`). Runs until cancelled; a
 * port that breaks its promise not to throw costs that round only.
 */
class KeepTransactionInputs(
    private val policy: SyncPolicySetting,
    private val accounts: AccountRepository,
    private val lightClient: LightClient,
    private val inputs: TransactionInputs,
    private val networks: List<Network> = Network.entries,
    private val interval: Duration = 10.minutes,
) {
    /** Runs until cancelled. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend operator fun invoke() {
        policy.observe().distinctUntilChanged().collectLatest { chosen ->
            if (chosen != SyncPolicy.LENIENT) return@collectLatest
            coroutineScope { networks.forEach { network -> launch { follow(network) } } }
        }
    }

    /** Reads [network]'s inputs whenever its light client becomes able to verify, and every [interval] while it is. */
    private suspend fun follow(network: Network) {
        lightClient.observeStatus(network)
            .map { it is LightClientStatus.Synced }
            .distinctUntilChanged()
            .collectLatest { synced ->
                if (!synced) return@collectLatest
                while (true) {
                    read(network)
                    delay(interval)
                }
            }
    }

    private suspend fun read(network: Network) {
        try {
            inputs.fees(network)
            val held = (accounts.observeAccounts().first() as? AccountList.Loaded)?.accounts.orEmpty()
                .filter { it.network == network && it.kind == AccountKind.EOA && it.custody == AccountCustody.InKeyStore }
            held.forEach { inputs.minedNonce(network, it.address) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The ports promise not to throw; the next round asks again.
        }
    }
}
