package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.RatePair
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.FeedTable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [RateWidgetChoices] and [RatePair] over a fake table. */
class RateWidgetChoicesTest {

    private val table = object : FeedTable {
        override fun quotes(network: Network): Map<PriceId, PriceId> =
            mapOf(ETH to PriceId.USD, LINK_ID to ETH, EUR to PriceId.USD)
    }

    private fun token(symbol: String, priceId: PriceId?, n: Int, icon: String? = null) =
        Token(Address.of("0x" + n.toString().padStart(40, '0')), symbol, symbol, 18, icon = icon, priceId = priceId)

    @Test
    fun theNativeCurrencyComesFirstThenEachPriceIdOfThePricedTokensOnceThenTheUnpricedOnes() = runTest {
        val catalog = ListCatalog(
            token("LINK", LINK_ID, 1),
            token("WETH", ETH, 2),
            token("UNI", null, 3), // no price
            token("GHO", PriceId("GHO"), 4), // priced, but no feed pinned here
            token("EURe", EUR, 5),
        )

        val bases = RateWidgetChoices(catalog, table).bases(Network.SEPOLIA)

        assertEquals(
            listOf(
                RateBase(ETH, listOf("Ether", "WETH")),
                RateBase(LINK_ID, listOf("LINK")),
                RateBase(EUR, listOf("EURe")),
                RateBase(null, listOf("UNI")),
                RateBase(null, listOf("GHO")),
            ),
            bases,
        )
    }

    @Test
    fun aNativeCurrencyWithNoFeedIsGreyedOutFirstNotLeftOut() = runTest {
        val noNative = object : FeedTable {
            override fun quotes(network: Network): Map<PriceId, PriceId> = mapOf(LINK_ID to PriceId.USD)
        }

        val bases = RateWidgetChoices(ListCatalog(token("LINK", LINK_ID, 1), token("UNI", null, 2)), noNative)
            .bases(Network.SEPOLIA)

        assertEquals(
            listOf(RateBase(LINK_ID, listOf("LINK")), RateBase(null, listOf("ETH")), RateBase(null, listOf("UNI"))),
            bases,
        )
    }

    @Test
    fun aBaseMayBeShownInEveryCurrencyButItselfPricedInUsdAndThosePinnedThere() {
        val choices = RateWidgetChoices(ListCatalog(), table)

        fun priced(base: PriceId) = choices.quotes(Network.SEPOLIA, base).filter { it.priced }.map { it.currency }
        assertEquals(listOf(DisplayCurrency.USD, DisplayCurrency.EUR), priced(ETH))
        assertEquals(listOf(DisplayCurrency.USD, DisplayCurrency.EUR, DisplayCurrency.ETH), priced(LINK_ID))
        assertEquals(listOf(DisplayCurrency.USD, DisplayCurrency.ETH), priced(EUR))
        assertEquals(
            DisplayCurrency.entries - DisplayCurrency.ETH,
            choices.quotes(Network.SEPOLIA, ETH).map { it.currency },
        )
        assertEquals(RateQuote(DisplayCurrency.GBP, priced = false), choices.quotes(Network.SEPOLIA, ETH)[2])
    }

    @Test
    fun aPairOfOneCurrencyInItselfIsNone() {
        assertFailsWith<IllegalArgumentException> { RatePair(Network.ETHEREUM, ETH, DisplayCurrency.ETH) }
        assertEquals(RatePair(Network.GNOSIS, PriceId("XDAI"), null), RatePair.native(Network.GNOSIS))
    }

    @Test
    fun theIconOfTheNativeCurrencyIsTheNetworksOwn() = runTest {
        val choices = RateWidgetChoices(ListCatalog(), table)

        assertEquals(Network.SEPOLIA.nativeIcon, choices.icon(Network.SEPOLIA, ETH))
    }

    @Test
    fun theIconOfAPricedTokenIsTheBuiltInTokensOwn() = runTest {
        val catalog = ListCatalog(token("LINK", LINK_ID, 1, icon = "link"))
        val choices = RateWidgetChoices(catalog, table)

        assertEquals("link", choices.icon(Network.SEPOLIA, LINK_ID))
    }

    @Test
    fun aBaseNoBuiltInTokenPricesHasNoIcon() = runTest {
        val choices = RateWidgetChoices(ListCatalog(token("UNI", null, 1)), table)

        assertEquals(null, choices.icon(Network.SEPOLIA, PriceId("SOMETHING")))
    }
}
