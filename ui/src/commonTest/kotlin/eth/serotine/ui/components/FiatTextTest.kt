package eth.serotine.ui.components

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.usecase.Total
import eth.serotine.domain.usecase.Valuation
import kotlin.test.Test
import kotlin.test.assertEquals

class FiatTextTest {

    @Test
    fun aValueIsRoundedToItsCurrencysDigitsAndGrouped() {
        assertEquals("$3,250.00", formatFiat(3_250.0, DisplayCurrency.USD))
        assertEquals("€1,234.57", formatFiat(1_234.567, DisplayCurrency.EUR))
        assertEquals("¥632", formatFiat(632.4, DisplayCurrency.JPY))
        assertEquals("1.2346 ETH", formatFiat(1.23456, DisplayCurrency.ETH))
        assertEquals("CHF 0.10", formatFiat(0.1, DisplayCurrency.CHF))
    }

    @Test
    fun aTinyValueIsNeverShownAsNothingAndAHugeOneIsCapped() {
        assertEquals("< $0.01", formatFiat(0.001, DisplayCurrency.USD))
        assertEquals("< ¥1", formatFiat(0.2, DisplayCurrency.JPY))
        assertEquals("$0.00", formatFiat(0.0, DisplayCurrency.USD))
        assertEquals("$1,000,000,000,000,000+", formatFiat(1e30, DisplayCurrency.USD))
        assertEquals("1,000,000,000,000+ BTC", formatFiat(1e20, DisplayCurrency.BTC))
    }

    @Test
    fun aRowSaysItsValueOrWhyThereIsNoneAndATestnetValueSaysSo() {
        val usd = DisplayCurrency.USD
        assertEquals(
            "$5.00 · testnet prices",
            valuationText(Valuation.Valued(5.0, 60, stale = false), usd, Network.SEPOLIA),
        )
        assertEquals(
            "$5.00 · price 2 h old · testnet prices",
            valuationText(Valuation.Valued(5.0, 7_200, stale = true), usd, Network.SEPOLIA),
        )
        assertEquals("No price", valuationText(Valuation.NoPrice(NoPriceReason.NO_FEED), usd, Network.SEPOLIA))
        assertEquals("No EUR rate", valuationText(Valuation.NoRate, DisplayCurrency.EUR, Network.SEPOLIA))
        assertEquals(null, valuationText(null, usd, Network.SEPOLIA))
    }

    @Test
    fun aValueAtAPoolsAverageSaysSoAndSoDoesATotalThatIncludesOne() {
        assertEquals(
            "≈ $0.12 · Uniswap\u00a030-\u2060min\u00a0average",
            valuationText(
                Valuation.Valued(0.12, 0, stale = false, poolAverage = true),
                DisplayCurrency.USD,
                Network.ETHEREUM,
            ),
        )
        assertEquals(
            "Includes Uniswap averages · 1 asset without a price",
            totalNote(Total(12.0, valued = 2, leftOut = 1, poolAveraged = 1), Network.ETHEREUM, DisplayCurrency.USD),
        )
        assertEquals("", totalNote(Total(12.0, valued = 2), Network.ETHEREUM, DisplayCurrency.USD))
        assertEquals(
            "No price: its pool just moved",
            valuationText(Valuation.NoPrice(NoPriceReason.POOL_MOVED), DisplayCurrency.USD, Network.ETHEREUM),
        )
    }

    @Test
    fun aTotalSaysWhatItLeavesOutAndNeverChecksForever() {
        assertEquals(
            "Testnet prices · 2 assets without a price · 1 not verified · still checking",
            totalNote(
                Total(1.0, valued = 1, leftOut = 2, unverified = 1, complete = false),
                Network.SEPOLIA,
                DisplayCurrency.USD,
            ),
        )
        assertEquals("Checking…", totalText(Total.PENDING, DisplayCurrency.USD))
        // Every balance unverifiable (no light client): said, not "Checking…" for ever.
        assertEquals("Not verified", totalText(Total(0.0, unverified = 2), DisplayCurrency.USD))
        assertEquals("No EUR rate", totalText(Total(0.0, noRate = true), DisplayCurrency.EUR))
        assertEquals("$12.00", totalText(Total(12.0, valued = 1, complete = false), DisplayCurrency.USD))
    }
}
