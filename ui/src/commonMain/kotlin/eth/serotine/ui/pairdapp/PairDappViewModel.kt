package eth.serotine.ui.pairdapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.repository.DappPairing
import eth.serotine.domain.usecase.PairDapp
import eth.serotine.ui.platform.QrScan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
    /**
     * The camera was refused, or could not be started: Scan is greyed out, still tappable, and [message]
     * says why (owner ruling, 2026-10-18, #55). A tap asks again.
     */
    val cameraUnavailable: Boolean = false,
) {
    /** Connect is greyed out, still tappable, until a link is pasted (owner ruling, 2026-10-18, #55). */
    val missingLink: Boolean get() = text.isBlank()
}

/**
 * Pair's ViewModel: pairs with the dApp of the pasted `wc:` link, of the one the platform opened the
 * app with ([incoming]), or of one a QR code scan read ([scanned]). A link that arrives or is scanned
 * is filled in, and paired with only on Connect.
 */
class PairDappViewModel(private val pairDapp: PairDapp, incoming: IncomingDappLink? = null) : ViewModel() {

    private val state = MutableStateFlow(PairDappUiState())
    val uiState: StateFlow<PairDappUiState> = state.asStateFlow()

    init {
        if (incoming != null) {
            // Every link that arrives while the screen is open too: the latest fills the field. Not while a
            // Connect is under way: the link waits, so an answer is never shown under a link it is not for.
            // Nor once paired: the screen leaves for Connected dApps, and the app opens it again for the link.
            viewModelScope.launch {
                combine(incoming.pending, state) { link, now -> link != null && !now.busy && !now.paired }
                    .distinctUntilChanged()
                    .filter { it }
                    .collect {
                        val link = incoming.take() ?: return@collect
                        state.update { it.copy(text = link, message = null, paired = false) }
                    }
            }
        }
    }

    fun edit(text: String) = state.update { it.copy(text = text, message = null) }

    /**
     * What a QR code scan came to. A `wc:` link fills the field, to be paired with on Connect, as a pasted
     * one is; any other code is refused with what it is not. Nothing while a Connect is under way.
     */
    fun scanned(scan: QrScan) {
        if (state.value.busy) return
        when (scan) {
            is QrScan.Read -> {
                val text = scan.text.trim()
                if (text.startsWith("wc:", ignoreCase = true)) {
                    state.update { it.copy(text = text, message = null, cameraUnavailable = false) }
                } else {
                    state.update { it.copy(message = NOT_A_LINK, cameraUnavailable = false) }
                }
            }
            QrScan.CameraRefused -> state.update { it.copy(message = CAMERA_REFUSED, cameraUnavailable = true) }
            QrScan.CameraUnavailable -> state.update { it.copy(message = NO_CAMERA, cameraUnavailable = true) }
            QrScan.Cancelled -> Unit
        }
    }

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
        const val NOT_A_LINK = "That QR code is not a WalletConnect link. Scan the one the dApp shows under " +
            "WalletConnect."
        const val CAMERA_REFUSED = "Serotine may not use the camera. Allow it in the system's settings for " +
            "Serotine to scan, or copy the dApp's link and paste it here."
        const val NO_CAMERA = "The camera could not be started. Copy the dApp's link and paste it here instead."

        fun message(pairing: DappPairing): String? = when (pairing) {
            is DappPairing.Paired -> null
            DappPairing.Unreadable ->
                "That is not a WalletConnect link. Copy the link the dApp shows under WalletConnect."
            DappPairing.UnsupportedVersion ->
                "That link is for an older version of WalletConnect, which Serotine does not use."
            is DappPairing.UnsupportedParameter ->
                "That link asks for something Serotine does not do (${pairing.parameter}), so it is not used."
            DappPairing.Expired -> "That link has expired. Ask the dApp for a new one."
            DappPairing.NoProposal -> "That link does not ask for a connection. Ask the dApp for a new one."
            DappPairing.TopicInUse -> "That link names a connection that is already open, so it is not used."
            DappPairing.NotSaved -> "That could not be saved. Nothing was changed."
            DappPairing.Off ->
                "This build has dApp connections switched off: it was built without a WalletConnect project ID."
        }
    }
}
