package eth.serotine.domain.repository

import eth.serotine.domain.model.Network

/**
 * The port that hands a signed transaction to the network: the embedded engine gossips it to
 * its peers (it never signs). :data implements it over the Myotis engine. Blocks on the
 * engine, bounded by it; never throws.
 */
interface Broadcaster {
    /**
     * Gossips [raw], a signed transaction's bytes as `eth_sendRawTransaction` takes them, to
     * [network]'s peers. Bytes signed for another chain are no transaction of [network]'s, and
     * a network no adapter serves here is not sent to ([Broadcast.Unavailable]).
     */
    suspend fun send(network: Network, raw: ByteArray): Broadcast
}

/** The outcome of [Broadcaster.send]. */
sealed interface Broadcast {

    /** At least one peer took it; [hash] (`0x` + 64 hex) is keccak256 of the raw bytes. */
    data class Sent(val hash: String) : Broadcast

    /**
     * Judged on verified state, it can never be mined as sent (the sender cannot pay, or its
     * nonce is used); [detail] is the engine's reason. Nothing was broadcast.
     */
    data class Rejected(val detail: String) : Broadcast

    /**
     * The bytes are no transaction the engine relays ([detail]). Nothing was broadcast, and
     * asking again changes nothing.
     */
    data class Refused(val detail: String) : Broadcast

    /** Not sent now (no peer, the light client not running): it may be sent again. */
    data class Unavailable(val detail: String?) : Broadcast
}
