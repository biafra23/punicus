package eth.serotine.ui.receive

import eth.serotine.ui.components.NetworkLabel

/** Everything the Receive screen renders (docs/wallet-ux.md, screen 6). */
sealed interface ReceiveUiState {

    /** The account has not been read yet. */
    data object Loading : ReceiveUiState

    /**
     * The account's [label] and its EIP-55 [address], which the QR code carries as it is, on its
     * [network], and [notice], the warning to send nothing from another network (docs/networks.md,
     * N13); [note], for an address that works on the other networks too, how; [networkOff], while
     * the network is off in this wallet, that what arrives is shown only once it is on (N18).
     */
    data class Ready(
        val label: String,
        val address: String,
        val network: NetworkLabel,
        val notice: String,
        val note: String? = null,
        val networkOff: String? = null,
    ) : ReceiveUiState

    /** The account is not in the wallet (removed): the screen leaves. */
    data object Gone : ReceiveUiState

    /** The stored list cannot be read right now, for the reason [message] gives. */
    data class Unavailable(val message: String) : ReceiveUiState
}
