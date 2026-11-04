package eth.serotine.ui.deriveaccount

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.MemoryStep
import eth.serotine.domain.usecase.DeriveAccount
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.WalletChanges
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeKeys
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.STEPPED_DOWN_TEXT
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class DeriveAccountViewModelTest {

    private val daily = Account(
        Network.ETHEREUM,
        Address.of("0x" + "11".repeat(20)),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )
    private val savings = Account(
        Network.ETHEREUM,
        Address.of("0x" + "22".repeat(20)),
        AccountKind.EOA,
        "Savings",
        AccountCustody.Watched,
    )
    private val next = Address.of("0x" + "33".repeat(20))
    private val accounts = FakeAccounts(daily, savings)
    private val keys = FakeKeys()

    private fun viewModel(account: Account = daily) = DeriveAccountViewModel(
        account.id,
        ObserveAccount(accounts),
        DeriveAccount(keys, accounts, WalletChanges()),
    )

    private val ask = DeriveAccountUiState.Ask("Daily", Eip55.format(daily.address), "Ethereum")

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun asksForThePasswordOfAnAccountHeldHere() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()

        assertEquals(ask, viewModel.uiState.value)
        assertEquals(false, ask.canDerive, "nothing to add with until a password is typed")
        viewModel.derive()
        assertEquals(emptyList(), keys.derivedWith)
    }

    @Test
    fun aWatchedAccountOrOneGoneOffersNothing() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        val watched = viewModel(savings).uiState.value
        assertIs<DeriveAccountUiState.Unavailable>(watched)
        assertEquals(true, "Import the phrase" in watched.message)
        assertEquals(DeriveAccountUiState.Gone, viewModel(daily.copy(network = Network.SEPOLIA)).uiState.value)
    }

    @Test
    fun theNextAccountIsAddedAndShown() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        keys.deriveGate = gate
        keys.derivation = KeyDerivation.Stored(next, 1, KeyProtection.HARDWARE)
        val viewModel = viewModel()

        viewModel.onPasswordChange("correct horse battery")
        viewModel.derive()
        // The password left the state as the derivation started.
        assertEquals(ask.copy(deriving = true), viewModel.uiState.value)
        gate.complete(Unit)

        assertEquals(listOf("correct horse battery"), keys.derivedWith)
        assertEquals(
            DeriveAccountUiState.Added("Account 3", Eip55.format(next), "Ethereum", number = 2),
            viewModel.uiState.value,
        )
        assertEquals(next, accounts.accounts.value.last().address)
    }

    @Test
    fun aSealThatSteppedDownIsSaid() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        keys.derivation = KeyDerivation.Stored(next, 1, KeyProtection.PASSWORD_ONLY, steppedDown = true)
        val viewModel = viewModel()

        viewModel.onPasswordChange("correct horse battery")
        viewModel.derive()

        assertEquals(STEPPED_DOWN_TEXT, assertIs<DeriveAccountUiState.Added>(viewModel.uiState.value).note)
    }

    @Test
    fun aRefusalIsSaidAndThePasswordAskedAgain() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()

        for (refusal in listOf(KeyDerivation.WrongPassword, KeyDerivation.NotEnoughMemory(MemoryStep.PROTECT))) {
            keys.derivation = refusal
            viewModel.onPasswordChange("not the password")
            viewModel.derive()
            val state = assertIs<DeriveAccountUiState.Ask>(viewModel.uiState.value)
            assertEquals("", state.password.text)
            assertEquals(false, state.deriving)
            assertEquals(true, state.error != null)
        }
        assertEquals(listOf(daily, savings), accounts.accounts.value, "nothing added")
    }

    @Test
    fun aPasswordTypedGoesWhenTheScreenIsHidden() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()

        viewModel.onPasswordChange("correct horse battery")
        viewModel.onHidden()

        assertEquals(ask, viewModel.uiState.value)
    }
}
