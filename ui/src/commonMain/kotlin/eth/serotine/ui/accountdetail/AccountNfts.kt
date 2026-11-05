package eth.serotine.ui.accountdetail

import eth.serotine.domain.model.UInt256
import eth.serotine.domain.usecase.HeldNft
import eth.serotine.domain.usecase.NftHoldings
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.grouped

/**
 * [holdings] as the account screen draws them below its assets (docs/wallet-ux.md, *NFTs*; owner
 * rulings, 2026-11-05, #246): the built-in collections' NFTs shown, the unlisted ones under a row
 * open while [unlistedOpen], and what the list covers. While the account's network is off ([off],
 * its name; null while it is on), nothing is proven again: the NFTs last proven stay, said to be
 * from before and possibly changed since (CLAUDE.md, *Trust*: one older answer, labelled as one).
 */
internal fun nftSection(holdings: NftHoldings, unlistedOpen: Boolean, off: String? = null): NftSection {
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
                shown = known.map(::row),
                unlisted = unlisted.map(::row),
                unlistedOpen = unlistedOpen,
                empty = when {
                    holdings.nfts.isNotEmpty() -> null
                    // Some received are still to be proven: none can be said to be held, nor none.
                    holdings.unproven > 0 -> CHECKING
                    !holdings.complete -> NONE_YET
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

/** One NFT's row: its collection's name, its id, an ERC-1155 amount above one, and its proof's block. */
private fun row(held: HeldNft): NftRow {
    val collection = held.collection
    return NftRow(
        title = collection.known?.name ?: collection.name ?: collection.symbol ?: UNNAMED,
        tokenId = "#" + shortened(held.nft.tokenId),
        amount = ("×" + shortened(held.amount)).takeIf { held.amount > UInt256.ONE },
        contract = Eip55.format(collection.address),
        provenAt = held.blockNumber?.let { "block ${grouped(it)}" },
    )
}

/** [id] as "#1234", or its first and last digits around an ellipsis where it is long: an ENS id has 77. */
internal fun tokenId(id: UInt256): String = "#" + shortened(id)

/** [number]'s digits, or its first six and last four around an ellipsis past [MAX_DIGITS]. */
private fun shortened(number: UInt256): String {
    val digits = number.toDecimal()
    return if (digits.length <= MAX_DIGITS) digits else digits.take(6) + "…" + digits.takeLast(4)
}

private const val MAX_DIGITS = 12
private const val UNNAMED = "Unnamed collection"
private const val NONE = "No NFTs found"
private const val NONE_YET = "None found yet"
private const val CHECKING = "Checking NFTs…"
private const val UNAVAILABLE = "NFTs cannot be read right now."
private const val NOT_FOLLOWED = "NFTs are found once the light client is synced."
private const val SINCE_FOLLOWED = "Received since this wallet began following this account"
