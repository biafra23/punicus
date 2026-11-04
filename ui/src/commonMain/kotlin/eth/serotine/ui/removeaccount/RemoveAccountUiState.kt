package eth.serotine.ui.removeaccount

/** Everything the remove dialog renders. */
sealed interface RemoveAccountUiState {

    /** The account has not been read yet. */
    data object Loading : RemoveAccountUiState

    /**
     * The question, for the account [label] at the EIP-55 [address] on [network], shown in full
     * because labels can repeat: [keyed] when its key is on this device, which the dialog says
     * before anything is removed, and goes with it unless the same address is held here on other
     * networks, which [keyStaysFor] then names ("Ethereum and Gnosis Chain"; docs/networks.md, N7);
     * [removing] while it is.
     */
    data class Confirm(
        val label: String,
        val address: String,
        val network: String,
        val keyed: Boolean,
        val removing: Boolean = false,
        val keyStaysFor: String? = null,
    ) : RemoveAccountUiState

    /** What the user must know about how it went, display-ready, before the dialog closes. */
    data class Notice(val message: String) : RemoveAccountUiState

    /** The account is out of the wallet: the dialog closes. */
    data object Done : RemoveAccountUiState

    /** The account is not in the wallet (any more): the dialog closes. */
    data object Gone : RemoveAccountUiState
}
