package eth.serotine.ui.ratewidget

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The widget's configuration in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, never from the host's ports.
// WidgetPairScreenPreviewsTest renders each one in widgetPairScreenPreviews, at the end.

private val ethereum = NetworkLabel(1, "Ethereum", testnet = false)
private val gnosis = NetworkLabel(100, "Gnosis Chain", testnet = false)
private val sepolia = NetworkLabel(11_155_111, "Sepolia", testnet = true)

private val networks = WidgetPairUiState.Networks(listOf(ethereum, gnosis, sepolia))

private val bases = WidgetPairUiState.Bases(
    gnosis,
    listOf(
        WidgetBaseChoice("XDAI", "xDAI · WXDAI"),
        WidgetBaseChoice("USDC", "USDC · USDC.e"),
        WidgetBaseChoice("USDT", "USDT"),
        WidgetBaseChoice("ETH", "WETH"),
        WidgetBaseChoice("GNO", "GNO"),
        WidgetBaseChoice("EUR", "EURe"),
        WidgetBaseChoice("sDAI", "No price", noPriceReason("sDAI", "Gnosis Chain")),
    ),
)

private val quotes = WidgetPairUiState.Quotes(
    gnosis,
    "GNO",
    listOf(
        WidgetQuoteChoice(null, "Display currency", followsDetail("Gnosis Chain", "GNO")),
        WidgetQuoteChoice("USD", "USD", "US dollar"),
        WidgetQuoteChoice("EUR", "EUR", "Euro"),
        WidgetQuoteChoice("GBP", "GBP", "Pound sterling", noQuoteReason("GBP", "Gnosis Chain")),
        WidgetQuoteChoice("CHF", "CHF", "Swiss franc"),
        WidgetQuoteChoice("JPY", "JPY", "Japanese yen"),
        WidgetQuoteChoice("ETH", "ETH", "Ether"),
        WidgetQuoteChoice("BTC", "BTC", "Bitcoin"),
    ),
)

@Composable
private fun WidgetPairPreview(state: WidgetPairUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        WidgetPairScreen(state, onNetwork = {}, onBase = {}, onQuote = {}, onFilter = {}, onBack = {}, onCancel = {})
    }
}

@Preview
@Composable
private fun WidgetPairNetworksPreview() = WidgetPairPreview(networks)

@Preview
@Composable
private fun WidgetPairNetworksDarkPreview() = WidgetPairPreview(networks, darkTheme = true)

@Preview
@Composable
private fun WidgetPairNoneOnPreview() = WidgetPairPreview(WidgetPairUiState.Networks(emptyList(), NO_NETWORK_ON))

@Preview
@Composable
private fun WidgetPairBasesPreview() = WidgetPairPreview(bases)

/** The prices narrowed by their filter: "dai" is in xDAI's names and in sDAI's symbol, greyed out still. */
@Preview
@Composable
private fun WidgetPairBasesFilteredPreview() = WidgetPairPreview(bases.copy(query = "dai"))

/** A filter that matches none of the prices says so. */
@Preview
@Composable
private fun WidgetPairBasesNoMatchPreview() = WidgetPairPreview(bases.copy(query = "xyz"))

@Preview
@Composable
private fun WidgetPairBasesFilteredDarkPreview() = WidgetPairPreview(bases.copy(query = "dai"), darkTheme = true)

@Preview
@Composable
private fun WidgetPairQuotesPreview() = WidgetPairPreview(quotes)

@Preview
@Composable
private fun WidgetPairQuotesDarkPreview() = WidgetPairPreview(quotes, darkTheme = true)

/** Every preview above, which WidgetPairScreenPreviewsTest renders: a new preview goes in here too. */
internal val widgetPairScreenPreviews: List<@Composable () -> Unit> = listOf(
    { WidgetPairNetworksPreview() },
    { WidgetPairNetworksDarkPreview() },
    { WidgetPairNoneOnPreview() },
    { WidgetPairBasesPreview() },
    { WidgetPairBasesFilteredPreview() },
    { WidgetPairBasesNoMatchPreview() },
    { WidgetPairBasesFilteredDarkPreview() },
    { WidgetPairQuotesPreview() },
    { WidgetPairQuotesDarkPreview() },
)
