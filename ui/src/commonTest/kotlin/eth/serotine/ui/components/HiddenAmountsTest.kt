package eth.serotine.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What an amount reads while amounts are hidden: its figure masked, everything else as it was. */
class HiddenAmountsTest {

    @Test
    fun theFigureIsMaskedAndTheUnitStays() {
        assertEquals("$AMOUNT_MASK ETH", maskAmount("0.5 ETH"))
        assertEquals("$AMOUNT_MASK USDC", maskAmount("1,250 USDC"))
        assertEquals("€$AMOUNT_MASK", maskAmount("€12,025.55"))
        assertEquals("$$AMOUNT_MASK", maskAmount("$3,250.00"))
        assertEquals("CHF $AMOUNT_MASK", maskAmount("CHF 1,234.00"))
        assertEquals("$AMOUNT_MASK BTC", maskAmount("0.1234 BTC"))
        // A value too large to show: its "+" is part of the figure.
        assertEquals("$$AMOUNT_MASK", maskAmount("$100,000,000+"))
    }

    @Test
    fun aTinyAmountDoesNotSayItIsTiny() {
        assertEquals("$AMOUNT_MASK ETH", maskAmount("< 0.0001 ETH"))
        assertEquals("$$AMOUNT_MASK", maskAmount("< $0.01"))
        // At a pool's average, "≈ " comes first.
        assertEquals("≈ €$AMOUNT_MASK · Uniswap 30-min average", maskAmount("≈ < €0.01 · Uniswap 30-min average"))
    }

    @Test
    fun digitsInTheUnitAreTheUnits() {
        assertEquals("$AMOUNT_MASK 1INCH", maskAmount("12.5 1INCH"))
        assertEquals("$AMOUNT_MASK USDT0", maskAmount("100 USDT0"))
    }

    @Test
    fun whatQualifiesAnAmountStays() {
        assertEquals(
            "≈ €$AMOUNT_MASK · Uniswap 30-min average · price 2 h old",
            maskAmount("≈ €3.10 · Uniswap 30-min average · price 2 h old"),
        )
        assertEquals("€$AMOUNT_MASK · testnet prices", maskAmount("€0.00 · testnet prices"))
    }

    @Test
    fun aTextWithoutAFigureIsLeftAsItIs() {
        listOf("Checking…", "No price", "no real value", "Syncing…", "Not verified", "No EUR rate", "Not checked")
            .forEach { assertEquals(it, maskAmount(it)) }
        assertNull(maskAmount(null as String?))
    }
}
