package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.data.logs.LogSink
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.LogLevel
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * [inner] with each request it is asked written to [log] under [SOURCE], named after the
 * `io.myotis.api` call it makes: at [LogLevel.INFO] the name, whether it was answered (ok) or
 * not (failed, with the engine's reason when it gives one) and how long it took; at
 * [LogLevel.DEBUG] also what was asked and what came back, each cut at [MAX_DETAIL] characters.
 * Failed means no answer: none at all, the engine's refusal or rejection, or an exception, which
 * is recorded and thrown on. A verified revert is an answer, and so is a status read of an
 * engine still starting. Nothing is built for a request the log would not keep.
 *
 * What it records is public chain data and this node's own requests: addresses, calls, signed
 * transactions (public once broadcast) and the engine's answers. No key ever reaches a chain.
 */
internal class LoggedChain(
    private val inner: EngineChain,
    private val log: LogSink,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : EngineChain {

    override fun snapshot(): EngineSnapshot =
        request("status", { "" }, ::notRunning) { inner.snapshot() }

    override fun proveBalance(address: Address): ProvenBalance? =
        request("requestAccount", { "address=${address.hex}" }, ::answered) { inner.proveBalance(address) }

    override fun proveCode(address: Address): ProvenCode? =
        request("requestAccount, getCode", { "address=${address.hex}" }, ::answered) { inner.proveCode(address) }

    override fun call(to: Address, data: ByteArray): CallOutcome? =
        request("callDetailed", { "to=${to.hex}, data=${data.hex()}" }, ::callAnswered) { inner.call(to, data) }

    override fun proveSlot(address: Address, slot: Long, holder: Address?): ProvenSlot? = request(
        "getStorageProof",
        { "address=${address.hex}, slot=$slot" + (holder?.let { ", holder=${it.hex}" } ?: "") },
        ::answered,
    ) { inner.proveSlot(address, slot, holder) }

    override fun storageAt(address: Address, slot: ByteArray): String? =
        request("getStorageAt", { "address=${address.hex}, slot=${slot.hex()}" }, ::answered) {
            inner.storageAt(address, slot)
        }

    override fun blockByNumber(number: Long): String? =
        request("getBlockByNumber", { "block=0x${number.toString(16)}, full=false" }, ::answered) {
            inner.blockByNumber(number)
        }

    override fun pendingNonce(address: Address): Long? =
        request("getTransactionCount", { "address=${address.hex}, block=pending" }, ::answered) {
            inner.pendingNonce(address)
        }

    override fun minedNonce(address: Address): Long? =
        request("getTransactionCount", { "address=${address.hex}, block=latest" }, ::answered) {
            inner.minedNonce(address)
        }

    override fun feeHistory(blocks: Int, percentiles: DoubleArray): String? = request(
        "feeHistory",
        { "blocks=$blocks, block=latest, percentiles=${percentiles.joinToString(prefix = "[", postfix = "]")}" },
        ::answered,
    ) { inner.feeHistory(blocks, percentiles) }

    override fun estimateGas(from: Address, to: Address, valueWei: String, data: ByteArray): EstimateAnswer = request(
        "estimateGasDetailed",
        { "from=${from.hex}, to=${to.hex}, value=$valueWei wei, data=${data.hex()}" },
        { answer ->
            when (answer) {
                is EstimateAnswer.Gas, is EstimateAnswer.Reverted, is EstimateAnswer.Infeasible -> null
                is EstimateAnswer.Refused -> "refused: ${answer.detail}"
                is EstimateAnswer.Unavailable -> answer.detail ?: ""
            }
        },
    ) { inner.estimateGas(from, to, valueWei, data) }

    override fun callFrom(from: Address, to: Address, valueWei: String, data: ByteArray): CallOutcome? = request(
        "callDetailed",
        { "from=${from.hex}, to=${to.hex}, value=$valueWei wei, data=${data.hex()}" },
        ::callAnswered,
    ) { inner.callFrom(from, to, valueWei, data) }

    override fun sendRaw(raw: ByteArray): SendAnswer = request(
        "sendRawTransactionDetailed",
        { "raw=${raw.hex()}" },
        { answer ->
            when (answer) {
                is SendAnswer.Sent -> null
                is SendAnswer.Rejected -> "rejected: ${answer.detail}"
                is SendAnswer.Refused -> "refused: ${answer.detail}"
                is SendAnswer.Unavailable -> answer.detail ?: ""
            }
        },
    ) { inner.sendRaw(raw) }

    override fun receipt(hash: String): String? =
        request("getTransactionReceipt", { "hash=$hash" }, ::answered) { inner.receipt(hash) }

    override fun resolveName(name: String): NameAnswer =
        request("ens.resolveAddress", { "name=$name, root=FINALIZED" }, { it.error }) {
            inner.resolveName(name)
        }

    override fun setLogIndexConfig(configJson: String): Boolean =
        request("setLogIndexConfig", { "config=$configJson" }, { if (it) null else "not taken" }) {
            inner.setLogIndexConfig(configJson)
        }

    override fun logIndexStatus(): String? =
        request("logIndexStatusJson", { "" }, ::jsonAnswered) { inner.logIndexStatus() }

    override fun logs(filterJson: String): String? =
        request("getLogs", { "filter=$filterJson" }, ::jsonAnswered) { inner.logs(filterJson) }

    /**
     * [block], the request [name], recorded with what [failure] says of its answer: null when it
     * is one, else why not ("" for no reason given). [params] says what was asked, built only
     * when the log keeps [LogLevel.DEBUG]; and nothing at all is built when it keeps nothing.
     */
    private inline fun <T> request(name: String, params: () -> String, failure: (T) -> String?, block: () -> T): T {
        if (!log.isLoggable(LogLevel.INFO)) return block()
        val started = timeSource.markNow()
        val answer = try {
            block()
        } catch (e: Throwable) {
            record(name, started, e::class.simpleName ?: "an exception", params) { "threw $e" }
            throw e
        }
        record(name, started, failure(answer), params) { answer.toString() }
        return answer
    }

    private inline fun record(
        name: String,
        started: TimeMark,
        failure: String?,
        params: () -> String,
        response: () -> String,
    ) {
        val took = started.elapsedNow().inWholeMilliseconds
        val result = when {
            failure == null -> "ok"
            failure.isEmpty() -> "failed"
            else -> "failed (${failure.take(MAX_REASON)})"
        }
        val line = LogLevel.INFO to "$name: $result, $took ms"
        // The detail first, and the two in one write: newest first, the Debug tab shows the detail
        // right under the line it belongs to, whatever other requests run at the same time.
        val entries = if (log.isLoggable(LogLevel.DEBUG)) {
            listOf(LogLevel.DEBUG to "$name(${params().cut()}) → ${response().cut()}", line)
        } else {
            listOf(line)
        }
        log.log(SOURCE, entries)
    }

    private fun answered(answer: Any?): String? = if (answer != null) null else ""

    private fun callAnswered(answer: CallOutcome?): String? = when (answer) {
        is CallOutcome.Returned, is CallOutcome.Reverted -> null
        CallOutcome.Refused -> "refused"
        null -> ""
    }

    private fun jsonAnswered(answer: String?): String? = when {
        answer == null -> ""
        answer.trimStart().startsWith("{\"error\"") -> answer.trim()
        else -> null
    }

    private fun ByteArray.hex(): String = "0x" + toHex()

    private fun String.cut(): String = if (length <= MAX_DETAIL) this else take(MAX_DETAIL) + "… ($length characters)"

    companion object {
        /** The source of every record this writes. */
        const val SOURCE = "Myotis"

        /** The most of what was asked, and of what came back, one record keeps. */
        const val MAX_DETAIL = 2_000

        /** The most of the engine's reason for a failure an INFO line keeps. */
        const val MAX_REASON = 200
    }
}

/** What the log says of a status read that found no light client: none on the device, or a start that failed. */
private fun notRunning(snapshot: EngineSnapshot): String? = when (snapshot) {
    EngineSnapshot.Down -> "no engine"
    EngineSnapshot.Failed -> "start failed"
    else -> null
}
