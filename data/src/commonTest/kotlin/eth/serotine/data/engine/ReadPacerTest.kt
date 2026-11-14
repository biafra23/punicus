package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class ReadPacerTest {

    private val a = Address.of("0x" + "aa".repeat(20))
    private val b = Address.of("0x" + "bb".repeat(20))
    private val c = Address.of("0x" + "cc".repeat(20))

    private fun TestScope.pacer(gap: Duration = GAP, maxTurn: Duration = ReadPacer.MAX_TURN) =
        ReadPacer(gap = { gap }, maxTurn = maxTurn, clock = testTimeSource)

    /** When each read started, in virtual milliseconds, per label. */
    private val started = mutableMapOf<String, Long>()

    private fun TestScope.read(pacer: ReadPacer, address: Address, label: String, takes: Duration = READ) =
        launch {
            pacer.paced(address) {
                started[label] = currentTime
                delay(takes)
            }
        }

    @Test
    fun readsAboutOneAddressRunTogether() = runTest {
        val pacer = pacer()
        read(pacer, a, "balance")
        read(pacer, a, "token 1")
        read(pacer, a, "token 2")
        runCurrent()
        assertEquals(mapOf("balance" to 0L, "token 1" to 0L, "token 2" to 0L), started)
    }

    @Test
    fun anotherAddressWaitsForTheTurnToEndAndTheGap() = runTest {
        val pacer = pacer()
        read(pacer, a, "a")
        read(pacer, b, "b")
        read(pacer, c, "c")
        advanceTimeBy(60.seconds)
        runCurrent()
        // a runs at once; b after a's read and the gap; c after b's and another gap.
        assertEquals(0L, started["a"])
        assertEquals((READ + GAP).inWholeMilliseconds, started["b"])
        assertEquals((READ + GAP).inWholeMilliseconds * 2, started["c"])
    }

    @Test
    fun theSameAddressAfterItsTurnNeedsNoGap() = runTest {
        val pacer = pacer()
        read(pacer, a, "first")
        advanceTimeBy(READ + 1.seconds)
        runCurrent()
        read(pacer, a, "again")
        runCurrent()
        assertEquals((READ + 1.seconds).inWholeMilliseconds, started["again"])
    }

    @Test
    fun aTurnTakenUpAgainStartsItsClockAgain() = runTest {
        // Long after a's last turn, a's reads take a new turn of their own: one more read about a
        // joins it even with b waiting, since the new turn is young.
        val pacer = pacer()
        read(pacer, a, "first")
        advanceTimeBy(20.seconds)
        runCurrent()
        read(pacer, a, "again", takes = 5.seconds)
        runCurrent()
        read(pacer, b, "b")
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()
        read(pacer, a, "token")
        runCurrent()
        assertEquals(21.seconds.inWholeMilliseconds, started["token"])
    }

    @Test
    fun waitingAddressesGetTheirTurnsInTheOrderTheyCame() = runTest {
        // b waited first, with one read; c came later, with three: b still goes first, and c's
        // three reads share the next turn.
        val pacer = pacer()
        read(pacer, a, "a")
        runCurrent()
        read(pacer, b, "b")
        runCurrent()
        repeat(3) { read(pacer, c, "c$it") }
        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals((READ + GAP).inWholeMilliseconds, started["b"])
        val cTurn = (READ + GAP).inWholeMilliseconds * 2
        assertEquals(listOf(cTurn, cTurn, cTurn), (0..2).map { started["c$it"] })
    }

    @Test
    fun aLongTurnTakesNoNewReadsWhileAnotherAddressWaits() = runTest {
        val pacer = pacer(maxTurn = 2.seconds)
        read(pacer, a, "a long", takes = 5.seconds)
        runCurrent()
        read(pacer, b, "b")
        advanceTimeBy(3.seconds)
        runCurrent()
        // Past the turn's limit with b waiting: a new read about a waits too.
        read(pacer, a, "a late")
        runCurrent()
        assertEquals(null, started["a late"])
        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals((5.seconds + GAP).inWholeMilliseconds, started["b"])
    }

    @Test
    fun aFailedReadEndsItsTurn() = runTest {
        val pacer = pacer()
        val failed = CompletableDeferred<Throwable?>()
        launch {
            failed.complete(runCatching { pacer.paced(a) { error("the engine threw") } }.exceptionOrNull())
        }
        read(pacer, b, "b")
        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals("the engine threw", failed.await()?.message)
        assertEquals(GAP.inWholeMilliseconds, started["b"])
    }

    @Test
    fun aCancelledWaiterHoldsNothingUp() = runTest {
        val pacer = pacer(maxTurn = 1.seconds)
        read(pacer, a, "a", takes = 3.seconds)
        runCurrent()
        val gaveUp = read(pacer, b, "b")
        runCurrent()
        gaveUp.cancel()
        advanceTimeBy(2.seconds)
        runCurrent()
        // With no one waiting any more, a's turn takes a new read past its limit.
        read(pacer, a, "a again")
        runCurrent()
        assertEquals(2.seconds.inWholeMilliseconds, started["a again"])
    }

    private companion object {
        val GAP = 2.seconds
        val READ = 1.seconds
    }
}
