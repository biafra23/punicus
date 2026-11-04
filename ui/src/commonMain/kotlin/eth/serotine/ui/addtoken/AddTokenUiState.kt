package eth.serotine.ui.addtoken

import eth.serotine.ui.components.NetworkChip

/**
 * Everything Add token renders (docs/wallet-ux.md, screen 20): the typed [address], why it
 * cannot be read ([addressError]), what the contract says about itself ([card], null until the
 * text is an address), and how adding went. The network comes first ([networks]: docs/networks.md,
 * N17): the contract is read on the one chosen, and the token added there.
 */
data class AddTokenUiState(
    val networks: List<NetworkChip> = emptyList(),
    val address: String = "",
    val addressError: String? = null,
    val card: TokenCard? = null,
    val adding: Boolean = false,
    val error: String? = null,
    /** Added: the screen leaves. */
    val done: Boolean = false,
) {
    /** Only a contract that described itself as a token can be added, and one add at a time. */
    val canAdd: Boolean
        get() = card is TokenCard.Claim && !adding
}

/** The card: what is at the typed address, display-ready. */
sealed interface TokenCard {

    /** The contract is being asked. */
    data object Checking : TokenCard

    /**
     * What the contract at the EIP-55 [address] says: [symbol], [name] and [decimals], read
     * [checkedAt]; [warning] when the symbol is a built-in token's, which this is not. [simpleCheckedAt]
     * is [checkedAt] with no block, for simple mode (docs/simple-ui.md).
     */
    data class Claim(
        val address: String,
        val symbol: String,
        val name: String,
        val decimals: Int,
        val checkedAt: String,
        val warning: String?,
        val simpleCheckedAt: String = checkedAt,
    ) : TokenCard

    /** The wallet knows the address already, as [symbol]. */
    data class Known(val symbol: String) : TokenCard

    /** No token there, or no verified answer right now, for the reason [message] gives. */
    data class Unavailable(val message: String) : TokenCard
}
