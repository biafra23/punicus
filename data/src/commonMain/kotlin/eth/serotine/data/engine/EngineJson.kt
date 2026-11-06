package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.ChainTransaction
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeQuote
import eth.serotine.domain.repository.ReceiptLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The engine's JSON answers (fee history, receipts, block bodies), read strictly: a field missing or
 * malformed is no answer.
 */
internal object EngineJson {

    /** The fee levels' percentiles, in [FeeLevel] order. */
    val PERCENTILES = doubleArrayOf(10.0, 50.0, 90.0)

    /**
     * A [FeeQuote] from an `eth_feeHistory` answer asked with [PERCENTILES]: the next base fee is
     * the history's last `baseFeePerGas` (the one after its newest block), and each level's tip
     * the median, over the blocks, of its percentile's reward, never below [MIN_TIP] and never
     * below the slower level's. Null when the answer is not one.
     */
    fun feeQuote(json: String): FeeQuote? = try {
        quoteOf(Json.parseToJsonElement(json).jsonObject)
    } catch (e: Exception) {
        null
    }

    private fun quoteOf(root: JsonObject): FeeQuote? {
        val baseFees = root.getValue("baseFeePerGas").jsonArray.map { quantity(it) ?: return null }
        val oldest = quantity(root.getValue("oldestBlock"))?.toLongOrNull() ?: return null
        // The percentiles were asked for: an answer without their rewards is no answer.
        val rewards = root["reward"]?.jsonArray?.map { block ->
            block.jsonArray.map { quantity(it) ?: return null }
        } ?: return null
        if (baseFees.size < 2 || rewards.size != baseFees.size - 1) return null
        if (rewards.any { it.size != PERCENTILES.size }) return null
        val newest = oldest + baseFees.size - 2
        var floor = MIN_TIP
        val tips = FeeLevel.entries.mapIndexed { index, level ->
            val paid = rewards.map { UInt256.of(it[index]) }.sorted()
            val median = if (paid.isEmpty()) MIN_TIP else paid[paid.size / 2]
            val tip = maxOf(median, floor)
            floor = tip
            level to tip
        }.toMap()
        return FeeQuote(UInt256.of(baseFees.last()), tips, newest)
    }

    /**
     * A receipt's verdict: null for the "null" literal (no verified block includes it yet). Its
     * logs are read as a whole or not at all: one that is malformed leaves [Receipt.logs] null,
     * and the verdict stands.
     */
    fun receipt(json: String): Receipt? {
        if (json.trim() == "null") return null
        val root = Json.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("not a receipt")
        val block = quantity(root.getValue("blockNumber"))?.toLongOrNull() ?: throw IllegalArgumentException("no block")
        val status = quantity(root.getValue("status")) ?: throw IllegalArgumentException("no status")
        return Receipt(block, succeeded = status == "1", logs = logsOf(root["logs"]))
    }

    /**
     * The transactions of block [number] from a `getBlockByNumber` answer with full transactions:
     * null for the "null" literal (the engine does not hold the block yet). A block that is not the
     * one asked for, or a transaction without a well-formed hash, sender, value or input, is no
     * answer: it throws, and the caller reads it as unproven. A contract's creation has no `to`.
     */
    fun blockTransactions(json: String, number: Long): List<ChainTransaction>? {
        if (json.trim() == "null") return null
        val root = Json.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("not a block")
        val answered = quantity(root.getValue("number"))?.toLongOrNull() ?: throw IllegalArgumentException("no number")
        require(answered == number) { "block $answered, not $number" }
        val transactions = root.getValue("transactions") as? JsonArray ?: throw IllegalArgumentException("no body")
        return transactions.map { entry ->
            val tx = entry as? JsonObject ?: throw IllegalArgumentException("a transaction hash, not its body")
            val hash = text(tx["hash"])?.lowercase()?.takeIf(TOPIC::matches) ?: throw IllegalArgumentException("hash")
            val from = text(tx["from"])?.let(Address::parse) ?: throw IllegalArgumentException("from")
            val to = when (val field = tx["to"]) {
                null, JsonNull -> null
                else -> text(field)?.let(Address::parse) ?: throw IllegalArgumentException("to")
            }
            val value = quantity(tx.getValue("value")) ?: throw IllegalArgumentException("value")
            val input = text(tx["input"])?.lowercase()?.takeIf(DATA::matches) ?: throw IllegalArgumentException("input")
            ChainTransaction(hash, from, to, UInt256.of(value), input)
        }
    }

    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

    /** A receipt's block, outcome and [logs] (null when it carried none that could be read). */
    data class Receipt(val blockNumber: Long, val succeeded: Boolean, val logs: List<ReceiptLog>? = null)

    private fun logsOf(element: JsonElement?): List<ReceiptLog>? {
        val array = element as? JsonArray ?: return null
        return array.map { entry ->
            val log = entry as? JsonObject ?: return null
            val address = (log["address"] as? JsonPrimitive)?.contentOrNull?.let(Address::parse) ?: return null
            val topics = (log["topics"] as? JsonArray)?.map { topic ->
                (topic as? JsonPrimitive)?.contentOrNull?.lowercase()?.takeIf(TOPIC::matches) ?: return null
            } ?: return null
            val data = (log["data"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.takeIf(DATA::matches)
                ?: return null
            ReceiptLog(address, topics, data)
        }
    }

    private val TOPIC = Regex("^0x[0-9a-f]{64}$")
    private val DATA = Regex("^0x([0-9a-f]{2})*$")

    /** A JSON-RPC quantity (`0x`-hex) as a decimal string, or null when it is not one. */
    private fun quantity(element: JsonElement): String? {
        val text = (element as? JsonPrimitive)?.contentOrNull ?: return null
        if (!text.startsWith("0x") || text.length < 3 || text.length > 66) return null
        val digits = text.substring(2)
        if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val even = digits.padStart(digits.length + digits.length % 2, '0')
        return UInt256.fromBigEndian(even.chunked(2).map { it.toInt(16).toByte() }.toByteArray()).toDecimal()
    }

    /** The least tip a level offers: 0.001 gwei, so that a fee history of empty blocks still pays something. */
    val MIN_TIP: UInt256 = UInt256.of(1_000_000L)
}
