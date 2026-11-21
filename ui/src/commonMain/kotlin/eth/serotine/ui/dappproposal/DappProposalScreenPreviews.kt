package eth.serotine.ui.dappproposal

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.WalletPassword
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
        // What the dApp asks for, of both kinds, as the ViewModel lists it, and no note: it can only be rejected.
        networks = listOf(Network.ETHEREUM, Network.SEPOLIA).map(DappProposalViewModel::networkLine),
        networksNote = null,
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

// Per-app addresses (issue #252). A first connection from a site: a new address of its own, ticked, the password typed.
private val firstVisit = realFunds.copy(
    url = "https://app.uniswap.org",
    accounts = realFunds.accounts.map { it.copy(chosen = false) },
    newAddress = NewAddressOption(
        title = "New address for uniswap.org",
        where = "app.uniswap.org, site uniswap.org · On Ethereum and Gnosis Chain",
        chosen = true,
        unavailable = null,
    ),
    password = WalletPassword("made-up password"),
    // A phrase is held: another site's address can be used instead, or an old site's typed and made again.
    aliasOffer = AliasOffer("No address for uniswap.org yet", null),
)

@Preview
@Composable
private fun DappProposalNewAddressPreview() = DappProposalPreview(firstVisit)

@Preview
@Composable
private fun DappProposalNewAddressDarkPreview() = DappProposalPreview(firstVisit, darkTheme = true)

// Two recovery phrases on this device: the row names the one it comes from, which can be changed.
private val twoPhrases = firstVisit.copy(
    newAddress = firstVisit.newAddress!!.copy(phrase = "From the recovery phrase of Savings", changeable = true),
)

@Preview
@Composable
private fun DappProposalTwoPhrasesPreview() = DappProposalPreview(twoPhrases)

@Preview
@Composable
private fun DappProposalChoosingPhrasePreview() = DappProposalPreview(
    twoPhrases.copy(
        phrases = listOf(
            PhraseOption("1:0x1", "Savings", "0x52b4…91e0", chosen = true),
            PhraseOption("1:0x5", "Cold", "0x9e0a…5b12", chosen = false),
        ),
    ),
)

// Only watched accounts and Safes: no phrase to make it from, so it is greyed out, never hidden.
@Preview
@Composable
private fun DappProposalNoPhrasePreview() = DappProposalPreview(
    request.copy(
        url = "https://app.uniswap.org",
        newAddress = NewAddressOption(
            title = "New address for uniswap.org",
            where = "app.uniswap.org, site uniswap.org",
            chosen = false,
            unavailable = DappProposalViewModel.NO_PHRASE,
        ),
        // No other site's address and no phrase: nothing to use instead, said on a tap.
        aliasOffer = AliasOffer("No address for uniswap.org yet", DappProposalViewModel.NO_OTHER_SITE),
    ),
)

// A dApp that moved to uniswap.app: no address there yet, and uniswap.org's may be used instead, after a question.
private val moved = firstVisit.copy(
    url = "https://app.uniswap.app",
    newAddress = firstVisit.newAddress!!.copy(
        title = "New address for uniswap.app",
        where = "app.uniswap.app, site uniswap.app · On Ethereum and Gnosis Chain",
    ),
    aliasOffer = AliasOffer("No address for uniswap.app yet", null),
    accounts = listOf(
        AccountOption(
            "1:0x6",
            "uniswap.org",
            "0x9fa6…8D52",
            chosen = false,
            unavailable = DappProposalViewModel.offerLine(DappOffer.OTHER_SITE, Network.ETHEREUM, "uniswap.org"),
            network = Network.ETHEREUM.displayName,
            perAppSite = "uniswap.org",
        ),
    ) + realFunds.accounts.map { it.copy(chosen = false) },
)

@Preview
@Composable
private fun DappProposalMovedPreview() = DappProposalPreview(moved)

@Preview
@Composable
private fun DappProposalChoosingAliasPreview() = DappProposalPreview(
    moved.copy(
        aliasChoices = listOf(
            AliasChoice("0x6", "uniswap.org", "Also seen by uniswap.exchange", "0x9fa6…8D52", "Ethereum, Gnosis Chain"),
            AliasChoice(
                "0x3",
                "safe.global",
                null,
                "0x3c17…a07b",
                "Sepolia",
                unavailable = DappProposalViewModel.UNUSABLE_HERE,
            ),
        ),
    ),
)

@Preview
@Composable
private fun DappProposalConfirmingAliasPreview() = DappProposalPreview(
    moved.copy(
        confirmingAlias = AliasQuestion(
            "Use uniswap.org's address?",
            "uniswap.app will see the same address as uniswap.org. The two sites can link your activity.",
        ),
    ),
)

// The old site not listed (after a restore of the phrase, say): its host typed, to make its address again.
@Preview
@Composable
private fun DappProposalTypingOldHostPreview() = DappProposalPreview(
    moved.copy(aliasChoices = emptyList(), aliasHost = "app.uniswap.org"),
)

@Preview
@Composable
private fun DappProposalMadeAgainPreview() = DappProposalPreview(
    moved.copy(
        password = WalletPassword("made-up password"),
        confirmingAlias = AliasQuestion(
            "Use uniswap.org's address?",
            "uniswap.app will see the same address as uniswap.org. The two sites can link your activity. " +
                DappProposalViewModel.MADE_AGAIN,
            needsPassword = true,
        ),
    ),
)

// "Use it" with a wrong password: said in the question, which stays open to try again.
@Preview
@Composable
private fun DappProposalMadeAgainFailedPreview() = DappProposalPreview(
    moved.copy(
        confirmingAlias = AliasQuestion(
            "Use uniswap.org's address?",
            "uniswap.app will see the same address as uniswap.org. The two sites can link your activity. " +
                DappProposalViewModel.MADE_AGAIN,
            needsPassword = true,
            error = "That is not the wallet password. Nothing was made or linked.",
        ),
    ),
)

// Linked: uniswap.org's address is uniswap.app's now, preselected, and no new address is offered beside it.
@Preview
@Composable
private fun DappProposalLinkedPreview() = DappProposalPreview(
    realFunds.copy(
        url = "https://app.uniswap.app",
        accounts = listOf(
            AccountOption(
                "1:0x6",
                "uniswap.org",
                "0x9fa6…8D52",
                chosen = true,
                unavailable = null,
                network = Network.ETHEREUM.displayName,
                perAppSite = "uniswap.org",
                linked = true,
            ),
        ) + realFunds.accounts.map { it.copy(chosen = false) },
    ),
)

// A site seen before, approved with an ordinary account, the new address declined then: offered, unticked
// (owner's decision, 2026-11-19, on #331).
@Preview
@Composable
private fun DappProposalSeenBeforeDeclinedPreview() = DappProposalPreview(
    firstVisit.copy(
        newAddress = firstVisit.newAddress!!.copy(chosen = false),
        password = WalletPassword.EMPTY,
        accounts = realFunds.accounts.mapIndexed { index, account -> account.copy(chosen = index == 0) },
    ),
)

// The site connecting again: its per-app address, approved last, preselected among the accounts.
@Preview
@Composable
private fun DappProposalSeenBeforePreview() = DappProposalPreview(
    realFunds.copy(
        url = "https://app.uniswap.org",
        accounts = listOf(
            AccountOption(
                "1:0x6",
                "uniswap.org",
                "0x9fa6…8D52",
                chosen = true,
                unavailable = null,
                network = Network.ETHEREUM.displayName,
                perAppSite = "uniswap.org",
            ),
        ) + realFunds.accounts.map { it.copy(chosen = false) },
    ),
)

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
    ScreenPreview("new-address") { DappProposalNewAddressPreview() },
    ScreenPreview("new-address-dark") { DappProposalNewAddressDarkPreview() },
    ScreenPreview("two-phrases") { DappProposalTwoPhrasesPreview() },
    ScreenPreview("choosing-phrase") { DappProposalChoosingPhrasePreview() },
    ScreenPreview("no-phrase") { DappProposalNoPhrasePreview() },
    ScreenPreview("seen-before") { DappProposalSeenBeforePreview() },
    ScreenPreview("seen-before-declined") { DappProposalSeenBeforeDeclinedPreview() },
    ScreenPreview("moved") { DappProposalMovedPreview() },
    ScreenPreview("choosing-alias") { DappProposalChoosingAliasPreview() },
    ScreenPreview("confirming-alias") { DappProposalConfirmingAliasPreview() },
    ScreenPreview("linked") { DappProposalLinkedPreview() },
    ScreenPreview("typing-old-host") { DappProposalTypingOldHostPreview() },
    ScreenPreview("made-again") { DappProposalMadeAgainPreview() },
    ScreenPreview("made-again-failed") { DappProposalMadeAgainFailedPreview() },
)
