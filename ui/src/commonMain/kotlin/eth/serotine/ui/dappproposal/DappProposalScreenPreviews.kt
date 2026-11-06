package eth.serotine.ui.dappproposal

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.DappRefusal
import eth.serotine.domain.usecase.DappOffer
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// A dApp's connection request in Android Studio's preview pane, one preview per state it draws
// differently. DappProposalScreenPreviewsTest renders each one in dappProposalScreenPreviews, at the end.

private fun option(key: String, name: String, address: String, network: Network, chosen: Boolean, offer: DappOffer) =
    AccountOption(key, name, address, chosen, DappProposalViewModel.offerLine(offer, network), network.displayName)

// A Sepolia account chosen: the connection is testnet's, and the accounts holding real funds are greyed out.
private val accounts = listOf(
    option("11155111:0x1", "Main", "0x52b4…91e0", Network.SEPOLIA, chosen = true, DappOffer.OFFERED),
    option("11155111:0x2", "Team Safe", "0x8a3f…2c17", Network.SEPOLIA, chosen = false, DappOffer.OFFERED),
    option("11155111:0x3", "Watched", "0x0f1c…9a44", Network.SEPOLIA, chosen = false, DappOffer.NO_KEY),
    option("1:0x1", "Savings", "0x52b4…91e0", Network.ETHEREUM, chosen = false, DappOffer.OTHER_FUNDS),
    option("100:0x4", "Gnosis Pay", "0x7c21…03bd", Network.GNOSIS, chosen = false, DappOffer.NOT_ASKED),
)

private val request = DappProposalUiState(
    loading = false,
    name = "Uniswap Interface",
    url = "https://app.uniswap.org",
    description = "Swap, earn, and build on the leading decentralized crypto trading protocol.",
    networks = listOf(DappProposalViewModel.networkLine(Network.SEPOLIA)),
    asks = listOf("personal_sign", "eth_sendTransaction").map(DappProposalViewModel::methodLine),
    accounts = accounts,
)

// Nothing chosen yet, the dApp asking for Ethereum and Sepolia: either kind, and the first account decides.
private val eitherKind = request.copy(
    networks = listOf(Network.ETHEREUM, Network.SEPOLIA).map(DappProposalViewModel::networkLine),
    networksNote = DappProposalViewModel.BOTH_KINDS,
    accounts = listOf(
        option("11155111:0x1", "Main", "0x52b4…91e0", Network.SEPOLIA, chosen = false, DappOffer.OFFERED),
        option("1:0x1", "Savings", "0x52b4…91e0", Network.ETHEREUM, chosen = false, DappOffer.OFFERED),
        option("100:0x4", "Gnosis Pay", "0x7c21…03bd", Network.GNOSIS, chosen = false, DappOffer.NOT_ASKED),
    ),
)

// Real funds chosen on both Ethereum and Gnosis Chain: one connection holding both, Sepolia greyed out.
private val realFunds = request.copy(
    networks = listOf(Network.ETHEREUM, Network.GNOSIS).map(DappProposalViewModel::networkLine),
    asks = listOf("personal_sign", "eth_sendTransaction", "wallet_switchEthereumChain")
        .map(DappProposalViewModel::methodLine),
    accounts = listOf(
        option("1:0x1", "Savings", "0x52b4…91e0", Network.ETHEREUM, chosen = true, DappOffer.OFFERED),
        option("100:0x4", "Gnosis Pay", "0x7c21…03bd", Network.GNOSIS, chosen = true, DappOffer.OFFERED),
        option("11155111:0x1", "Main", "0x52b4…91e0", Network.SEPOLIA, chosen = false, DappOffer.OTHER_FUNDS),
        option("1:0x5", "Cold", "0x9e0a…5b12", Network.ETHEREUM, chosen = false, DappOffer.NETWORK_OFF),
    ),
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
private fun DappProposalEitherKindPreview() = DappProposalPreview(eitherKind)

@Preview
@Composable
private fun DappProposalRealFundsPreview() = DappProposalPreview(realFunds)

@Preview
@Composable
private fun DappProposalRealFundsDarkPreview() = DappProposalPreview(realFunds, darkTheme = true)

// The dApp requires Ethereum and Gnosis Chain: an Ethereum account chosen, Approve waits for one on Gnosis Chain.
private val missingRequired = realFunds.copy(
    accounts = realFunds.accounts.map { if (it.network == Network.GNOSIS.displayName) it.copy(chosen = false) else it },
    missingRequired = DappProposalViewModel.missingLine(setOf(Network.GNOSIS)),
)

@Preview
@Composable
private fun DappProposalMissingRequiredPreview() = DappProposalPreview(missingRequired)

@Preview
@Composable
private fun DappProposalMissingRequiredDarkPreview() = DappProposalPreview(missingRequired, darkTheme = true)

@Preview
@Composable
private fun DappProposalMixedFundsRefusedPreview() = DappProposalPreview(
    request.copy(
        networks = emptyList(),
        refusal = DappProposalViewModel.refusalLine(DappRefusal.MIXED_FUNDS),
        accounts = accounts.map { it.copy(chosen = false) },
    ),
)

@Preview
@Composable
private fun DappProposalNoneChosenPreview() =
    DappProposalPreview(request.copy(accounts = accounts.map { it.copy(chosen = false) }))

@Preview
@Composable
private fun DappProposalRefusedPreview() = DappProposalPreview(
    request.copy(
        refusal = DappProposalViewModel.refusalLine(DappRefusal.OTHER_CHAIN),
        accounts = accounts.map { it.copy(chosen = false) },
    ),
)

@Preview
@Composable
private fun DappProposalExplainingPreview() = DappProposalPreview(
    request.copy(
        explaining = Explanation(
            "Not offered to this dApp",
            DappProposalViewModel.offerLine(DappOffer.OTHER_FUNDS, Network.ETHEREUM)!!,
        ),
    ),
)

@Preview
@Composable
private fun DappProposalNotSavedPreview() = DappProposalPreview(request.copy(notice = DappProposalViewModel.NOT_SAVED))

@Preview
@Composable
private fun DappProposalEndedPreview() = DappProposalPreview(
    DappProposalUiState(
        loading = false,
        ended = true,
        notice = "This request has expired. Connect again from the dApp.",
    ),
)

@Preview
@Composable
private fun DappProposalLoadingPreview() = DappProposalPreview(DappProposalUiState())

/** Every preview above, which DappProposalScreenPreviewsTest renders: a new preview goes in here too. */
internal val dappProposalScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("request") { DappProposalRequestPreview() },
    ScreenPreview("request-dark") { DappProposalRequestDarkPreview() },
    ScreenPreview("none-chosen") { DappProposalNoneChosenPreview() },
    ScreenPreview("either-kind") { DappProposalEitherKindPreview() },
    ScreenPreview("real-funds") { DappProposalRealFundsPreview() },
    ScreenPreview("real-funds-dark") { DappProposalRealFundsDarkPreview() },
    ScreenPreview("missing-required") { DappProposalMissingRequiredPreview() },
    ScreenPreview("missing-required-dark") { DappProposalMissingRequiredDarkPreview() },
    ScreenPreview("mixed-funds-refused") { DappProposalMixedFundsRefusedPreview() },
    ScreenPreview("refused") { DappProposalRefusedPreview() },
    ScreenPreview("explaining") { DappProposalExplainingPreview() },
    ScreenPreview("not-saved") { DappProposalNotSavedPreview() },
    ScreenPreview("ended") { DappProposalEndedPreview() },
    ScreenPreview("loading") { DappProposalLoadingPreview() },
)
