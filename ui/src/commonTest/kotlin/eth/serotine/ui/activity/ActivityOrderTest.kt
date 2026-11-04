package eth.serotine.ui.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** An Activity's rows run the way the user chose: [ordered]. */
class ActivityOrderTest {

    private fun row(key: String) = ActivityRow(
        key = key,
        title = "Sent 1 ETH",
        detail = "To 0x5aAe…eAed",
        about = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        time = "Oct 27, 2026, 2:02 PM",
        status = "In block 9",
        state = ActivityState.DONE,
        opens = ActivityKey(key),
    )

    private val shown = ActivityUiState.Shown(
        rows = listOf(row("newest"), row("middle"), row("oldest")),
        coverage = "Transactions sent from this device.",
        waiting = listOf(WaitingStep("Swap 40 USDC → ETH", "Step 2 is waiting for you.", "0x" + "11".repeat(20))),
    )

    @Test
    fun newestFirstIsTheListAsBuilt() {
        assertSame(shown, shown.ordered(ActivityOrder.NEWEST_FIRST))
    }

    @Test
    fun oldestFirstTurnsTheRowsRoundAndKeepsTheWaitingCardsOnTop() {
        val turned = shown.ordered(ActivityOrder.OLDEST_FIRST) as ActivityUiState.Shown

        assertEquals(listOf("oldest", "middle", "newest"), turned.rows.map { it.key })
        assertEquals(shown.waiting, turned.waiting)
        assertEquals(shown.coverage, turned.coverage)
    }

    @Test
    fun whatIsNotShownIsLeftAsItIs() {
        assertSame(ActivityUiState.Loading, ActivityUiState.Loading.ordered(ActivityOrder.OLDEST_FIRST))
        val unavailable = ActivityUiState.Unavailable("Cannot be read")
        assertSame(unavailable, unavailable.ordered(ActivityOrder.OLDEST_FIRST))
    }
}
