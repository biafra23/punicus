package eth.serotine.ui.renameaccount

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.RenameAccount
import eth.serotine.ui.FakeAccounts
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
class RenameAccountViewModelTest {

    private val daily = Account(
        Network.SEPOLIA,
        Address.of("0x" + "11".repeat(20)),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )
    private val accounts = FakeAccounts(daily)

    private fun viewModel(address: Address = daily.address) =
        RenameAccountViewModel(AccountId(Network.SEPOLIA, address), ObserveAccount(accounts), RenameAccount(accounts))

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun startsFromTheCurrentNameAndSavesTheTypedOne() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        assertEquals(RenameAccountUiState.Editing("Daily"), viewModel.uiState.value)

        viewModel.onNameChange("Spending")
        viewModel.save()

        assertEquals(RenameAccountUiState.Done, viewModel.uiState.value)
        assertEquals("Spending", accounts.accounts.value.single().label)
    }

    @Test
    fun aBlankNameIsRefusedUntilTheUserTypesAgain() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()

        viewModel.onNameChange("   ")
        viewModel.save()
        assertEquals(RenameAccountUiState.Editing("   ", error = "Enter a name"), viewModel.uiState.value)

        viewModel.onNameChange("S")
        assertEquals(RenameAccountUiState.Editing("S"), viewModel.uiState.value, "typing clears the error")
    }

    @Test
    fun aNameThatCannotBeSavedSaysSoAndKeepsWhatWasTyped() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        accounts.failSaves = true
        val viewModel = viewModel()

        viewModel.onNameChange("Spending")
        viewModel.save()

        assertEquals(
            RenameAccountUiState.Editing("Spending", error = "The name could not be saved. Try again."),
            viewModel.uiState.value,
        )
    }

    @Test
    fun anAccountNoLongerInTheWalletClosesTheDialog() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        assertEquals(RenameAccountUiState.Gone, viewModel(Address.of("0x" + "99".repeat(20))).uiState.value)
    }
}
