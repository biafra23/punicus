package eth.serotine.ui.connections

import androidx.compose.runtime.Composable
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Connected dApps in Android Studio's preview pane, one preview per state it draws differently.
// Built from display-ready values, never from the ViewModel. ConnectionsScreenPreviewsTest renders
// each one in connectionsScreenPreviews, at the end.

private val connected = ConnectionsUiState(
    loading = false,
    link = "Connected to WalletConnect's relay",
    proposals = listOf(ProposalRow("p/1", "Uniswap Interface", "https://app.uniswap.org", "Asks to connect · Review")),
    requests = listOf(
        RequestRow("t2/7", "Example dApp", "https://example.org", "Asks you to sign in · Review"),
        RequestRow(
            "t2/8",
            "Example dApp",
            "https://example.org",
            "Asks you to send a transaction · Review",
            transaction = true,
        ),
    ),
    sessions = listOf(
        SessionRow("t1", "Safe{Wallet}", "https://app.safe.global", "Sees 1 account · Ends in 6 days"),
        SessionRow(
            "t2",
            "Example dApp",
            "https://example.org",
            "Sees 2 accounts · Ends in 5 hours · Not confirmed by the dApp yet",
        ),
    ),
)

@Composable
private fun ConnectionsPreview(state: ConnectionsUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        ConnectionsScreen(
            state,
            onBack = {},
            onPair = {},
            onProposal = {},
            onRequest = {},
            onTransaction = {},
            onExtend = {},
            onDisconnect = {},
        )
    }
}

@Preview
@Composable
private fun ConnectionsConnectedPreview() = ConnectionsPreview(connected)

@Preview
@Composable
private fun ConnectionsConnectedDarkPreview() = ConnectionsPreview(connected, darkTheme = true)

@Preview
@Composable
private fun ConnectionsNoneYetPreview() = ConnectionsPreview(
    ConnectionsUiState(loading = false, link = "Connecting to WalletConnect's relay…"),
)

@Preview
@Composable
private fun ConnectionsNotSavedPreview() = ConnectionsPreview(connected.copy(notice = ConnectionsViewModel.NOT_SAVED))

@Preview
@Composable
private fun ConnectionsOffPreview() = ConnectionsPreview(ConnectionsUiState(loading = false, off = true))

@Preview
@Composable
private fun ConnectionsLoadingPreview() = ConnectionsPreview(ConnectionsUiState())

/** Every preview above, which ConnectionsScreenPreviewsTest renders: a new preview goes in here too. */
internal val connectionsScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("connected") { ConnectionsConnectedPreview() },
    ScreenPreview("connected-dark") { ConnectionsConnectedDarkPreview() },
    ScreenPreview("none-yet") { ConnectionsNoneYetPreview() },
    ScreenPreview("not-saved") { ConnectionsNotSavedPreview() },
    ScreenPreview("off") { ConnectionsOffPreview() },
    ScreenPreview("loading") { ConnectionsLoadingPreview() },
)
