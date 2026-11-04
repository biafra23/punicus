package eth.serotine.ui.removeaccount

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.RemoveAccount
import eth.serotine.domain.usecase.WalletChanges
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeKeys
import eth.serotine.ui.components.Eip55
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class RemoveAccountViewModelTest {

    private val daily = Account(
        Network.SEPOLIA,
        Address.of("0x" + "11".repeat(20)),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )
    private val savings =
        Account(Network.SEPOLIA, Address.of("0x" + "22".repeat(20)), AccountKind.EOA, "Savings", AccountCustody.Watched)
    private val accounts = FakeAccounts(daily, savings)
    private val keys = FakeKeys()

    private fun viewModel(address: Address) =
        RemoveAccountViewModel(
            AccountId(Network.SEPOLIA, address),
            ObserveAccount(accounts),
            ObserveAccounts(accounts),
            RemoveAccount(accounts, keys, WalletChanges()),
        )

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun asksFirstSayingWhetherAKeyGoesWithTheAccount() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        assertEquals(
            RemoveAccountUiState.Confirm("Daily", Eip55.format(daily.address), "Sepolia", keyed = true),
            viewModel(daily.address).uiState.value,
        )
        assertEquals(
            RemoveAccountUiState.Confirm("Savings", Eip55.format(savings.address), "Sepolia", keyed = false),
            viewModel(savings.address).uiState.value,
        )
        assertEquals(emptyList(), keys.deleted, "asking removes nothing")
    }

    @Test
    fun removingTakesTheAccountAndItsKey() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel(daily.address)

        viewModel.remove()

        assertEquals(RemoveAccountUiState.Done, viewModel.uiState.value)
        assertEquals(listOf(savings), accounts.accounts.value)
        assertEquals(listOf(daily.address), keys.deleted)
    }

    @Test
    fun aKeyThatStaysIsSaid() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        keys.failDeletes = true
        val viewModel = viewModel(daily.address)

        viewModel.remove()

        assertEquals(
            RemoveAccountUiState.Notice(
                "Daily is removed, but its key could not be deleted from this device, so " +
                    "the wallet password stays the one you set. Importing its recovery phrase again " +
                    "brings the account back.",
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun aRemovalThatCannotBeSavedChangesNothingAndSaysSo() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        accounts.failSaves = true
        val viewModel = viewModel(daily.address)

        viewModel.remove()

        assertEquals(
            RemoveAccountUiState.Notice("Daily could not be removed. Nothing has changed."),
            viewModel.uiState.value,
        )
        assertEquals(emptyList(), keys.deleted)
    }
}
