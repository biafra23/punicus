package eth.serotine.ui.connections

/**
 * Connected dApps (docs/walletconnect.md, screen D1) as display-ready values: whether dApp
 * connections are [off] in this build, how the relay connection stands ([link]), the proposals and
 * the requests to sign waiting for the user, and the open sessions. Every dApp name and URL is the
 * dApp's own claim, and the screen says so.
 */
data class ConnectionsUiState(
    val loading: Boolean = true,
    val off: Boolean = false,
    val link: String = "",
    val proposals: List<ProposalRow> = emptyList(),
    val requests: List<RequestRow> = emptyList(),
    val sessions: List<SessionRow> = emptyList(),
    val notice: String? = null,
)

/** A proposal waiting for the user: [key] opens its review (screen D3). */
data class ProposalRow(val key: String, val name: String, val url: String, val line: String)

/** A request waiting for the user: [key] opens its review, screen D6 for a [transaction], D4 otherwise. */
data class RequestRow(
    val key: String,
    val name: String,
    val url: String,
    val line: String,
    val transaction: Boolean = false,
)

/** An open session: [key] names it to Extend and Disconnect. */
data class SessionRow(val key: String, val name: String, val url: String, val line: String)
