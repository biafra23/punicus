package eth.serotine.domain.repository

import eth.serotine.domain.model.SigningRequest

/**
 * The port for the text a [SigningRequest] travels as between devices, in a QR code or through
 * the platform's share sheet (CLAUDE.md, *Data sources*: no off-chain service). The text is
 * versioned and carries the raw fields alone; [decode] is strict, since what it reads came from
 * another device and is untrusted until the chain has been asked. :data implements it for every
 * network the app knows ([eth.serotine.domain.model.Network]); whether a request is for the Safe
 * it is imported to, on that Safe's network, is the importing use case's check. Never throws.
 */
interface SigningRequestCodec {

    /** [request] as text: the same request always gives the same text. */
    fun encode(request: SigningRequest): String

    /**
     * The request [text] carries, or why it carries none: anything that is not exactly a request of a version this
     * build reads, for a network the app knows, is refused, never repaired. What it decodes is not checked against the
     * chain here.
     */
    fun decode(text: String): SigningRequestDecoding
}

/** The outcome of [SigningRequestCodec.decode]. */
sealed interface SigningRequestDecoding {

    /** [request], decoded. */
    data class Decoded(val request: SigningRequest) : SigningRequestDecoding

    /** Not a signing request: not one of this format, a field out of its range, or text too long to be one. */
    data object Malformed : SigningRequestDecoding

    /** A signing request of [version], which this build does not read. */
    data class UnknownVersion(val version: Int) : SigningRequestDecoding

    /** A signing request for the chain [chainId], which is no network this wallet supports. */
    data class UnknownChain(val chainId: Long) : SigningRequestDecoding
}
