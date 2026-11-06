package eth.serotine.domain.model

/** Which standard an NFT follows: ERC-721 (one holder per token) or ERC-1155 (an amount per holder). */
enum class NftStandard { ERC721, ERC1155 }

/** One NFT: the token [tokenId] of the collection at [collection], which follows [standard] (issue #246). */
data class NftRef(val collection: Address, val tokenId: UInt256, val standard: NftStandard)

/** [amount] of the ERC-1155 token [id] of the contract [token], moved from [from] to [to]. */
data class Erc1155Transfer(
    val token: Address,
    val from: Address,
    val to: Address,
    val id: UInt256,
    val amount: UInt256,
)

/**
 * ERC-1155's `TransferSingle` and `TransferBatch` events, read strictly: the operator, the sender and
 * the receiver indexed as topics 1 to 3, the ids and amounts in the data. Anything else is no ERC-1155
 * transfer.
 */
object Erc1155Transfers {

    /** `keccak256("TransferSingle(address,address,address,uint256,uint256)")`. */
    const val TRANSFER_SINGLE: String = "0xc3d58168c5ae7397731d063d5bbf3d657854427343f4c083240f7aacaa2d0f62"

    /** `keccak256("TransferBatch(address,address,address,uint256[],uint256[])")`. */
    const val TRANSFER_BATCH: String = "0x4a39dc06d4c0dbc64b70af90fd698a233a518aa5d07e595d983b8c0526c8f7fb"

    /** The most ids one batch is read with: a log naming more is not read at all, rather than read in part. */
    const val MAX_BATCH: Int = 256

    /**
     * The moves a log the contract [token] emitted with [topics] (each `0x` and 64 lower-case hex digits,
     * the signature hash first) and [data] (`0x` and lower-case hex) records, when it is a
     * `TransferSingle` or a `TransferBatch`; null for anything else, or one out of shape.
     */
    fun decode(token: Address, topics: List<String>, data: String): List<Erc1155Transfer>? {
        val kind = topics.firstOrNull()
        if ((kind != TRANSFER_SINGLE && kind != TRANSFER_BATCH) || topics.size != 4) return null
        val words = topics.drop(1).map { topic ->
            topic.takeIf { it.startsWith("0x") }?.let { AbiWords.returnedWord(it.removePrefix("0x")) } ?: return null
        }
        AbiWords.address(words[0]) ?: return null // the operator: an address, though not shown
        val from = AbiWords.address(words[1]) ?: return null
        val to = AbiWords.address(words[2]) ?: return null
        val bytes = data.takeIf { it.startsWith("0x") }?.let { AbiWords.returned(it.removePrefix("0x")) } ?: return null
        return if (kind == TRANSFER_SINGLE) {
            val (id, amount) = AbiWords.words(bytes, 2) ?: return null
            listOf(Erc1155Transfer(token, from, to, AbiWords.uint(id), AbiWords.uint(amount)))
        } else {
            batch(bytes)?.map { (id, amount) -> Erc1155Transfer(token, from, to, id, amount) }
        }
    }

    /**
     * A `TransferBatch`'s data, `(uint256[] ids, uint256[] values)`, in the one layout the ABI encoder
     * writes: the ids' offset two words in, the values' right after the ids, both arrays of one length
     * of at most [MAX_BATCH], and nothing after them.
     */
    private fun batch(bytes: ByteArray): List<Pair<UInt256, UInt256>>? {
        val word = AbiWords.WORD
        if (bytes.size < 4 * word || bytes.size % word != 0) return null
        val all = AbiWords.words(bytes, bytes.size / word) ?: return null
        if (AbiWords.long(all[0]) != 2L * word) return null
        val count = AbiWords.long(all[2])?.takeIf { it in 0..MAX_BATCH.toLong() }?.toInt() ?: return null
        val values = 3 + count
        if (AbiWords.long(all[1]) != values.toLong() * word) return null
        if (all.size != values + 1 + count || AbiWords.long(all[values]) != count.toLong()) return null
        return List(count) { AbiWords.uint(all[3 + it]) to AbiWords.uint(all[values + 1 + it]) }
    }
}

/** What a collection the wallet knows is (issue #246): the ENS contracts whose tokens are names. */
enum class KnownNftKind {
    /** The .eth BaseRegistrar: an ERC-721 whose token id is the label's hash. */
    ENS_REGISTRAR,

    /** ENS's NameWrapper: an ERC-1155 whose token id is the name's namehash. */
    ENS_NAME_WRAPPER,

    /**
     * ENSv2's .eth registry: an ERC-1155 whose token id is the label's hash with its low 32 bits a
     * version, and whose label store (`LABEL_STORE()`) holds each label in clear.
     */
    ENS_V2_REGISTRY,
}

/**
 * A collection built into the wallet: [address] on its network, of [standard], named [name], whose
 * tokens are [kind]; where [codeHash] is set (lower-case hex, no `0x`), it is that collection only
 * while the code at [address] is proven to hash to it, and unlisted otherwise.
 */
data class KnownNftCollection(
    val address: Address,
    val standard: NftStandard,
    val name: String,
    val kind: KnownNftKind,
    val codeHash: String? = null,
)

/**
 * The NFT collections built into the wallet, per network (owner ruling, 2026-11-05, #246): shown
 * openly, while any other collection is unlisted, since anyone can send an NFT to any address.
 * Today ENS's: the .eth BaseRegistrar, `0x57f1887a8BF19b14fC0dF6Fd9B2acc9Af147eA85` on Ethereum
 * and Sepolia alike, and the NameWrapper, `0xD4416b13d2b3a9aBae7AcD5D6C2BbDBE25686401` on Ethereum
 * and `0x0635513f179D50A207757E05759CbD106d7dFcE8` on Sepolia. These are the addresses Myotis pins
 * (`io.myotis.ens.EnsAddresses`, checked against each chain's registry). Gnosis Chain has no ENS.
 *
 * Sepolia runs on ENSv2, and a name registered there now is a token of ENSv2's .eth registry
 * (owner ruling, 2026-11-06, #246): `0xd4ebcbbdf463c9c45784603db0ddd499bc44a8b4`, from ENS's
 * redeploy of 2026-10-01 (ensdomains/contracts-v2 #460, docs.ens.domains/learn/deployments). Read
 * 2026-11-06 at Sepolia block 12,075,044: it answers `supportsInterface` for ERC-1155 and not
 * ERC-721, its parent's label is "eth", its `LABEL_STORE()` is
 * `0xed8246ff02203a4d4cb262bd78beaa7408a57cae`, and its code hashes to [ENS_V2_REGISTRY_CODE_HASH],
 * which it is pinned by: a redeploy at another address is no longer this collection, and its NFTs show
 * as unlisted until the pin is updated.
 */
object KnownNftCollections {

    private val REGISTRAR = Address.of("0x57f1887a8bf19b14fc0df6fd9b2acc9af147ea85")

    private fun registrar() = KnownNftCollection(REGISTRAR, NftStandard.ERC721, "ENS", KnownNftKind.ENS_REGISTRAR)

    private fun wrapper(address: String) =
        KnownNftCollection(Address.of(address), NftStandard.ERC1155, "ENS (wrapped)", KnownNftKind.ENS_NAME_WRAPPER)

    /** The code hash Sepolia's ENSv2 .eth registry is pinned by. */
    const val ENS_V2_REGISTRY_CODE_HASH: String = "ddb7698fc2857f7a9e726491025fead241791f113a19714149db8cbf87688e98"

    private fun ensV2Registry(address: String) = KnownNftCollection(
        Address.of(address),
        NftStandard.ERC1155,
        "ENS",
        KnownNftKind.ENS_V2_REGISTRY,
        ENS_V2_REGISTRY_CODE_HASH,
    )

    /** The collections built in on [network]. */
    fun of(network: Network): List<KnownNftCollection> = when (network) {
        Network.ETHEREUM -> listOf(registrar(), wrapper("0xd4416b13d2b3a9abae7acd5d6c2bbdbe25686401"))
        Network.SEPOLIA -> listOf(
            ensV2Registry("0xd4ebcbbdf463c9c45784603db0ddd499bc44a8b4"),
            registrar(),
            wrapper("0x0635513f179d50a207757e05759cbd106d7dfce8"),
        )
        Network.GNOSIS -> emptyList()
    }

    /** The collection at [address] on [network] when it is built in, else null. */
    fun find(network: Network, address: Address): KnownNftCollection? = of(network).find { it.address == address }
}
