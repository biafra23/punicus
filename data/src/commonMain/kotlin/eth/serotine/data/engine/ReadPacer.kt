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
 * another address waits until the turn's reads are done and then a random [gap] more. Waiting
 * addresses get their turns first come, first served, so each wait is bounded. A turn that has
 * run for [maxTurn] takes no new reads while another address waits: those queue behind it.
 *
 * Only background re-proofs are paced ([ProvenReads]): a subscriber's first proof (a screen
 * opening, a review, a signature) and a refresh the user asked for never wait here. A read about
 * the turn's own address waits only past [maxTurn], or after its turn, while another address
 * waits. What it never does: hide the reads from the peers that get them, or change what a read
 * proves.
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

    /** The reads waiting, per address, in the order the addresses began to wait. */
    private val waiters = LinkedHashMap<Address, Int>()

    /** When the next address may start, once [active] is 0. */
    private var nextAt: ComparableTimeMark? = null

    /** Bumped whenever a turn starts, a read ends or a waiter leaves, so waiters look again. */
    private val changes = MutableStateFlow(0L)

    /** Runs [read], about [address], in its address's turn. */
    suspend fun <T> paced(address: Address, read: suspend () -> T): T {
        var queued = false
        try {
            while (true) {
                val seen = changes.value
                val wait = lock.withLock {
                    val wait = admit(address)
                    if (wait == null && queued) {
                        leave(address)
                        queued = false
                    } else if (wait != null && !queued) {
                        waiters[address] = (waiters[address] ?: 0) + 1
                        queued = true
                    }
                    wait
                } ?: break
                withTimeoutOrNull(wait) { changes.first { it != seen } }
            }
        } finally {
            if (queued) {
                withContext(NonCancellable) {
                    lock.withLock { leave(address) }
                    changes.update { it + 1 }
                }
            }
        }
        // Admitted: the address's other waiting reads may join now.
        changes.update { it + 1 }
        try {
            return read()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    active--
                    if (active == 0) nextAt = clock.markNow() + gap()
                }
                changes.update { it + 1 }
            }
        }
    }

    /** One waiting read about [address] fewer. */
    private fun leave(address: Address) {
        val left = (waiters[address] ?: 0) - 1
        if (left > 0) waiters[address] = left else waiters.remove(address)
    }

    /** Null when a read about [address] may start now (and counts it); else how long to wait at most. */
    private fun admit(address: Address): Duration? {
        val othersWait = waiters.keys.any { it != address }
        val gapOver = nextAt?.hasPassedNow() != false
        if (current == address) {
            // The turn's address: joins a running turn, unless it is past its limit with others waiting.
            if (active > 0 && (!othersWait || turnStarted?.elapsedNow()?.let { it < maxTurn } != false)) {
                active++
                return null
            }
            // After its turn, with nobody else waiting: a new turn of its own, no gap needed.
            if (active == 0 && !othersWait) {
                turnStarted = clock.markNow()
                active++
                return null
            }
        }
        val head = waiters.keys.firstOrNull()
        if ((current == null || (active == 0 && gapOver)) && (head == null || head == address)) {
            current = address
            turnStarted = clock.markNow()
            active++
            return null
        }
        return if (active == 0 && !gapOver) {
            nextAt?.let { -it.elapsedNow() }?.coerceAtLeast(Duration.ZERO) ?: Duration.ZERO
        } else {
            Duration.INFINITE
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
