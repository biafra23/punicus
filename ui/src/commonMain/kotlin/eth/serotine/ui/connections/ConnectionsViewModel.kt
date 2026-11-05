package eth.serotine.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.DappConnections
import eth.serotine.domain.repository.DappProposalId
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappRelayLink
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.usecase.DappSignContent
import eth.serotine.domain.usecase.DisconnectDapp
import eth.serotine.domain.usecase.ExtendDappSession
import eth.serotine.domain.usecase.ObserveDappConnections
import eth.serotine.domain.usecase.ObserveDappMessages
import eth.serotine.ui.components.epochSecondsNow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Connected dApps' ViewModel (screen D1): the proposals, the requests to sign and the sessions,
 * each session's end, and Extend and Disconnect. [clock] is now, in unix seconds.
 */
class ConnectionsViewModel(
    observeDappConnections: ObserveDappConnections,
    observeDappMessages: ObserveDappMessages,
    private val disconnectDapp: DisconnectDapp,
    private val extendDappSession: ExtendDappSession,
    private val clock: () -> Long = ::epochSecondsNow,
    /** When the sessions' ends are said again: every minute, so "Ends in …" stays true while the screen is open. */
    ticks: Flow<Unit> = everyMinute(),
) : ViewModel() {

    private val notice = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ConnectionsUiState> = combine(
        observeDappConnections(),
        observeDappMessages(),
        notice,
        ticks,
    ) { overview, messages, notice, _ ->
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
                            line = if (proposal.refusal != null) {
                                "Asks for more than Serotine offers · Review"
                            } else {
                                "Asks to connect · Review"
                            },
                        )
                    },
                    requests = messages.map { review ->
                        RequestRow(
                            key = requestKey(review.request.id),
                            name = review.request.dapp.name,
                            url = review.request.dapp.url,
                            transaction = review.content is DappSignContent.Transaction,
                            line = when {
                                review.content is DappSignContent.Transaction && review.refusal != null ->
                                    "Asks for a transaction that cannot be sent · Review"
                                review.content is DappSignContent.Transaction ->
                                    "Asks you to send a transaction · Review"
                                review.refusal != null -> "Asks for a signature that cannot be given · Review"
                                review.signIn != null -> "Asks you to sign in · Review"
                                review.message == null -> "Asks you to sign data · Review"
                                else -> "Asks you to sign a message · Review"
                            },
                        )
                    },
                    sessions = connections.sessions.map { session ->
                        val count = session.accounts.size
                        val accounts = if (count == 1) "1 account" else "$count accounts"
                        val confirmed = if (session.acknowledged) "" else " · Not confirmed by the dApp yet"
                        SessionRow(
                            key = session.id.topic,
                            name = session.dapp.name,
                            url = session.dapp.url,
                            line = "${networksLine(session.networks)} · Sees $accounts · " +
                                "${endsIn(session.expiresAtSeconds - now)}$confirmed",
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

        /**
         * The networks a session holds, by name, with their kind of funds: one kind per session (owner ruling,
         * 2026-11-05, on #22), and Sepolia keeps its testnet warning.
         */
        fun networksLine(networks: Set<Network>): String {
            val held = Network.entries.filter { it in networks }
            val names = when (held.size) {
                0 -> return "On no network Serotine serves"
                1 -> held.single().displayName
                else -> held.dropLast(1).joinToString(", ") { it.displayName } + " and " + held.last().displayName
            }
            return if (held.all { it.testnet }) "On $names, a test network" else "On $names, real funds"
        }

        /** A request as a route carries it: `topic/id`. */
        fun requestKey(id: DappRequestId): String = "${id.session.topic}/${id.id}"

        /** A proposal as a route carries it: `pairing/id`. */
        fun proposalKey(id: DappProposalId): String = "${id.pairing}/${id.id}"

        fun linkLine(link: DappRelayLink): String = when (link) {
            DappRelayLink.CONNECTED -> "Connected to WalletConnect's relay"
            DappRelayLink.CONNECTING -> "Connecting to WalletConnect's relay…"
            DappRelayLink.DISCONNECTED -> "WalletConnect's relay can't be reached right now"
        }

        /** A tick now and every minute after. */
        fun everyMinute(): Flow<Unit> = flow {
            while (true) {
                emit(Unit)
                delay(60_000)
            }
        }

        /** "Ends in 6 days", "Ends in 5 hours", "Ends in a few minutes", "Ended". */
        fun endsIn(seconds: Long): String = when {
            seconds <= 0 -> "Ended"
            seconds >= 2 * 86_400 -> "Ends in ${seconds / 86_400} days"
            seconds >= 86_400 -> "Ends in 1 day"
            seconds >= 2 * 3_600 -> "Ends in ${seconds / 3_600} hours"
            seconds >= 3_600 -> "Ends in 1 hour"
            else -> "Ends in a few minutes"
        }
    }
}
