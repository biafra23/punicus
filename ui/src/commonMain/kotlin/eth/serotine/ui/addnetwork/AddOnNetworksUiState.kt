package eth.serotine.ui.addnetwork

import eth.serotine.ui.components.NetworkChoice

/** Everything the Add on another network dialog renders (docs/networks.md, N6). */
sealed interface AddOnNetworksUiState {

    /** The account has not been read yet. */
    data object Loading : AddOnNetworksUiState

    /**
     * The networks the account [label] is not on yet ([choices], in `Network` order; empty when it
     * is on every one), each a check box; [adding] while the accounts are added, and [error] when
     * the last Add could not be saved.
     */
    data class Choose(
        val label: String,
        val choices: List<NetworkChoice>,
        val adding: Boolean = false,
        val error: String? = null,
    ) : AddOnNetworksUiState {
        /** Whether Add can be done: a network that is on is ticked, and nothing is being added. */
        val canAdd: Boolean
            get() = !adding && choices.any { it.chosen && it.on }
    }

    /** The account is added on the networks chosen: the dialog closes. */
    data object Done : AddOnNetworksUiState

    /** The account is not in the wallet (any more): the dialog closes. */
    data object Gone : AddOnNetworksUiState

    /** Nothing can be added, for the reason [message] gives, display-ready. */
    data class Unavailable(val message: String) : AddOnNetworksUiState
}
