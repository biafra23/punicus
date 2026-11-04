package eth.serotine.ui.currency

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.CurrencyChoice
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.SetDisplayCurrency
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.MemoryCurrencySetting
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class CurrencyViewModelTest {

    private val feeds = FakeFeeds()
    private val setting = MemoryCurrencySetting()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): CurrencyViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = CurrencyViewModel(
            ObserveDisplayCurrencies(feeds, setting, FakeAccounts()),
            SetDisplayCurrency(feeds, setting, FakeAccounts()),
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun theCurrenciesWithAFeedAreOfferedAndAChoiceEndsTheScreen() = runTest {
        val viewModel = viewModel()
        runCurrent()
        // The test feeds price ETH, USDC, EUR and JPY: GBP, CHF and BTC are not offered.
        assertEquals(
            listOf(
                CurrencyOption("USD", "US dollar", chosen = true),
                CurrencyOption("EUR", "Euro", chosen = false),
                CurrencyOption("JPY", "Japanese yen", chosen = false),
                CurrencyOption("ETH", "Ether", chosen = false),
            ),
            viewModel.uiState.value.options,
        )

        viewModel.choose("EUR")
        runCurrent()
        assertTrue(viewModel.uiState.value.done)
        assertEquals(CurrencyChoice.Chosen(DisplayCurrency.EUR), setting.choice.value)
    }

    @Test
    fun aChoiceThatCannotBeSavedOrReadIsSaid() = runTest {
        val viewModel = viewModel()
        setting.failSaves = true
        viewModel.choose("JPY")
        runCurrent()
        assertEquals("The choice could not be saved. Nothing has changed.", viewModel.uiState.value.notice)
        assertFalse(viewModel.uiState.value.done)

        setting.choice.value = CurrencyChoice.Unreadable
        runCurrent()
        assertFalse(viewModel.uiState.value.canChoose)
        assertEquals(true, viewModel.uiState.value.notice?.startsWith("Your choice of currency cannot be read"))
    }
}
