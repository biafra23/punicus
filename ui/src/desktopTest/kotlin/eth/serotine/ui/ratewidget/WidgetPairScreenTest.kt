package eth.serotine.ui.ratewidget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.UNAVAILABLE_REASON_TAG
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class WidgetPairScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(state: WidgetPairUiState) = rule.setContent {
        SerotineTheme {
            WidgetPairScreen(
                state,
                onNetwork = { events += "network $it" },
                onBase = { events += "base $it" },
                onQuote = { events += "quote $it" },
                onFilter = { events += "filter $it" },
                onBack = { events += "back" },
                onCancel = { events += "cancel" },
            )
        }
    }

    @Test
    fun aTappedNetworkIsChosenAndCancelPlacesNone() {
        show(
            WidgetPairUiState.Networks(
                listOf(NetworkLabel(1, "Ethereum", false), NetworkLabel(11_155_111, "Sepolia", true)),
            ),
        )

        rule.onNodeWithTag(WIDGET_NETWORK_TAG + 11_155_111).performClick()
        rule.onNodeWithContentDescription("Cancel").performClick()

        assertEquals(listOf("network 11155111", "cancel"), events)
    }

    @Test
    fun aTappedBaseIsChosenAndBackStepsBack() {
        show(
            WidgetPairUiState.Bases(
                NetworkLabel(1, "Ethereum", false),
                listOf(
                    WidgetBaseChoice("ETH", "Ether · WETH"),
                    WidgetBaseChoice("LINK", "LINK"),
                    WidgetBaseChoice("sDAI", "No price", noPriceReason("sDAI", "Ethereum")),
                ),
            ),
        )

        rule.onNodeWithText("Ether · WETH").assertExists()
        rule.onNodeWithTag(WIDGET_BASE_TAG + "sDAI").performClick() // no price: says why, chooses nothing
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals(noPriceReason("sDAI", "Ethereum"))
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithTag(WIDGET_BASE_TAG + "LINK").performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf("base LINK", "back"), events)
    }

    @Test
    fun theFilterNarrowsThePricesSaysWhenNoneMatchesAndEmptiesWithItsButton() {
        val bases = WidgetPairUiState.Bases(
            NetworkLabel(1, "Ethereum", false),
            listOf(WidgetBaseChoice("ETH", "Ether · WETH"), WidgetBaseChoice("LINK", "LINK")),
        )
        var state by mutableStateOf(bases)
        rule.setContent {
            SerotineTheme {
                WidgetPairScreen(
                    state,
                    onNetwork = {},
                    onBase = {},
                    onQuote = {},
                    onFilter = {
                        events += "filter $it"
                        state = state.copy(query = it)
                    },
                    onBack = {},
                    onCancel = {},
                )
            }
        }

        rule.onNodeWithContentDescription("Clear the filter").assertDoesNotExist()
        rule.onNodeWithTag(WIDGET_FILTER_TAG).performTextInput("lin")
        rule.onNodeWithTag(WIDGET_BASE_TAG + "LINK").assertExists()
        rule.onNodeWithTag(WIDGET_BASE_TAG + "ETH").assertDoesNotExist()

        rule.onNodeWithTag(WIDGET_FILTER_TAG).performTextInput("x")
        rule.onNodeWithTag(WIDGET_NO_MATCH_TAG).assertTextEquals("No price matches “linx”")

        rule.onNodeWithContentDescription("Clear the filter").performClick()
        rule.onNodeWithTag(WIDGET_BASE_TAG + "ETH").assertExists()
        rule.onNodeWithTag(WIDGET_NO_MATCH_TAG).assertDoesNotExist()
        assertEquals(listOf("filter lin", "filter linx", "filter "), events)
    }

    @Test
    fun aTappedCurrencyIsChosenTheDisplayCurrencyAsNone() {
        show(
            WidgetPairUiState.Quotes(
                NetworkLabel(1, "Ethereum", false),
                "LINK",
                listOf(
                    WidgetQuoteChoice(null, "Display currency", followsDetail("Ethereum", "LINK")),
                    WidgetQuoteChoice("EUR", "EUR", "Euro"),
                    WidgetQuoteChoice("GBP", "GBP", "Pound sterling", noQuoteReason("GBP", "Ethereum")),
                ),
            ),
        )

        rule.onNodeWithText("Which currency should LINK be shown in?").assertExists()
        rule.onNodeWithTag(WIDGET_QUOTE_TAG + "EUR").performClick()
        rule.onNodeWithTag(WIDGET_QUOTE_TAG + WIDGET_FOLLOWS).performClick()
        rule.onNodeWithTag(WIDGET_QUOTE_TAG + "GBP").performClick() // greyed out: says why, chooses nothing

        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals(noQuoteReason("GBP", "Ethereum"))
        assertEquals(listOf("quote EUR", "quote null"), events)
    }
}
