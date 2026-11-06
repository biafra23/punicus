package eth.serotine.ui.accountdetail

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.model.NftStandard
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.usecase.HeldNft
import eth.serotine.domain.usecase.NftHoldings
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.shortDigits

/**
 * [holdings] as the account screen draws them below its assets (docs/wallet-ux.md, *NFTs*; owner
 * rulings, 2026-11-05, #246): the built-in collections' NFTs shown, the unlisted ones under a row
 * open while [unlistedOpen], and what the list covers. While the account's network is off ([off],
 * its name; null while it is on), nothing is proven again: the NFTs last proven stay, said to be
 * from before and possibly changed since (CLAUDE.md, *Trust*: one older answer, labelled as one). An
 * ENS token's expiry is dated with [formatTime] and said to be past against [now] (unix seconds).
 */
internal fun nftSection(
    holdings: NftHoldings,
    unlistedOpen: Boolean,
    off: String? = null,
    formatTime: (Long) -> String = { it.toString() },
    now: Long = 0,
): NftSection {
    val toRow = { held: HeldNft -> row(held, formatTime, now) }
    val before = off?.let { "$it is off: proven before, may have changed since" }
    return when (holdings) {
        NftHoldings.NotFollowed -> NftSection(unlistedOpen = unlistedOpen, empty = NOT_FOLLOWED)
        is NftHoldings.Checking -> NftSection(
            unlistedOpen = unlistedOpen,
            empty = CHECKING,
            note = "Received since block ${grouped(holdings.start)}",
            simpleNote = SINCE_FOLLOWED,
        )
        NftHoldings.Unavailable -> NftSection(unlistedOpen = unlistedOpen, empty = UNAVAILABLE)
        is NftHoldings.Read -> {
            val (known, unlisted) = holdings.nfts.partition { it.collection.known != null }
            val unproven = "${holdings.unproven} not proven held yet".takeIf { holdings.unproven > 0 }
            val notChecked = "${holdings.notChecked} more not checked".takeIf { holdings.notChecked > 0 }
            val older = "still checking blocks ${grouped(holdings.start)} to ${grouped(holdings.checked.first - 1)}"
            NftSection(
                shown = known.map(toRow),
                unlisted = unlisted.map(toRow),
                unlistedOpen = unlistedOpen,
                empty = when {
                    holdings.nfts.isNotEmpty() -> null
                    // Some received are still to be proven: none can be said to be held, nor none.
                    holdings.unproven > 0 -> CHECKING
                    !holdings.complete -> NONE_YET
                    // Past the cap, some received were never proven either way.
                    holdings.notChecked > 0 -> NONE_AMONG_CHECKED
                    else -> NONE
                },
                note = listOfNotNull(
                    before,
                    "Received since block ${grouped(holdings.start)}",
                    older.takeUnless { holdings.complete },
                    unproven,
                    notChecked,
                ).joinToString(" · "),
                simpleNote = listOfNotNull(
                    before,
                    SINCE_FOLLOWED,
                    "still checking older blocks".takeUnless { holdings.complete },
                    unproven,
                    notChecked,
                ).joinToString(" · "),
            )
        }
    }
}

/**
 * One NFT's row: its collection's name, its id, an ERC-1155 amount above one, and its proof's block;
 * an ENS token's the name it stands for, its collection, and whether it is wrapped and when it expires.
 */
private fun row(held: HeldNft, formatTime: (Long) -> String, now: Long): NftRow {
    val collection = held.collection
    val ens = held.ens
    return NftRow(
        // An undecoded name by its hash: a label's, under .eth; a wrapped one's namehash, alone.
        title = ens?.let { it.name ?: "[" + shortHash(it.labelHash) + "]" + if (it.wrapped) "" else ".eth" }
            ?: collection.known?.name ?: collection.name ?: collection.symbol ?: UNNAMED,
        tokenId = if (ens != null) collection.known?.name ?: "ENS" else tokenId(held.nft.tokenId),
        amount = ("×" + shortDigits(held.amount)).takeIf { held.amount > UInt256.ONE },
        contract = Eip55.format(collection.address),
        provenAt = held.blockNumber?.let { "block ${grouped(it)}" },
        addedKey = held.takeIf { it.added }?.nft?.let(::nftKey),
        detail = ens?.let {
            listOfNotNull(
                "Wrapped".takeIf { _ -> it.wrapped },
                it.expiry?.let { expiry -> (if (expiry <= now) "expired " else "expires ") + formatTime(expiry) },
            ).joinToString(" · ").replaceFirstChar { c -> c.uppercase() }.ifEmpty { null }
        },
    )
}

/** [nft]'s key as a row carries it: "contract:id:standard", which [nftOfKey] reads back. */
internal fun nftKey(nft: NftRef): String = "${nft.collection.hex}:${nft.tokenId.toDecimal()}:${nft.standard.name}"

/** The NFT [key] names ([nftKey]), or null when it is no such key. */
internal fun nftOfKey(key: String): NftRef? {
    val (contract, id, standard) = key.split(':').takeIf { it.size == 3 } ?: return null
    return NftRef(
        Address.parse(contract) ?: return null,
        UInt256.parse(id) ?: return null,
        NftStandard.entries.find { it.name == standard } ?: return null,
    )
}

/** A token id as the user types it: a whole number, or `0x` and up to 64 hexadecimal digits; else null. */
internal fun parseTokenId(text: String): UInt256? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("0x")) return UInt256.parse(trimmed)
    val hex = trimmed.drop(2).lowercase()
    if (hex.isEmpty() || hex.length > 64 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    return UInt256.fromBigEndian(hex.padStart(64, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray())
}

/** A hash (`0x` and 64 hex digits) by its first six and last four digits: "27a0e0…0f90". */
private fun shortHash(hash: String): String = hash.removePrefix("0x").let { it.take(6) + "…" + it.takeLast(4) }

/** [id] as "#1234", or its first and last digits around an ellipsis where it is long ([shortDigits]). */
internal fun tokenId(id: UInt256): String = "#" + shortDigits(id)

private const val UNNAMED = "Unnamed collection"
private const val NONE = "No NFTs found"
private const val NONE_YET = "None found yet"
private const val NONE_AMONG_CHECKED = "None held among the NFTs checked"
private const val CHECKING = "Checking NFTs…"
private const val UNAVAILABLE = "NFTs cannot be read right now."
private const val NOT_FOLLOWED = "NFTs are found once the light client is synced."
private const val SINCE_FOLLOWED = "Received since this wallet began following this account"
