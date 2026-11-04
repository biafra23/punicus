package eth.serotine.data.account

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class InMemoryAccountRepositoryTest {

    private val eoa = Account(
        Network.SEPOLIA,
        Address.of("0x" + "aa".repeat(20)),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )
    private val safe =
        Account(
            Network.SEPOLIA,
            Address.of("0x" + "bb".repeat(20)),
            AccountKind.SAFE,
            "Treasury",
            AccountCustody.InKeyStore,
        )

    @Test
    fun startsWithTheInitialAccountsAndEmitsAdditions() = runTest {
        val repository = InMemoryAccountRepository(listOf(eoa))
        assertEquals(AccountList.Loaded(listOf(eoa)), repository.observeAccounts().first())

        assertEquals(AccountAdd.Added, repository.add(safe))
        assertEquals(AccountList.Loaded(listOf(eoa, safe)), repository.observeAccounts().first())
    }

    @Test
    fun duplicateAddressesInTheInitialAccountsKeepTheFirstEntry() = runTest {
        val repository = InMemoryAccountRepository(listOf(eoa, eoa.copy(label = "Renamed"), safe))

        assertEquals(AccountList.Loaded(listOf(eoa, safe)), repository.observeAccounts().first())
    }

    @Test
    fun addingAnAddressTwiceKeepsTheFirstEntry() = runTest {
        val repository = InMemoryAccountRepository()
        assertEquals(AccountAdd.Added, repository.add(eoa))
        assertEquals(AccountAdd.AlreadyPresent, repository.add(eoa.copy(label = "Renamed")))

        assertEquals(AccountList.Loaded(listOf(eoa)), repository.observeAccounts().first())
    }

    @Test
    fun renameAndRemoveChangeOneEntry() = runTest {
        val repository = InMemoryAccountRepository(listOf(eoa, safe))

        assertEquals(AccountChange.Changed, repository.rename(eoa.id, "Spending"))
        assertEquals(AccountChange.Changed, repository.remove(safe.id))
        assertEquals(AccountList.Loaded(listOf(eoa.copy(label = "Spending"))), repository.observeAccounts().first())

        assertEquals(AccountChange.Changed, repository.setCustody(eoa.id, AccountCustody.Watched))
        val stored = (repository.observeAccounts().first() as AccountList.Loaded).accounts
        assertEquals(AccountCustody.Watched, stored[0].custody)
        assertEquals(AccountChange.NotFound, repository.rename(safe.id, "Treasury"))
        assertEquals(AccountChange.NotFound, repository.remove(safe.id))
    }

    @Test
    fun aRenameMakesTheNameTheUsers() = runTest {
        val byName = Account(
            Network.SEPOLIA,
            eoa.address,
            AccountKind.EOA,
            "daily.eth",
            AccountCustody.Watched,
            ensName = "daily.eth",
        )
        val repository = InMemoryAccountRepository(listOf(byName))

        assertEquals(AccountChange.Changed, repository.rename(eoa.id, "Daily"))
        assertEquals(
            AccountList.Loaded(listOf(byName.copy(label = "Daily", ensName = null))),
            repository.observeAccounts().first(),
        )
    }
}
