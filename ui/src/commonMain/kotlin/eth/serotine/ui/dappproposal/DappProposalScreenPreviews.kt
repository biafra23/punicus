package eth.serotine.ui.dappproposal

import androidx.compose.runtime.Composable
import eth.serotine.domain.repository.DappRefusal
import eth.serotine.domain.usecase.DappOffer
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// A dApp's connection request in Android Studio's preview pane, one preview per state it draws
// differently. DappProposalScreenPreviewsTest renders each one in dappProposalScreenPreviews, at the end.

private val accounts = listOf(
    AccountOption("0x1", "Main", "0x52b4…91e0", chosen = true, unavailable = null),
    AccountOption("0x2", "Team Safe", "0x8a3f…2c17", chosen = false, unavailable = null),
    AccountOption("0x3", "Watched", "0x0f1c…9a44", chosen = false, unavailable = DappProposalViewModel.offerLine(DappOffer.NO_KEY)),
    AccountOption("0x4", "Savings", "0x52b4…91e0", chosen = false, unavailable = DappProposalViewModel.offerLine(DappOffer.NOT_SEPOLIA)),
)

private val request = DappProposalUiState(
    loading = false,
    name = "Uniswap Interface",
    url = "https://app.uniswap.org",
    description = "Swap, earn, and build on the leading decentralized crypto trading protocol.",
    asks = listOf("personal_sign", "eth_sendTransaction").map(DappProposalViewModel::methodLine),
    accounts = accounts,
)

@Composable
private fun DappProposalPreview(state: DappProposalUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        DappProposalScreen(state, onToggle = {}, onApprove = {}, onReject = {}, onDismissExplanation = {}, onBack = {})
    }
}

@Preview
@Composable
private fun DappProposalRequestPreview() = DappProposalPreview(request)

@Preview
@Composable
private fun DappProposalRequestDarkPreview() = DappProposalPreview(request, darkTheme = true)

@Preview
@Composable
private fun DappProposalNoneChosenPreview() =
    DappProposalPreview(request.copy(accounts = accounts.map { it.copy(chosen = false) }))

@Preview
@Composable
private fun DappProposalRefusedPreview() = DappProposalPreview(
    request.copy(refusal = DappProposalViewModel.refusalLine(DappRefusal.OTHER_CHAIN), accounts = accounts.map { it.copy(chosen = false) }),
)

@Preview
@Composable
private fun DappProposalExplainingPreview() = DappProposalPreview(
    request.copy(explaining = Explanation("Not offered to dApps", DappProposalViewModel.offerLine(DappOffer.NO_KEY)!!)),
)

@Preview
@Composable
private fun DappProposalNotSavedPreview() = DappProposalPreview(request.copy(notice = DappProposalViewModel.NOT_SAVED))

@Preview
@Composable
private fun DappProposalLoadingPreview() = DappProposalPreview(DappProposalUiState())

/** Every preview above, which DappProposalScreenPreviewsTest renders: a new preview goes in here too. */
internal val dappProposalScreenPreviews: List<@Composable () -> Unit> = listOf(
    { DappProposalRequestPreview() },
    { DappProposalRequestDarkPreview() },
    { DappProposalNoneChosenPreview() },
    { DappProposalRefusedPreview() },
    { DappProposalExplainingPreview() },
    { DappProposalNotSavedPreview() },
    { DappProposalLoadingPreview() },
)
