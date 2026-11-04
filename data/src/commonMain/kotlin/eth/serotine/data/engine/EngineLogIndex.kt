package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.LogCoverage
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatch
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptLog
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What [EngineLogIndex] needs of the engine: its state, and its log index
 * (`io.myotis.api.ChainHandle.setLogIndexConfig` and `logIndexStatusJson`,
 * `VerifiedReads.getLogs`), each JSON in and out as the engine speaks it. The first and the
 * last block, bounded by the engine.
 */
internal interface LogIndexChain : VerifiedChain {

    /** Installs [configJson]; true once the engine took it. */
    fun setLogIndexConfig(configJson: String): Boolean

    /** The index's status JSON, an `{"error":…}` object, or null when there is no engine. */
    fun logIndexStatus(): String?

    /** The logs [filterJson] selects as a JSON array, an `{"error":…}` object, or null: no answer. */
    fun logs(filterJson: String): String?
}

/**
 * [LogIndex] over the engine's verified log index (Myotis `docs/eth-getlogs-design.md`). An
 * [LogWatchKey.Emitter] is an address entry; a [LogWatchKey.Mention] a topic entry of the address
 * left-padded to 32 bytes at positions 1 to 3, the engine's default and the only positions this
 * adapter ever asks for, so a later change never meets an entry under another restriction (which
 * the engine would answer by replacing the whole index). No entry is restricted by event, nor
 * named: a name would put the user's labels into the engine's files.
 *
 * A change is asked of the engine only while it can verify reads, as the engine holds a call
 * made earlier until it is ready. The coverage is polled every [pollInterval]; an entry this port
 * does not model (restricted, or a topic that is not an address at all three positions) is left
 * out of it. A query is asked of the engine only from its entry's block on, which the status says;
 * logs are taken only when every one of them is what the query selects, in its range: anything
 * else is no answer, never a part of one. A device without an engine ([chain] null) answers
 * [ReadUnavailable.NO_ENGINE].
 */
class EngineLogIndex internal constructor(
    private val served: Network,
    private val chain: LogIndexChain?,
    private val dispatcher: CoroutineDispatcher,
    private val pollInterval: Duration,
) : LogIndex {

    override suspend fun configure(network: Network, watch: List<LogWatch>, unwatch: List<LogWatchKey>): Boolean {
        val keys = watch.map { it.key }.toSet()
        require(unwatch.none { it in keys }) { "an entry both watched and unwatched" }
        if (network != served) return false
        if (watch.isEmpty() && unwatch.isEmpty()) return true
        val chain = chain ?: return false
        val config = buildJsonObject {
            put("enabled", true)
            putJsonArray("watch") {
                watch.distinctBy { it.key }.forEach { entry ->
                    addJsonObject {
                        when (val key = entry.key) {
                            is LogWatchKey.Emitter -> put("address", key.address.hex)
                            is LogWatchKey.Mention -> {
                                put("topic", topicOf(key.address))
                                putJsonArray("positions") { POSITIONS.forEach { add(it) } }
                            }
                        }
                        // The lowest block any entry of this key asks for.
                        put("fromBlock", watch.filter { it.key == entry.key }.minOf { it.fromBlock })
                    }
                }
            }
            putJsonArray("unwatch") { unwatch.distinct().forEach { add(it.wireKey()) } }
        }.toString()
        return withContext(dispatcher) {
            try {
                whyUnreadable(chain.snapshot()) == null && chain.setLogIndexConfig(config)
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive() // as in ProvenReads
                false
            }
        }
    }

    override fun observeCoverage(network: Network): Flow<LogCoverageReading> {
        if (network != served) return flowOf(LogCoverageReading.Unavailable)
        val chain = chain ?: return flowOf(LogCoverageReading.Unavailable)
        return chain.snapshots(pollInterval).map { snapshot ->
            val running = snapshot as? EngineSnapshot.Running
            if (running == null || running.paused) {
                LogCoverageReading.Unavailable
            } else {
                try {
                    chain.logIndexStatus()?.let(::coverageOf) ?: LogCoverageReading.Unavailable
                } catch (e: Throwable) {
                    currentCoroutineContext().ensureActive()
                    LogCoverageReading.Unavailable
                }
            }
        }.distinctUntilChanged().flowOn(dispatcher)
    }

    // The engine takes an entry's fromBlock as saying nothing comes before it, and answers a range
    // reaching below it from there on: asked first, so that such a range is refused, not cut short.
    override suspend fun logs(network: Network, query: LogQuery): LogsReading {
        if (network != served) return LogsReading.Unavailable(ReadUnavailable.NO_ENGINE)
        return chain.askVerified(dispatcher, LogsReading::Unavailable) { chain ->
            val coverage = chain.logIndexStatus()?.let(::coverageOf) as? LogCoverageReading.Read
                ?: return@askVerified LogsReading.Unavailable(ReadUnavailable.UNPROVEN)
            val start = coverage.entries[query.key]?.fromBlock ?: return@askVerified LogsReading.NotWatched
            if (query.fromBlock < start) return@askVerified LogsReading.BeforeStart(start)
            val answer = chain.logs(filterOf(query))
                ?: return@askVerified LogsReading.Unavailable(ReadUnavailable.UNPROVEN)
            readingOf(answer, query)
        }
    }

    /** The engine's filter for [query], both ends explicit. */
    private fun filterOf(query: LogQuery): String = buildJsonObject {
        put("fromBlock", quantity(query.fromBlock))
        put("toBlock", quantity(query.toBlock))
        val topic0 = if (query.topic0s.isEmpty()) JsonNull else JsonArray(query.topic0s.map(::JsonPrimitive))
        when (val key = query.key) {
            is LogWatchKey.Emitter -> {
                put("address", key.address.hex)
                if (query.topic0s.isNotEmpty()) putJsonArray("topics") { add(topic0) }
            }
            is LogWatchKey.Mention -> putJsonArray("topics") {
                add(topic0)
                repeat(requireNotNull(query.position) - 1) { add(JsonNull) }
                add(topicOf(key.address))
            }
        }
    }.toString()

    /** What the engine's [answer] to [query] says. */
    private fun readingOf(answer: String, query: LogQuery): LogsReading {
        val root = try {
            Json.parseToJsonElement(answer)
        } catch (e: Exception) {
            return LogsReading.Unavailable(ReadUnavailable.UNPROVEN)
        }
        if (root is JsonArray) {
            val logs = root.map { indexedLogOf(it)?.takeIf { log -> selects(query, log) } }
            return if (logs.any { it == null }) {
                LogsReading.Unavailable(ReadUnavailable.UNPROVEN)
            } else {
                LogsReading.Logs(logs.filterNotNull())
            }
        }
        val error = root as? JsonObject ?: return LogsReading.Unavailable(ReadUnavailable.UNPROVEN)
        if ((error["code"] as? JsonPrimitive)?.intOrNull == INVALID_PARAMS) {
            return LogsReading.Unavailable(ReadUnavailable.REFUSED)
        }
        val message = (error["error"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when {
            message.startsWith(NOT_INDEXED_YET) -> LogsReading.NotCovered(
                COVERED.find(message)?.let { it.groupValues[1].toLong()..it.groupValues[2].toLong() },
            )
            message.startsWith(NOTHING_INDEXED_YET) || message.startsWith(NO_COVERAGE_YET) ->
                LogsReading.NotCovered(null)
            message.startsWith(ADDRESS_UNWATCHED) || message.startsWith(TOPIC_UNWATCHED) -> LogsReading.NotWatched
            // Pinned where, or under signatures, the entry is not indexed for: asking again changes nothing.
            message.startsWith(TOPIC_UNINDEXED) || message.startsWith(SIGNATURE_UNINDEXED) ->
                LogsReading.Unavailable(ReadUnavailable.REFUSED)
            else -> LogsReading.Unavailable(ReadUnavailable.UNPROVEN)
        }
    }

    /** Whether [log] is one [query] selects: in its range, of its events, from or naming its address. */
    private fun selects(query: LogQuery, log: IndexedLog): Boolean {
        if (log.blockNumber !in query.fromBlock..query.toBlock) return false
        val topics = log.log.topics
        if (query.topic0s.isNotEmpty() && topics.firstOrNull() !in query.topic0s) return false
        return when (val key = query.key) {
            is LogWatchKey.Emitter -> log.log.address == key.address
            is LogWatchKey.Mention -> topics.getOrNull(requireNotNull(query.position)) == topicOf(key.address)
        }
    }

    /** One served log, or null when it is not one. */
    private fun indexedLogOf(element: JsonElement): IndexedLog? {
        val log = element as? JsonObject ?: return null
        fun text(name: String) = (log[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
        if ((log["removed"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val address = text("address")?.let(Address::parse) ?: return null
        val topics = (log["topics"] as? JsonArray)?.map { topic ->
            val word = (topic as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
            word?.takeIf(WORD::matches) ?: return null
        } ?: return null
        val data = text("data")?.takeIf(DATA::matches) ?: return null
        return IndexedLog(
            log = ReceiptLog(address, topics, data),
            blockNumber = text("blockNumber")?.let(::quantityOf) ?: return null,
            blockHash = text("blockHash")?.takeIf(WORD::matches) ?: return null,
            transactionHash = text("transactionHash")?.takeIf(WORD::matches) ?: return null,
            logIndex = text("logIndex")?.let(::quantityOf)?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null,
        )
    }

    /** The coverage of the entries this port models, from the engine's status JSON; null when it is not one. */
    private fun coverageOf(json: String): LogCoverageReading? {
        val root = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        if (root.containsKey("error")) return null
        val entries = root["entries"] as? JsonArray ?: return null
        val coverage = entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return null
            if ((entry["restricted"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            val key = keyOf(entry) ?: return@mapNotNull null
            val from = (entry["fromBlock"] as? JsonPrimitive)?.longOrNull ?: return null
            val low = (entry["coveredLow"] as? JsonPrimitive)?.longOrNull
            val high = (entry["coveredHigh"] as? JsonPrimitive)?.longOrNull
            key to LogCoverage(from, if (low != null && high != null && low <= high) low..high else null)
        }
        return LogCoverageReading.Read(coverage.toMap())
    }

    /** The key of a status entry this port models, or null. */
    private fun keyOf(entry: JsonObject): LogWatchKey? {
        val address = (entry["address"] as? JsonPrimitive)?.contentOrNull
        if (address != null) return Address.parse(address)?.let(LogWatchKey::Emitter)
        val topic = (entry["topic"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: return null
        val positions = (entry["positions"] as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull }
        if (positions != POSITIONS || !topic.startsWith(PADDING)) return null
        return Address.parse("0x" + topic.removePrefix(PADDING))?.let(LogWatchKey::Mention)
    }

    private fun LogWatchKey.wireKey(): String = when (this) {
        is LogWatchKey.Emitter -> address.hex
        is LogWatchKey.Mention -> topicOf(address)
    }

    private companion object {
        /** The positions a [LogWatchKey.Mention] is indexed at: every topic but the event's. */
        val POSITIONS = listOf(1, 2, 3)

        /** JSON-RPC's invalid params: a filter the engine refuses for good. */
        const val INVALID_PARAMS = -32602

        /** A word's leading 12 zero bytes before an address, with its `0x`. */
        const val PADDING = "0x000000000000000000000000"

        // The engine's refusals (`get_logs_json_impl`, myotis-engine/src/host.rs), by their opening words.
        const val NOT_INDEXED_YET = "requested range is not indexed yet"
        const val NOTHING_INDEXED_YET = "log index has not indexed any blocks yet"
        const val NO_COVERAGE_YET = "log index has no coverage yet"
        const val ADDRESS_UNWATCHED = "address is not on this node's log watch-list"
        const val TOPIC_UNWATCHED = "no topic this filter pins is on this node's log watch-list"
        const val TOPIC_UNINDEXED = "the watched topic is pinned only at positions"
        const val SIGNATURE_UNINDEXED = "topic is outside this node's indexed signatures"
        val COVERED = Regex("""\(covered: (\d+)-(\d+)\)""")

        val WORD = Regex("^0x[0-9a-f]{64}$")
        val DATA = Regex("^0x([0-9a-f]{2})*$")

        /** [address] as a 32-byte topic: left-padded with zeros. */
        fun topicOf(address: Address): String = PADDING + address.hex.removePrefix("0x")

        fun quantity(block: Long): String = "0x" + block.toString(16)

        /** A JSON-RPC quantity (`0x`-hex, lower-case here) as a number, or null when it is not one. */
        fun quantityOf(text: String): Long? =
            text.takeIf { it.startsWith("0x") && it.length in 3..17 }?.substring(2)?.toLongOrNull(16)
    }
}
