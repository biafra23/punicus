package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Adds an externally owned account on other networks (docs/networks.md, N5 and N6): its address
 * is the same account on every EVM chain, so each network chosen gets an entry of its own, under
 * the same name and custody. A key held here is the address's, so it signs there too and nothing
 * is sealed or signed; a Safe or another contract exists only where it was deployed and is never
 * added this way. The entry added has no ENS name to follow: a name was verified on its own
 * network only (docs/networks.md, *Names*).
 *
 * It runs under the app's [WalletChanges], as a removal does: a key-store entry is never added
 * while the last one holding its key is being removed, which would leave it without its key.
 */
class AddOnNetworks(
    private val accounts: AccountRepository,
    /** The app's one [WalletChanges]: an add never interleaves with a removal or an import. */
    private val changes: WalletChanges,
) {
    /** Adds [account] on each of [networks] it is not on yet. */
    suspend operator fun invoke(account: AccountId, networks: List<Network>): NetworksAdd =
        withContext(NonCancellable) { changes.serialized { add(account, networks) } }

    private suspend fun add(id: AccountId, networks: List<Network>): NetworksAdd {
        val listed = when (val list = accounts.observeAccounts().first()) {
            is AccountList.Unreadable -> return NetworksAdd.NotSaved(emptyList())
            is AccountList.Loaded -> list.accounts
        }
        val account = listed.firstOrNull { it.id == id } ?: return NetworksAdd.NotFound
        if (account.kind != AccountKind.EOA) return NetworksAdd.NotAnEoa
        val added = mutableListOf<Network>()
        for (network in networks.distinct().filter { it != id.network }) {
            when (accounts.add(account.copy(network = network, ensName = null))) {
                AccountAdd.Added -> added += network
                AccountAdd.AlreadyPresent -> Unit
                AccountAdd.NotSaved -> return NetworksAdd.NotSaved(added)
            }
        }
        return NetworksAdd.Added(added)
    }
}

/** The outcome of [AddOnNetworks]. */
sealed interface NetworksAdd {
    /** The account is on each network chosen now; [networks] are those it was added on (not there before). */
    data class Added(val networks: List<Network>) : NetworksAdd

    /** The list could not be stored, or cannot be read; it was added on [networks] before that. */
    data class NotSaved(val networks: List<Network>) : NetworksAdd

    /** The account is not in the wallet (any more). */
    data object NotFound : NetworksAdd

    /** The account is a Safe or another contract, which exists only where it was deployed. */
    data object NotAnEoa : NetworksAdd
}
