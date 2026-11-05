package eth.serotine.ui.accountdetail

import eth.serotine.domain.model.UInt256
import eth.serotine.domain.usecase.HeldNft
import eth.serotine.domain.usecase.NftHoldings
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.grouped

/**
 * [holdings] as the account screen draws them below its assets (docs/wallet-ux.md, *NFTs*; owner
 * rulings, 2026-11-05, #246): the built-in collections' NFTs shown, the unlisted ones under a row
 * open while [unlistedOpen], and what the list covers.
 */
internal fun nftSection(holdings: NftHoldings, unlistedOpen: Boolean): NftSection = when (holdings) {
    NftHoldings.NotFollowed -> NftSection(empty = NOT_FOLLOWED)
    is NftHoldings.Checking -> NftSection(
        empty = CHECKING,
        note = "Received since block ${grouped(holdings.start)}",
        simpleNote = SINCE_ADDED,
    )
    NftHoldings.Unavailable -> NftSection(empty = UNAVAILABLE)
    is NftHoldings.Read -> {
        val (known, unlisted) = holdings.nfts.partition { it.collection.known != null }
        val waiting = listOfNotNull(
            "still checking blocks ${grouped(holdings.start)} to ${grouped(holdings.checked.first - 1)}"
                .takeUnless { holdings.complete },
            "${holdings.unproven} not proven held yet".takeIf { holdings.unproven > 0 },
            "${holdings.notChecked} more not checked".takeIf { holdings.notChecked > 0 },
        )
        val simpleWaiting = listOfNotNull(
            "still checking older blocks".takeUnless { holdings.complete },
            "${holdings.unproven} not proven held yet".takeIf { holdings.unproven > 0 },
            "${holdings.notChecked} more not checked".takeIf { holdings.notChecked > 0 },
        )
        NftSection(
            shown = known.map(::row),
            unlisted = unlisted.map(::row),
            unlistedOpen = unlistedOpen,
            empty = NONE.takeIf { holdings.nfts.isEmpty() },
            note = (listOf("Received since block ${grouped(holdings.start)}") + waiting).joinToString(" · "),
            simpleNote = (listOf(SINCE_ADDED) + simpleWaiting).joinToString(" · "),
        )
    }
}

/** One NFT's row: its collection's name, its id, and an ERC-1155 amount above one. */
private fun row(held: HeldNft): NftRow {
    val collection = held.collection
    return NftRow(
        title = collection.known?.name ?: collection.name ?: collection.symbol ?: UNNAMED,
        tokenId = tokenId(held.nft.tokenId),
        amount = "×${held.amount}".takeIf { held.amount > UInt256.ONE },
        contract = Eip55.format(collection.address),
    )
}

/** [id] as "#1234", or its first and last digits around an ellipsis where it is long: an ENS id has 77. */
internal fun tokenId(id: UInt256): String {
    val digits = id.toDecimal()
    return "#" + if (digits.length <= MAX_ID_DIGITS) digits else digits.take(6) + "…" + digits.takeLast(4)
}

private const val MAX_ID_DIGITS = 12
private const val UNNAMED = "Unnamed collection"
private const val NONE = "No NFTs found"
private const val CHECKING = "Checking NFTs…"
private const val UNAVAILABLE = "NFTs cannot be read right now. Serotine keeps trying."
private const val NOT_FOLLOWED = "NFTs are found once the light client is synced."
private const val SINCE_ADDED = "Received since this account was added here"
