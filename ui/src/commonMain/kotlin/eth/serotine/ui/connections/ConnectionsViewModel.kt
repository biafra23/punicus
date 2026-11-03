package eth.serotine.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.repository.DappConnections
import eth.serotine.domain.repository.DappProposalId
import eth.serotine.domain.repository.DappRelayLink
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.usecase.DisconnectDapp
import eth.serotine.domain.usecase.ExtendDappSession
import eth.serotine.domain.usecase.ObserveDappConnections
import eth.serotine.ui.components.epochSecondsNow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Connected dApps' ViewModel (screen D1): the proposals and sessions, each session's end, and
 * Extend and Disconnect. [clock] is now, in unix seconds.
 */
class ConnectionsViewModel(
    observeDappConnections: ObserveDappConnections,
    private val disconnectDapp: DisconnectDapp,
    private val extendDappSession: ExtendDappSession,
    private val clock: () -> Long = ::epochSecondsNow,
) : ViewModel() {

    private val notice = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ConnectionsUiState> = combine(observeDappConnections(), notice) { overview, notice ->
        when (val connections = overview.connections) {
            DappConnections.Off -> ConnectionsUiState(loading = false, off = true)
            is DappConnections.On -> {
                val now = clock()
                ConnectionsUiState(
                    loading = false,
                    link = linkLine(connections.link),
                    proposals = connections.proposals.map { proposal ->
                        ProposalRow(
                            key = proposalKey(proposal.id),
                            name = proposal.dapp.name,
                            url = proposal.dapp.url,
                            line = if (proposal.refusal != null) "Asks for more than Serotine offers · Review" else "Asks to connect · Review",
                        )
                    },
                    sessions = connections.sessions.map { session ->
                        val accounts = if (session.accounts.size == 1) "1 account" else "${session.accounts.size} accounts"
                        val confirmed = if (session.acknowledged) "" else " · Not confirmed by the dApp yet"
                        SessionRow(
                            key = session.id.topic,
                            name = session.dapp.name,
                            url = session.dapp.url,
                            line = "Sees $accounts · ${endsIn(session.expiresAtSeconds - now)}$confirmed",
                        )
                    },
                    notice = notice,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionsUiState())

    /** Ends the session [key], telling the dApp. */
    fun disconnect(key: String) {
        viewModelScope.launch {
            notice.value = if (disconnectDapp(DappSessionId(key))) null else NOT_SAVED
        }
    }

    /** Extends the session [key] to seven days from now. */
    fun extend(key: String) {
        viewModelScope.launch {
            notice.value = if (extendDappSession(DappSessionId(key))) null else NOT_SAVED
        }
    }

    companion object {
        const val NOT_SAVED = "That could not be saved. Nothing was changed."

        /** A proposal as a route carries it: `pairing/id`. */
        fun proposalKey(id: DappProposalId): String = "${id.pairing}/${id.id}"

        fun linkLine(link: DappRelayLink): String = when (link) {
            DappRelayLink.CONNECTED -> "Connected to WalletConnect's relay"
            DappRelayLink.CONNECTING -> "Connecting to WalletConnect's relay…"
            DappRelayLink.DISCONNECTED -> "WalletConnect's relay can't be reached right now"
        }

        /** "Ends in 6 days", "Ends in 5 hours", "Ends in a few minutes". */
        fun endsIn(seconds: Long): String = when {
            seconds >= 2 * 86_400 -> "Ends in ${seconds / 86_400} days"
            seconds >= 86_400 -> "Ends in 1 day"
            seconds >= 2 * 3_600 -> "Ends in ${seconds / 3_600} hours"
            seconds >= 3_600 -> "Ends in 1 hour"
            else -> "Ends in a few minutes"
        }
    }
}
