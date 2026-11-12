package eth.serotine.domain.model

/**
 * A WebAuthn-formatted P-256 signature for Safe transactions.
 *
 * WebAuthn signatures have a different format than ECDSA:
 * - authenticatorData: binary data from the hardware authenticator
 * - clientDataFields: JSON string with challenge, type, etc.
 * - r, s: ECDSA signature components (P-256 curve)
 *
 * Format: `authenticatorData ‖ clientDataFields ‖ r ‖ s`
 */
@ConsistentCopyVisibility
data class WebAuthnSignature private constructor(
    val authenticatorData: ByteArray,
    val clientDataFields: String,
    val r: String,
    val s: String,
) {
    init {
        require(r.length == 64) { "r must be 64 hex characters (32 bytes)" }
        require(s.length == 64) { "s must be 64 hex characters (32 bytes)" }
        require(SCALAR.matches(r)) { "r must be lowercase hex" }
        require(SCALAR.matches(s)) { "s must be lowercase hex" }
        // s must be in lower half of curve order for non-malleability
        require(s <= HALF_ORDER) { "s must be in lower half of curve order" }
    }

    /**
     * Encodes this signature as bytes for Safe's `execTransaction`:
     * `authenticatorData ‖ clientDataFields (as bytes) ‖ r ‖ s`
     */
    fun encode(): ByteArray {
        val clientDataBytes = clientDataFields.encodeToByteArray()
        return authenticatorData + clientDataBytes + r.hexToBytes() + s.hexToBytes()
    }

    companion object {
        private val SCALAR = Regex("^[0-9a-f]{64}$")
        private const val HALF_ORDER = "7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0"

        /**
         * Creates a WebAuthn signature from its components.
         *
         * @param authenticatorData The WebAuthn authenticator data
         * @param clientDataFields The additional client data JSON fields (without the standard "type" and "challenge")
         * @param r The ECDSA r component (64 hex chars)
         * @param s The ECDSA s component (64 hex chars, must be <= HALF_ORDER)
         */
        fun of(
            authenticatorData: ByteArray,
            clientDataFields: String,
            r: String,
            s: String,
        ): WebAuthnSignature = WebAuthnSignature(authenticatorData, clientDataFields, r, s)
    }
}
