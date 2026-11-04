package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

class ObserveAccountsTest {

    private val repository = FakeAccountRepository()
    private val observeAccounts = ObserveAccounts(repository)

    @Test
    fun emitsTheRepositoryStreamUnchanged() = runTest {
        val account = Account(
            Network.SEPOLIA,
            Address.of("0x" + "11".repeat(20)),
            AccountKind.EOA,
            "Savings",
            AccountCustody.InKeyStore,
        )
        repository.accounts.value = listOf(account)

        assertEquals(AccountList.Loaded(listOf(account)), observeAccounts().first())
    }

    private class FakeAccountRepository : AccountRepository {
        val accounts = MutableStateFlow<List<Account>>(emptyList())
        override fun observeAccounts(): Flow<AccountList> = accounts.map { AccountList.Loaded(it) }
        override suspend fun add(account: Account): AccountAdd = error("not used by this test")
        override suspend fun rename(id: AccountId, label: String): AccountChange = error("not used by this test")
        override suspend fun remove(id: AccountId): AccountChange = error("not used by this test")
        override suspend fun setCustody(id: AccountId, custody: AccountCustody): AccountChange =
            error("not used by this test")
    }
}
