package eth.serotine.domain.repository

import eth.serotine.domain.model.KnownNftCollection
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NftRef

/**
 * The port for what an ENS collection's token stands for (issue #246; owner rulings, 2026-11-05 and
 * 2026-11-06): its name, its expiry, and whether it is wrapped, all read on chain with verified calls
 * and checked by hashing, never from ENS's metadata service. :data implements it over the Myotis engine
 * (`eth.serotine.data.engine`). Never throws.
 */
interface EnsNfts {

    /**
     * The name [nft], a token of the built-in ENS [collection] on [network], stands for:
     * - ENSv2's registry: the label its label store holds for the token, kept only when its hash, version
     *   bits aside, is the token's id; the registry's code must hash to [KnownNftCollection.codeHash]
     *   ([EnsNftReading.NotPinned] otherwise); the expiry, its `getExpiry`.
     * - The NameWrapper: `names(id)`, the DNS-encoded name, kept only when its namehash is the token's
     *   id; the expiry, its `getData`.
     * - The .eth registrar: the label from a `NameRegistered` log among [receiptLogs] (the verified logs
     *   of a transaction that moved the token to the account) whose hash is the token's id; the expiry,
     *   `nameExpires`.
     * A [network] no adapter serves here is refused ([ReadUnavailable.NO_ENGINE]).
     */
    suspend fun name(
        network: Network,
        collection: KnownNftCollection,
        nft: NftRef,
        receiptLogs: List<ReceiptLog> = emptyList(),
    ): EnsNftReading
}

/** What [EnsNfts.name] answers. */
sealed interface EnsNftReading {

    /**
     * The token is the name [name] ("multisig.eth"; null when no label could be found and checked, when
     * the screen shows its hash), whose label hash is [labelHash] (`0x` and 64 lower-case hex digits; for a
     * wrapped name not decoded, its namehash, the token's id),
     * registered until [expiry] (unix seconds; null when not said), [wrapped] by the NameWrapper or not,
     * read at block [blockNumber] (null: not said).
     */
    data class Named(
        val name: String?,
        val labelHash: String,
        val expiry: Long?,
        val wrapped: Boolean,
        val blockNumber: Long?,
    ) : EnsNftReading

    /** The collection's code is not the code it is pinned by: its tokens are not taken for ENS names. */
    data object NotPinned : EnsNftReading

    /** No verified answer right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : EnsNftReading
}
