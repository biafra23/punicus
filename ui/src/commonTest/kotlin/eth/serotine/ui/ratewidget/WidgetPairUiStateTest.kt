package eth.serotine.ui.ratewidget

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.usecase.RateBase
import eth.serotine.domain.usecase.RateQuote
import eth.serotine.ui.components.NetworkLabel
import kotlin.test.Test
import kotlin.test.assertEquals

/** [widgetNetworkUiState], [widgetBaseUiState] and [widgetQuoteUiState]: what a widget being placed may show. */
class WidgetPairUiStateTest {

    private val ethereum = NetworkLabel(1, "Ethereum", testnet = false)
    private val sepolia = NetworkLabel(11_155_111, "Sepolia", testnet = true)
    private val networks = listOf(ethereum, sepolia)

    @Test
    fun theNetworksThatAreOnWithAccountsAreOffered() {
        assertEquals(
            WidgetPairUiState.Networks(listOf(ethereum)),
            widgetNetworkUiState(networks, on = setOf(1, 11_155_111), withAccounts = setOf(1)),
        )
        // A network with accounts that is off reads nothing: not offered.
        assertEquals(
            WidgetPairUiState.Networks(listOf(sepolia)),
            widgetNetworkUiState(networks, on = setOf(11_155_111), withAccounts = setOf(1, 11_155_111)),
        )
    }

    @Test
    fun aWalletWithoutAccountsIsOfferedEveryNetworkThatIsOn() {
        assertEquals(
            WidgetPairUiState.Networks(listOf(ethereum, sepolia)),
            widgetNetworkUiState(networks, on = setOf(1, 11_155_111), withAccounts = emptySet()),
        )
        assertEquals(
            WidgetPairUiState.Networks(listOf(ethereum)),
            widgetNetworkUiState(networks, on = setOf(1), withAccounts = null),
        )
    }

    @Test
    fun accountsOnlyOnNetworksThatAreOffLeaveEveryNetworkThatIsOn() {
        // Sepolia's accounts with Sepolia off: Ethereum, on, can still show its rate.
        assertEquals(
            WidgetPairUiState.Networks(listOf(ethereum)),
            widgetNetworkUiState(networks, on = setOf(1), withAccounts = setOf(11_155_111)),
        )
    }

    @Test
    fun withNoNetworkOnNothingIsOfferedAndItSaysWhy() {
        assertEquals(
            WidgetPairUiState.Networks(emptyList(), NO_NETWORK_ON),
            widgetNetworkUiState(networks, on = emptySet(), withAccounts = setOf(1)),
        )
    }

    @Test
    fun theBasesNameWhatTheyPriceAndOneWithNoPriceSaysWhy() {
        assertEquals(
            WidgetPairUiState.Bases(
                ethereum,
                listOf(
                    WidgetBaseChoice("ETH", "Ether · WETH"),
                    WidgetBaseChoice("LINK", "LINK"),
                    WidgetBaseChoice("sDAI", "No price", noPriceReason("sDAI", "Ethereum")),
                ),
            ),
            widgetBaseUiState(
                Network.ETHEREUM,
                listOf(
                    RateBase(PriceId("ETH"), listOf("Ether", "WETH")),
                    RateBase(PriceId("LINK"), listOf("LINK")),
                    RateBase(null, listOf("sDAI")),
                ),
            ),
        )
    }

    @Test
    fun theDisplayCurrencyComesFirstThenEachCurrencyByItsNameOneNotPricedSayingWhy() {
        assertEquals(
            WidgetPairUiState.Quotes(
                sepolia,
                "LINK",
                listOf(
                    WidgetQuoteChoice(null, "Display currency", followsDetail("Sepolia", "LINK")),
                    WidgetQuoteChoice("USD", "USD", "US dollar"),
                    WidgetQuoteChoice("CHF", "CHF", "Swiss franc", noQuoteReason("CHF", "Sepolia")),
                ),
            ),
            widgetQuoteUiState(
                Network.SEPOLIA,
                "LINK",
                listOf(RateQuote(DisplayCurrency.USD, priced = true), RateQuote(DisplayCurrency.CHF, priced = false)),
            ),
        )
    }

    @Test
    fun theFilterKeepsThePricesWhoseSymbolOrANameHoldsItAndSaysSoWhenNoneDoes() {
        val bases = WidgetPairUiState.Bases(
            ethereum,
            listOf(
                WidgetBaseChoice("ETH", "Ether · WETH"),
                WidgetBaseChoice("USDC", "USDC · USDC.e"),
                WidgetBaseChoice("COW", "COW"),
                WidgetBaseChoice("GNO", "No price", noPriceReason("GNO", "Ethereum")),
            ),
        )

        assertEquals(bases.choices, bases.shown, "nothing typed: every one")
        assertEquals(bases.choices, bases.copy(query = "  ").shown, "only spaces: every one")
        assertEquals(listOf("ETH"), bases.copy(query = "weth").shown.map { it.id }, "a name, any case")
        assertEquals(listOf("USDC"), bases.copy(query = " usdc.e ").shown.map { it.id }, "trimmed")
        // A greyed-out one matches by its symbol, and stays greyed out; never by its "No price".
        assertEquals(listOf("GNO"), bases.copy(query = "gn").shown.map { it.id })
        assertEquals(emptyList(), bases.copy(query = "price").shown)
        assertEquals(null, bases.copy(query = "c").noMatch)
        assertEquals("No price matches “xyz”", bases.copy(query = " xyz").noMatch)
        // Nothing read yet is no match of anything: the step is still loading.
        assertEquals(null, WidgetPairUiState.Bases(ethereum, emptyList(), query = "xyz").noMatch)
    }

    @Test
    fun whileAStepIsReadItIsShownWithNothingToChoose() {
        assertEquals(WidgetPairUiState.Networks(emptyList()), widgetPairLoading(null, null))
        assertEquals(WidgetPairUiState.Bases(ethereum, emptyList()), widgetPairLoading(Network.ETHEREUM, null))
        assertEquals(
            WidgetPairUiState.Quotes(sepolia, "LINK", emptyList()),
            widgetPairLoading(Network.SEPOLIA, PriceId("LINK")),
        )
    }
}
