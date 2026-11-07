package eth.serotine.data.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * Carries the engine's own log into a host's debug log. The Rust engine prints nothing: it
 * writes its tracing lines into a ring of its own (4096 lines, the oldest dropped when full),
 * which a host has to drain, so without this a running engine's account of its sync (peers
 * found and dropped, heads followed, proofs refused) is never seen. [drain] takes up to its
 * argument's lines out of that ring, oldest first and newline-joined, empty when none wait;
 * each line goes to [sink] behind [PREFIX], its indent dropped. Engine state only: no key
 * material is ever in the engine, so none can reach the log this way.
 */
internal class EngineLogPump(
    private val drain: (Int) -> String,
    private val sink: (String) -> Unit,
) {

    /**
     * Drains what waits now, batch by batch, until the ring is empty. A drain or a sink that
     * throws ends the pump's work, said once in the log where it can be: the log is for watching
     * the engine and must never stop it. Returns false then, true otherwise.
     */
    fun drainNow(): Boolean {
        val failure = drainAll(drain) { lines ->
            lines.forEach { sink(PREFIX + it.trimStart()) } // the engine indents its level
        } ?: return true
        try {
            sink(PREFIX + "the engine's log can no longer be drained: $failure")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // The sink failed in the first place: nowhere left to say so.
        }
        return false
    }

    /** Drains every [interval] until cancelled, or until a drain or the sink throws. */
    suspend fun run(interval: Duration) {
        while (drainNow()) delay(interval)
    }

    companion object {
        /** What each engine line is marked with, beside the adapters' own `Punicus:` lines. */
        const val PREFIX = "Myotis: "

        /** Lines taken per drain call; the ring is emptied in as many calls as it takes. */
        const val BATCH = 256

        /**
         * The last [count] lines waiting in the ring, newline-joined, the rest drained with them:
         * the ring gives the oldest first, and a refused start says why at the end. Drained until
         * empty, whatever the ring's size; a drain that throws leaves the lines taken before it.
         */
        fun lastLines(drain: (Int) -> String, count: Int): String {
            val tail = ArrayDeque<String>()
            drainAll(drain) { lines ->
                tail.addAll(lines)
                while (tail.size > count) tail.removeFirst()
            }
            return tail.joinToString("\n")
        }

        /**
         * Hands [take] each batch [drain] gives, split into lines, until the ring is empty.
         * Returns what was thrown by [drain] or [take], which ends it; null once the ring is empty.
         */
        private fun drainAll(drain: (Int) -> String, take: (List<String>) -> Unit): Throwable? {
            while (true) {
                try {
                    val batch = drain(BATCH)
                    if (batch.isEmpty()) return null
                    val lines = batch.split('\n')
                    take(lines)
                    if (lines.size < BATCH) return null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    return e
                }
            }
        }
    }
}
