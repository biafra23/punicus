package eth.serotine.ui.currency

import androidx.compose.runtime.Composable
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The display currency screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. CurrencyScreenPreviewsTest renders each one in currencyScreenPreviews, at the end.
// The currencies are the ones Sepolia's price feeds offer.

private fun options(chosen: String) = listOf(
    "USD" to "US dollar",
    "EUR" to "Euro",
    "GBP" to "Pound sterling",
    "JPY" to "Japanese yen",
    "ETH" to "Ether",
    "BTC" to "Bitcoin",
).map { (code, name) -> CurrencyOption(code, name, chosen = code == chosen) }

private val euro = CurrencyUiState(options("EUR"))

@Composable
private fun CurrencyPreview(state: CurrencyUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        CurrencyScreen(state = state, onChoose = {}, onBack = {})
    }
}

@Preview
@Composable
private fun CurrencyChosenPreview() = CurrencyPreview(euro)

@Preview
@Composable
private fun CurrencyChosenDarkPreview() = CurrencyPreview(euro, darkTheme = true)

@Preview
@Composable
private fun CurrencySetAsidePreview() = CurrencyPreview(
    CurrencyUiState(
        options("USD"),
        notice = "Your saved choice of currency was damaged and has been set aside: values are in USD.",
    ),
)

@Preview
@Composable
private fun CurrencyUnreadablePreview() = CurrencyPreview(
    CurrencyUiState(
        options("USD"),
        notice = "Your choice of currency cannot be read right now, so values are in USD and nothing can be " +
            "chosen. Punicus keeps trying.",
        canChoose = false,
    ),
)

/** Every preview above, which CurrencyScreenPreviewsTest renders: a new preview goes in here too. */
internal val currencyScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("chosen") { CurrencyChosenPreview() },
    ScreenPreview("chosen-dark") { CurrencyChosenDarkPreview() },
    ScreenPreview("set-aside") { CurrencySetAsidePreview() },
    ScreenPreview("unreadable") { CurrencyUnreadablePreview() },
)
