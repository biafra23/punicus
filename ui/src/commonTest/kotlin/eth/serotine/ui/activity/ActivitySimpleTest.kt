package eth.serotine.ui.activity

import kotlin.test.Test
import kotlin.test.assertEquals

/** An Activity in simple mode's words: [simple]. */
class ActivitySimpleTest {

    private val row = ActivityRow(
        key = "0x" + "ab".repeat(32) + ":1",
        title = "Received 100 USDC",
        detail = "From 0x5aAe…eAed",
        about = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        time = "Block 23,598,301",
        status = "On chain, from the token's verified logs",
        state = ActivityState.DONE,
        opens = ActivityKey("0x" + "ab".repeat(32), 1),
        simpleTime = null,
    )

    @Test
    fun aCautionGivenAfterTheRowWasMadeIsKept() {
        // As the rows' factory does: the caution is a copy of a row made with another status.
        val cautioned = row.copy(
            status = "From an address you have not sent to: compare every character before you copy it",
            state = ActivityState.CAUTION,
        )
        val shown = ActivityUiState.Shown(listOf(cautioned), coverage = "On chain from block 1 on").simple()

        val simple = (shown as ActivityUiState.Shown).rows.single()
        assertEquals(cautioned.status, simple.status)
        assertEquals(ActivityState.CAUTION, simple.state)
        assertEquals("", simple.time, "dated only by its block: no time in simple mode")
    }

    @Test
    fun aSentRowSaysHowItEndedInWordsAndTheCoverageLosesItsBlock() {
        val sent = row.copy(
            time = "Oct 27, 2026, 2:02 PM",
            simpleTime = "Oct 27, 2026, 2:02 PM",
            status = "In block 9",
            simpleStatus = "Confirmed",
        )
        val shown = ActivityUiState.Shown(
            listOf(sent),
            coverage = "On chain from block 1 on",
            simpleCoverage = "Activity since you added it.",
        ).simple() as ActivityUiState.Shown

        assertEquals("Confirmed", shown.rows.single().status)
        assertEquals("Oct 27, 2026, 2:02 PM", shown.rows.single().time)
        assertEquals("Activity since you added it.", shown.coverage)
    }
}
