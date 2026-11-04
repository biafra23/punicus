package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * [AddOnNetworks], with Sepolia the only network there is yet: what it refuses, and that the
 * account's own network is never added twice. Adding on Ethereum and Gnosis Chain is tested with
 * the parts that bring them (docs/networks.md, *Delivery*).
 */
class AddOnNetworksTest {

    private val main =
        Account(Network.SEPOLIA, Address.of("0x" + "11".repeat(20)), AccountKind.EOA, "Main", AccountCustody.InKeyStore)
    private val safe =
        Account(Network.SEPOLIA, Address.of("0x" + "22".repeat(20)), AccountKind.SAFE, "Club", AccountCustody.Watched)
    private val accounts = ListAccountRepository(main, safe)
    private val add = AddOnNetworks(accounts, WalletChanges())

    @Test
    fun theAccountsOwnNetworkIsNotAddedAgain() = runTest {
        assertEquals(NetworksAdd.Added(emptyList()), add(main.id, listOf(Network.SEPOLIA)))
        assertEquals(listOf(main, safe), accounts.accounts.value)
    }

    @Test
    fun aSafeIsRefused() = runTest {
        assertEquals(NetworksAdd.NotAnEoa, add(safe.id, Network.entries))
    }

    @Test
    fun anAccountNotInTheListIsNotFound() = runTest {
        accounts.accounts.value = listOf(safe)
        assertEquals(NetworksAdd.NotFound, add(main.id, Network.entries))
    }

    @Test
    fun anUnreadableListAddsNothing() = runTest {
        accounts.unreadable = true
        assertEquals(NetworksAdd.NotSaved(emptyList()), add(main.id, Network.entries))
    }
}
