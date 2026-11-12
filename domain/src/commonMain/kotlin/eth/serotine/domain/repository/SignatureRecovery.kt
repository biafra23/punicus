package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Signature

/**
 * The port that tells who made a secp256k1 signature: what `ecrecover` tells a contract, for
 * checking a co-signer's signature of a Safe transaction before it is believed (CLAUDE.md, *Data
 * sources*). Public data in, an address out: no key is involved. :data implements it with
 * libsecp256k1, as `:domain` has no secp256k1 of its own. Never throws.
 *
 * Note: WebAuthn signatures (P-256 hardware-bound owners) are verified by the Safe's WebAuthn
 * signer proxy contract directly, not through signature recovery. This port is only for ECDSA
 * signatures.
 */
interface SignatureRecovery {
    /**
     * The address whose key made [signature] over [digest], signed as given and never hashed
     * again (as the Safe checks an owner's ECDSA signature of a transaction hash); null when it
     * recovers to no key.
     */
    fun recover(digest: Digest, signature: Signature): Address?
}
