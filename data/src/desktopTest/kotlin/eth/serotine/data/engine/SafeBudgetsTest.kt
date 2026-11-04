package eth.serotine.data.engine

import eth.serotine.domain.usecase.SAFE_CHECK_BUDGET
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The link between the adapter's budget for one Safe read and the domain's wait for a verified
 * reading, which only comments stated before: the wait outlasts the read's budget and the next
 * poll, with room for the read after it, or it would cut off the very read it waits on.
 */
class SafeBudgetsTest {

    @Test
    fun aOneShotCheckOutlastsOneReadsBudgetAndThePollAfterItWithRoomForAnotherRead() {
        // Room for a read after the poll: half of another budget, a dozen proofs that go through.
        val nextRead = 10.seconds
        val needed = VerifiedAddressKinds.SAFE_READ_BUDGET + MyotisHost.POLL_INTERVAL + nextRead
        assertTrue(
            SAFE_CHECK_BUDGET >= needed,
            "SAFE_CHECK_BUDGET $SAFE_CHECK_BUDGET must cover the read's budget, a poll and a next read: $needed",
        )
    }
}
