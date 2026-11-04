package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import kotlinx.coroutines.flow.Flow

/**
 * The port for the engine's verified log index (issue #85; Myotis `docs/eth-getlogs-design.md`):
 * a watch-list of entries, each walked back from its own block over the verified headers, whose
 * logs the index then serves verified, and only inside the span it covers. :data implements it
 * over the Myotis engine (`eth.serotine.data.engine`), one index per network. Public chain data only. Never throws, but
 * for the caller's error [configure] names.
 */
interface LogIndex {

    /**
     * Adds [watch] to the entries [network]'s index follows, each from its [LogWatch.fromBlock], and drops
     * every entry [unwatch] names, with its coverage and its logs; entries named in neither are
     * left as they are. One entry named in both is the caller's error and throws. The index keeps
     * the lowest block an entry was ever asked from, so asking again from a later block changes
     * nothing. True once the engine has taken all of it, and an unwatch is kept across a restart;
     * false when it took none of it (no engine, still starting, or it refused), and the same
     * change may be asked again; so it is for a network no adapter serves here.
     */
    suspend fun configure(network: Network, watch: List<LogWatch>, unwatch: List<LogWatchKey>): Boolean

    /**
     * What [network]'s index covers of each entry it follows, polled, re-emitted when it
     * changes; or [LogCoverageReading.Unavailable] while the engine cannot say, or none runs for
     * [network] here.
     */
    fun observeCoverage(network: Network): Flow<LogCoverageReading>

    /**
     * Every log [query] selects, from [network]'s index: all of them, or none and why
     * ([LogsReading]). The index answers only a range it covers in full, never with the part of
     * one it has, and never one reaching below the block the entry is followed from
     * ([LogsReading.BeforeStart]).
     */
    suspend fun logs(network: Network, query: LogQuery): LogsReading
}

/** What an index entry follows: the logs of one contract, or every log that names one address. */
sealed interface LogWatchKey {

    /** The address the entry is about. */
    val address: Address

    /** Every log the contract at [address] emits: a Safe's own events. */
    data class Emitter(override val address: Address) : LogWatchKey

    /**
     * Every log, from any contract, that carries [address] as one of its indexed topics 1 to 3:
     * the token transfers to and from it, whichever token moved (Myotis #673).
     */
    data class Mention(override val address: Address) : LogWatchKey
}

/** An entry for the index: [key], followed from block [fromBlock] on, and nothing of it before. */
data class LogWatch(val key: LogWatchKey, val fromBlock: Long) {
    init {
        require(fromBlock >= 0) { "a block number is never negative" }
    }
}

/** What [LogIndex.observeCoverage] emits. */
sealed interface LogCoverageReading {

    /** The [entries] the index follows, each with its coverage; entries this port does not model are left out. */
    data class Read(val entries: Map<LogWatchKey, LogCoverage>) : LogCoverageReading

    /** The engine cannot say right now (none, starting, asleep). */
    data object Unavailable : LogCoverageReading
}

/**
 * One entry's coverage: it is followed from [fromBlock] on, and its logs are indexed over
 * [covered] (null: none yet). Complete once [covered] reaches down to [fromBlock].
 */
data class LogCoverage(val fromBlock: Long, val covered: LongRange?)

/**
 * The logs of blocks [fromBlock] to [toBlock] that [key]'s entry holds: for an
 * [LogWatchKey.Emitter], those its contract emitted, of the events in [topic0s] (empty: any);
 * for a [LogWatchKey.Mention], those carrying its address as topic [position] (1 to 3, and only
 * for a mention), of the events in [topic0s] (empty: any). [topic0s] are event signature hashes,
 * `0x` and 64 lower-case hex digits. The range is explicit at both ends: the index's answer does
 * not say which range it read.
 */
data class LogQuery(
    val key: LogWatchKey,
    val fromBlock: Long,
    val toBlock: Long,
    val topic0s: List<String> = emptyList(),
    val position: Int? = null,
) {
    init {
        require(fromBlock in 0..toBlock) { "a range from block $fromBlock to $toBlock" }
        require((key is LogWatchKey.Mention) == (position != null)) { "a topic position for a mention, only for one" }
        require(position == null || position in 1..3) { "a topic position is 1, 2 or 3" }
        require(topic0s.all { TOPIC.matches(it) }) { "a topic is 0x and 64 lower-case hex digits" }
    }

    private companion object {
        val TOPIC = Regex("^0x[0-9a-f]{64}$")
    }
}

/** What [LogIndex.logs] answers. */
sealed interface LogsReading {

    /** Every log the query selects, in block order, verified by the engine. */
    data class Logs(val logs: List<IndexedLog>) : LogsReading

    /**
     * The index does not cover the range yet: it covers [covered] (null: nothing yet). It may,
     * once its walk or the head follow gets there.
     */
    data class NotCovered(val covered: LongRange?) : LogsReading

    /** The index does not follow the query's entry. */
    data object NotWatched : LogsReading

    /**
     * The query reaches below [startBlock], the block the entry is followed from: the index never
     * answers for a block before it, and a range cut short there would read as complete.
     */
    data class BeforeStart(val startBlock: Long) : LogsReading

    /** No answer right now ([ReadUnavailable.NO_ENGINE], [ReadUnavailable.UNPROVEN], ...). */
    data class Unavailable(val reason: ReadUnavailable) : LogsReading
}

/**
 * One log the index served: [log] as it is in the receipt, in block [blockNumber] (hash
 * [blockHash]), emitted by transaction [transactionHash], at [logIndex] in the block. Hashes
 * are `0x` and 64 lower-case hex digits.
 */
data class IndexedLog(
    val log: ReceiptLog,
    val blockNumber: Long,
    val blockHash: String,
    val transactionHash: String,
    val logIndex: Int,
)
