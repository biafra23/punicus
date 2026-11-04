package eth.serotine.data.engine

import kotlin.concurrent.Volatile
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Everything a node's adapters ask of its engine: the seam each platform's engine host (`MyotisHost`, on
 * the JVM targets and on iOS) implements over each network's handle, and which [SharedSnapshots] and
 * [LoggedChain] wrap.
 */
internal interface EngineChain : TransactingChain, LogIndexChain

/**
 * [inner] whose [snapshot] is shared: every adapter, and each of its subscribers, polls on its
 * own, and those that ask within [reuse] of one read get that read, so they cost the engine one
 * status call, not one each. Everything else goes to [inner] as it is.
 */
internal class SharedSnapshots(
    private val inner: EngineChain,
    private val reuse: Duration,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : EngineChain by inner {

    private class Timed(val at: ComparableTimeMark, val snapshot: EngineSnapshot)

    @Volatile
    private var last: Timed? = null

    override fun snapshot(): EngineSnapshot {
        last?.let { if (it.at.elapsedNow() < reuse) return it.snapshot }
        val at = timeSource.markNow()
        return inner.snapshot().also { last = Timed(at, it) }
    }
}
