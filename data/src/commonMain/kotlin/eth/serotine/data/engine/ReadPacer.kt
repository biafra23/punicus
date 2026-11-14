package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import kotlin.random.Random
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spaces out one network's background re-proofs of different accounts, so that a peer does not
 * get the reads of all the wallet's addresses at the same moment from one IP (issue #253,
 * WalletBeat's *multi-address correlation*). The engine sends each address to its own peers from
 * the Myotis release that carries its #863 on; this adds the time between them.
 *
 * Reads take turns by address. Every read about the address whose turn it is runs at once: its
 * balance and its token balances together, since they name the same address. A read about
 * another address waits until the turn's reads are done and then a random [gap] more. A turn
 * that has run for [maxTurn] takes no new reads while another address waits, so one address
 * cannot hold the others back for good.
 *
 * Only background re-proofs are paced ([ProvenReads]): a subscriber's first proof (a screen
 * opening, a review, a signature) never waits here. What it never does: delay a read about the
 * same address, hide the reads from the peers that get them, or change what a read proves.
 */
internal class ReadPacer(
    /** The pause between the end of one address's turn and the start of another's. */
    private val gap: () -> Duration = ::randomGap,
    /** How long a turn takes new reads while another address waits. */
    private val maxTurn: Duration = MAX_TURN,
    private val clock: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private val lock = Mutex()

    /** The address whose turn it is; null before the first read. */
    private var current: Address? = null
    private var turnStarted: ComparableTimeMark? = null

    /** The current turn's reads still running. */
    private var active = 0

    /** Reads waiting for their address's turn. */
    private var waiting = 0

    /** When the next address may start, once [active] is 0. */
    private var nextAt: ComparableTimeMark? = null

    /** Bumped whenever a read ends, so waiters look again. */
    private val ended = MutableStateFlow(0L)

    /** Runs [read], about [address], in its address's turn. */
    suspend fun <T> paced(address: Address, read: suspend () -> T): T {
        var counted = false
        try {
            while (true) {
                val seen = ended.value
                val wait = lock.withLock {
                    val wait = admit(address)
                    if (wait != null && !counted) {
                        waiting++
                        counted = true
                    }
                    if (wait == null && counted) {
                        waiting--
                        counted = false
                    }
                    wait
                } ?: break
                withTimeoutOrNull(wait) { ended.first { it != seen } }
            }
        } finally {
            if (counted) withContext(NonCancellable) { lock.withLock { waiting-- } }
        }
        try {
            return read()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    active--
                    if (active == 0) nextAt = clock.markNow() + gap()
                }
                ended.update { it + 1 }
            }
        }
    }

    /** Null when a read about [address] may start now (and counts it); else how long to wait at most. */
    private fun admit(address: Address): Duration? {
        val joins = current == address &&
            (active > 0 || waiting == 0 || nextAt?.hasPassedNow() != false) &&
            (waiting == 0 || turnStarted?.elapsedNow()?.let { it < maxTurn } != false)
        val starts = current == null || (active == 0 && nextAt?.hasPassedNow() != false)
        return when {
            joins -> {
                active++
                null
            }
            starts -> {
                current = address
                turnStarted = clock.markNow()
                active++
                null
            }
            active == 0 -> nextAt?.let { -it.elapsedNow() }?.coerceAtLeast(Duration.ZERO) ?: Duration.ZERO
            else -> Duration.INFINITE
        }
    }

    companion object {
        /** The shortest and longest pause between two addresses' turns. */
        val MIN_GAP = 1500.milliseconds
        val MAX_GAP = 4.seconds

        /** How long a turn takes new reads while another address waits. */
        val MAX_TURN = 8.seconds

        private fun randomGap(): Duration =
            Random.nextLong(MIN_GAP.inWholeMilliseconds, MAX_GAP.inWholeMilliseconds + 1).milliseconds
    }
}
