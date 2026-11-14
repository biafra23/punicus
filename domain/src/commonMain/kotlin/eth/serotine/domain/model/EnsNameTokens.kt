package eth.serotine.domain.model

/**
 * The NFTs an ENS name can be, worked out from the name alone (issue #274): the .eth registrar's
 * token, whose id is the hash of the name's first label, and the NameWrapper's, whose id is the name's
 * namehash (ENSIP-1). Pure computation: what it gives are candidates, each proven held or not at the
 * head with the calls `AddNft` makes; a name that hashes to nothing held simply finds nothing.
 *
 * ENSv2's registry (Sepolia) is not covered: its token id carries a version in its low 32 bits, which
 * the name does not tell. Its tokens are still found in the log index, as before.
 */
object EnsNameTokens {

    /** The longest name taken, in characters: longer than any name a row shows. */
    const val MAX_NAME_LENGTH: Int = 255

    /**
     * [typed] as the hashes are computed over: trimmed, in lower case, and with every label non-empty
     * and free of spaces; null when it is no such name, or has fewer than two labels ("alice.eth" at
     * least). Lower case is ENS normalization (ENSIP-15) for the names written in ASCII; a name
     * normalized otherwise hashes to tokens nobody holds, and finds nothing.
     */
    fun normalized(typed: String): String? {
        val name = typed.trim().lowercase()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return null
        val labels = name.split('.')
        if (labels.size < 2 || labels.any { label -> label.isEmpty() || label.any { it.isWhitespace() } }) return null
        return name
    }

    /** The hash of [label] (its UTF-8 bytes), as a token id: the .eth registrar's id for "[label].eth". */
    fun labelHash(label: String): UInt256 = UInt256.fromBigEndian(keccak256(label.encodeToByteArray()))

    /** ENSIP-1's namehash of [name] ("alice.eth"), as a token id: the NameWrapper's id for it. */
    fun namehash(name: String): UInt256 = UInt256.fromBigEndian(
        name.split('.').foldRight(ByteArray(WORD)) { label, node ->
            keccak256(node + keccak256(label.encodeToByteArray()))
        },
    )

    /**
     * The tokens of [network]'s built-in ENS collections ([KnownNftCollections]) that [name] can be:
     * for a .eth name of the second level, the registrar's; for any name, the NameWrapper's. Empty
     * when [name] is not [normalized], and on a network with no ENS.
     */
    fun candidates(network: Network, name: String): List<NftRef> {
        val normal = normalized(name)?.takeIf { it == name } ?: return emptyList()
        val labels = normal.split('.')
        return KnownNftCollections.of(network).mapNotNull { collection ->
            when (collection.kind) {
                KnownNftKind.ENS_REGISTRAR -> if (labels.size == 2 && labels[1] == "eth") {
                    NftRef(collection.address, labelHash(labels[0]), NftStandard.ERC721)
                } else {
                    null
                }
                KnownNftKind.ENS_NAME_WRAPPER -> NftRef(collection.address, namehash(normal), NftStandard.ERC1155)
                KnownNftKind.ENS_V2_REGISTRY -> null
            }
        }
    }

    /**
     * The label of a .eth name among [names] whose hash is [tokenId], the id of a registrar token: the
     * registrar keeps only the hash, and a name the wallet has seen supplies the label, checked by
     * hashing. Only a label of letters, digits, `-` and `_` is given, so nothing invisible or moving
     * text reaches a screen; null when none fits.
     */
    fun registrarLabel(tokenId: UInt256, names: Collection<String>): String? = names.asSequence()
        .mapNotNull { name -> name.split('.').takeIf { it.size == 2 && it[1] == "eth" }?.get(0) }
        .filter { label -> label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' || it == '_' } }
        .firstOrNull { labelHash(it) == tokenId }

    private const val WORD = 32
}
