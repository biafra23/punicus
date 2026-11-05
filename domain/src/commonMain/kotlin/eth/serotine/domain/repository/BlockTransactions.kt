package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256

/**
 * The port for the transactions in a block, as the embedded light client verifies them: the
 * block's header is the one its verified head chain names, and its body is checked against the
 * header's `transactionsRoot`. What Send from another wallet (issue #255) reads to find the
 * transfer another wallet sent, since nothing comes back through the payment link. :data
 * implements it over the Myotis engine. Never throws.
 */
interface BlockTransactions {

    /**
     * The newest block on [network] whose transactions can be read verified now, or why none can.
     * A [network] no adapter serves is refused ([ReadUnavailable.NO_ENGINE]).
     */
    suspend fun head(network: Network): HeadReading

    /**
     * Every transaction of block [number] on [network], in the block's order, or
     * [BlockReading.NotYet] for a block above the verified head.
     */
    suspend fun transactions(network: Network, number: Long): BlockReading
}

/** What [BlockTransactions.head] answers. */
sealed interface HeadReading {

    /** Block [number] is the newest whose transactions can be read verified. */
    data class At(val number: Long) : HeadReading

    /** No block can be read verified now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : HeadReading
}

/** What [BlockTransactions.transactions] answers. */
sealed interface BlockReading {

    /** The block's [transactions], verified against its transactions root. */
    data class Read(val transactions: List<ChainTransaction>) : BlockReading

    /** The block is above the verified head: there is no verified answer about it yet. */
    data object NotYet : BlockReading

    /** The block cannot be read verified now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : BlockReading
}

/**
 * One transaction of a verified block: its [hash] (`0x` and 64 lower-case hex digits), its sender
 * [from] (recovered from its signature), its recipient [to] (null for a contract's creation), the
 * [value] it sends in wei, and its call data [input] (`0x` and lower-case hex, `0x` for none).
 */
data class ChainTransaction(
    val hash: String,
    val from: Address,
    val to: Address?,
    val value: UInt256,
    val input: String,
)
