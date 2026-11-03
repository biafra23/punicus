package eth.serotine.ui.pairdapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.repository.DappPairing
import eth.serotine.domain.usecase.PairDapp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Pair (docs/walletconnect.md, screen D2) as display-ready values: the `wc:` link pasted, whether it
 * is being paired, what pairing answered, and [paired] once it did (the screen then returns to
 * Connected dApps, where the dApp's proposal appears).
 */
data class PairDappUiState(
    val text: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val paired: Boolean = false,
) {
    /** Connect is greyed out, still tappable, until a link is pasted (owner ruling, 2026-10-18, #55). */
    val missingLink: Boolean get() = text.isBlank()
}

/** Pair's ViewModel: pairs with the dApp of the pasted `wc:` link. */
class PairDappViewModel(private val pairDapp: PairDapp) : ViewModel() {

    private val state = MutableStateFlow(PairDappUiState())
    val uiState: StateFlow<PairDappUiState> = state.asStateFlow()

    fun edit(text: String) = state.update { it.copy(text = text, message = null) }

    fun connect() {
        val text = state.value.text
        if (state.value.busy) return
        if (text.isBlank()) {
            state.update { it.copy(message = PASTE_FIRST) }
            return
        }
        state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val pairing = pairDapp(text)
            state.update {
                it.copy(busy = false, paired = pairing is DappPairing.Paired, message = message(pairing))
            }
        }
    }

    companion object {
        const val PASTE_FIRST = "Paste the WalletConnect link the dApp shows first."

        fun message(pairing: DappPairing): String? = when (pairing) {
            is DappPairing.Paired -> null
            DappPairing.Unreadable -> "That is not a WalletConnect link. Copy the link the dApp shows under WalletConnect."
            DappPairing.UnsupportedVersion -> "That link is for an older version of WalletConnect, which Serotine does not use."
            is DappPairing.UnsupportedParameter ->
                "That link asks for something Serotine does not do (${pairing.parameter}), so it is not used."
            DappPairing.Expired -> "That link has expired. Ask the dApp for a new one."
            DappPairing.NoProposal -> "That link does not ask for a connection. Ask the dApp for a new one."
            DappPairing.TopicInUse -> "That link names a connection that is already open, so it is not used."
            DappPairing.NotSaved -> "That could not be saved. Nothing was changed."
            DappPairing.Off -> "This build has dApp connections switched off: it was built without a WalletConnect project ID."
        }
    }
}
