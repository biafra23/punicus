package eth.serotine.ui.ratewidget

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.RatePair
import eth.serotine.domain.model.RateSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class RateWidgetContentTest {

    private val snapshot = RateSnapshot(
        base = PriceId("ETH"),
        quote = DisplayCurrency.EUR,
        rate = 2_046.2,
        dayAgoRate = 2_000.0,
        updatedAt = 1_000,
        readAt = 1_600,
        stale = false,
    )

    private val sepolia = RatePair.native(Network.SEPOLIA)
    private val ethereum = RatePair.native(Network.ETHEREUM)

    @Test
    fun aRiseIsGreenWithAPlusAndTheRateIsDatedAsTestnetPrices() {
        assertEquals(
            RateWidgetContent(
                "ETH · EUR",
                "€2,046.20",
                "24h +2.31%",
                Trend.UP,
                "Sepolia · Verified 14:05 · may have changed since · testnet prices",
            ),
            rateWidgetContent(snapshot, sepolia, "14:05"),
        )
    }

    @Test
    fun aFallIsRedWithAMinusSign() {
        val content = rateWidgetContent(snapshot.copy(rate = 1_979.0), sepolia, "14:05")

        assertEquals("24h −1.05%", content.change)
        assertEquals(Trend.DOWN, content.trend)
    }

    @Test
    fun whatRoundsToNoMoveIsFlatAndNoRateADayBackIsADash() {
        val flat = rateWidgetContent(snapshot.copy(rate = 2_000.04), sepolia, "14:05")
        assertEquals("24h 0.00%", flat.change)
        assertEquals(Trend.FLAT, flat.trend)

        val none = rateWidgetContent(snapshot.copy(dayAgoRate = null), sepolia, "14:05")
        assertEquals("24h —", none.change)
        assertEquals(Trend.UNKNOWN, none.trend)
    }

    @Test
    fun aStaleRateSaysHowOldItsPriceIs() {
        val stale = snapshot.copy(updatedAt = 1_600 - 7_200, stale = true)

        val content = rateWidgetContent(stale, sepolia, "14:05")

        assertEquals(
            "Sepolia · Verified 14:05 · may have changed since · price 2 h old · testnet prices",
            content.note,
        )
    }

    @Test
    fun aRateAtAPoolsAverageSaysSoOnTheLineThatDatesIt() {
        val cow = snapshot.copy(base = PriceId("COW"), rate = 0.11, poolAverage = true)

        val content = rateWidgetContent(cow, RatePair(Network.ETHEREUM, PriceId("COW"), DisplayCurrency.EUR), "14:05")

        assertEquals("Ethereum · Verified 14:05 · Uniswap\u00a030-\u2060min\u00a0average · may have changed since", content.note)
    }

    @Test
    fun noRateKeptYetSaysSo() {
        assertEquals(
            RateWidgetContent("ETH", "—", null, Trend.UNKNOWN, "Sepolia · No verified rate yet"),
            rateWidgetContent(null, sepolia, ""),
        )
    }

    @Test
    fun aWidgetOfANetworkThatIsOffKeepsItsLastRateAndSaysSo() {
        assertEquals(
            "Ethereum · Verified 14:05 · Ethereum is off",
            rateWidgetContent(snapshot, ethereum, "14:05", on = false).note,
        )
        assertEquals("€2,046.20", rateWidgetContent(snapshot, ethereum, "14:05", on = false).rate)
        assertEquals("Ethereum · Ethereum is off", rateWidgetContent(null, ethereum, "", on = false).note)
    }

    @Test
    fun theIconPassesThroughWhetherOrNotThereIsARate() {
        assertEquals("eth", rateWidgetContent(snapshot, sepolia, "14:05", icon = "eth").icon)
        assertEquals("eth", rateWidgetContent(null, sepolia, "", icon = "eth").icon)
        assertEquals(null, rateWidgetContent(snapshot, sepolia, "14:05").icon)
    }

    @Test
    fun aTokenInItsOwnCurrencyIsNamedSoBeforeItsFirstRate() {
        val linkInEur = RatePair(Network.ETHEREUM, PriceId("LINK"), DisplayCurrency.EUR)

        assertEquals("LINK · EUR", rateWidgetContent(null, linkInEur, "").pair)
        val link = snapshot.copy(base = PriceId("LINK"), rate = 12.5, dayAgoRate = 10.0)
        assertEquals(
            RateWidgetContent(
                "LINK · EUR",
                "€12.50",
                "24h +25.00%",
                Trend.UP,
                "Ethereum · Verified 14:05 · may have changed since",
            ),
            rateWidgetContent(link, linkInEur, "14:05"),
        )
    }
}
