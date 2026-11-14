package eth.serotine.ui.accounts

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.StaleAnchorWarning
import eth.serotine.ui.components.catchingUpStripSample
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Accounts screen in simple mode (docs/simple-ui.md, H1 to H3) in Android Studio's preview
// pane, one preview per state it draws differently. Built from display-ready values, as the screen
// tests are, never from the ViewModel. SimpleAccountsScreenPreviewsTest renders each one in
// simpleAccountsScreenPreviews, at the end. The addresses are EIP-55's own examples, and the
// names and amounts are made up.

private val ethereum = NetworkLabel.of(Network.ETHEREUM)
private val gnosis = NetworkLabel.of(Network.GNOSIS)
private val sepolia = NetworkLabel.of(Network.SEPOLIA)

/** Never drawn by simple mode: the sections' strips are Detailed view's. */
private val strip = LightClientItem("Verified at block 23,614,077 · 8 s ago", null, false, VERIFIED)

private val main = AccountItem(
    "Main",
    "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
    ethereum.chainId,
    AccountKind.EOA,
    Control.FULL_CONTROL,
    "3.2041 ETH",
    balanceVerified = true,
    network = ethereum,
    networkLine = "Ethereum",
    accountTotal = "€12,025.55",
)

private val familySafe = AccountItem(
    "Family Safe",
    "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
    gnosis.chainId,
    AccountKind.SAFE,
    Control.CO_SIGNER,
    "4,861.20 XDAI",
    balanceVerified = true,
    signers = "2/3",
    kindNote = "Safe",
    network = gnosis,
    networkLine = "Gnosis Chain · Safe",
    accountTotal = "€4,500.00",
)

private val savings = AccountItem(
    "Savings",
    "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
    ethereum.chainId,
    AccountKind.EOA,
    Control.WATCH_ONLY,
    "0.84 ETH",
    balanceVerified = true,
    network = ethereum,
    networkLine = "Ethereum",
    accountTotal = "€2,850.30",
)

private val test = AccountItem(
    "Test",
    "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
    sepolia.chainId,
    AccountKind.EOA,
    Control.FULL_CONTROL,
    "1.5 ETH",
    balanceVerified = true,
    network = sepolia,
    networkLine = "Sepolia · test network",
    accountTotal = "no real value",
)

private val everyday = AccountsUiState.Ready(
    sections = listOf(
        NetworkSection(ethereum.chainId, "Ethereum", false, true, strip, listOf(main, savings)),
        NetworkSection(gnosis.chainId, "Gnosis Chain", false, true, strip, listOf(familySafe)),
        NetworkSection(sepolia.chainId, "Sepolia", true, true, strip, listOf(test)),
    ),
    total = "€19,375.85",
    totalLabel = "Total · EUR · Ethereum and Gnosis Chain",
    totalNote = "Sepolia not included",
    freshness = "Updated 2 min ago · pull down to refresh",
)

@Composable
private fun SimpleAccountsPreview(state: AccountsUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleAccountsScreen(
            state = state,
            onWatchAddress = {},
            onImportPhrase = {},
            onCreateAccount = {},
            onCreateSafe = {},
            onAccountAction = {},
            onRefresh = {},
        )
    }
}

/** H1: the accounts of three networks, the total with how fresh it is. */
@Preview
@Composable
private fun SimpleAccountsEverydayPreview() = SimpleAccountsPreview(everyday)

@Preview
@Composable
private fun SimpleAccountsEverydayDarkPreview() = SimpleAccountsPreview(everyday, darkTheme = true)

/** H2: pulled down, the networks without block access lists being checked again. */
@Preview
@Composable
private fun SimpleAccountsRefreshingPreview() = SimpleAccountsPreview(
    everyday.copy(freshness = "Checking Ethereum and Gnosis Chain…", refreshing = true),
)

/** H3: Ethereum cannot be checked; its rows dimmed with their age, out of the total. */
@Preview
@Composable
private fun SimpleAccountsUnreachablePreview() = SimpleAccountsPreview(
    everyday.copy(
        sections = listOf(
            NetworkSection(
                ethereum.chainId,
                "Ethereum",
                false,
                true,
                strip,
                listOf(
                    main.copy(balanceVerified = false, accountTotal = null, ageNote = "3 h ago · may have changed"),
                    savings.copy(balanceVerified = false, accountTotal = null, ageNote = "3 h ago · may have changed"),
                ),
            ),
            NetworkSection(gnosis.chainId, "Gnosis Chain", false, true, strip, listOf(familySafe)),
            NetworkSection(sepolia.chainId, "Sepolia", true, true, strip, listOf(test)),
        ),
        total = "€4,500.00",
        totalNote = "2 Ethereum accounts not counted · Sepolia not included",
        freshness = "Updated 40 s ago · pull down to refresh",
        unreachable = listOf(
            UnreachableNetwork(
                ethereum.chainId,
                "Can't check Ethereum right now",
                "Its balances were last confirmed 3 h ago and may have changed. Sending from its accounts waits " +
                    "until they can be checked. The app keeps trying.",
            ),
        ),
    ),
)

/** Ethereum still catching up, with balances from before: it says it is syncing, not that it cannot check. */
@Preview
@Composable
private fun SimpleAccountsSyncingPreview() = SimpleAccountsPreview(
    everyday.copy(
        sections = listOf(
            NetworkSection(
                ethereum.chainId,
                "Ethereum",
                false,
                true,
                strip,
                listOf(
                    main.copy(balanceVerified = false, accountTotal = null, ageNote = "3 h ago · may have changed"),
                    savings.copy(balanceVerified = false, accountTotal = null, ageNote = "3 h ago · may have changed"),
                ),
            ),
            NetworkSection(gnosis.chainId, "Gnosis Chain", false, true, strip, listOf(familySafe)),
            NetworkSection(sepolia.chainId, "Sepolia", true, true, strip, listOf(test)),
        ),
        total = "€4,500.00",
        freshness = "Syncing Ethereum · 2 of 6 periods · updated 40 s ago",
        totalNote = "2 Ethereum accounts not counted · Sepolia not included",
        unreachable = listOf(
            UnreachableNetwork(
                ethereum.chainId,
                "Ethereum is still syncing",
                "Its balances were last confirmed 3 h ago and may have changed. Sending from its accounts waits " +
                    "until it has caught up.",
            ),
        ),
    ),
)

/** A wallet on Sepolia alone, which updates live: the debug build's first network. */
@Preview
@Composable
private fun SimpleAccountsLivePreview() = SimpleAccountsPreview(
    AccountsUiState.Ready(
        sections = listOf(NetworkSection(sepolia.chainId, "Sepolia", true, true, strip, listOf(test))),
        total = "€4,500.00",
        totalLabel = "Total · EUR · Sepolia",
        totalNote = "Testnet prices",
        freshness = "Live",
    ),
)

/** Gnosis Chain refusing a stale checkpoint: warned in the error colour, with "Catch up…". */
@Preview
@Composable
private fun SimpleAccountsStaleAnchorPreview() = SimpleAccountsPreview(
    everyday.copy(
        sections = everyday.sections.map {
            if (it.chainId != gnosis.chainId) {
                it
            } else {
                it.copy(staleAnchor = StaleAnchorWarning.of(Network.GNOSIS, LightClientStatus.StaleAnchor(6, 3)))
            }
        },
    ),
)

/** No accounts yet: what the + button adds. */
@Preview
@Composable
private fun SimpleAccountsEmptyPreview() = SimpleAccountsPreview(AccountsUiState.Ready(emptyList()))

/** No accounts yet, and Gnosis Chain refusing a stale checkpoint: its warning and "Catch up…". */
@Preview
@Composable
private fun SimpleAccountsEmptyStaleAnchorPreview() = SimpleAccountsPreview(
    AccountsUiState.Ready(
        emptyList(),
        lightClients = listOf(
            NetworkLightClient(
                100,
                "Gnosis Chain",
                testnet = false,
                status = catchingUpStripSample,
                staleAnchor = StaleAnchorWarning.of(Network.GNOSIS, LightClientStatus.StaleAnchor(6, 3)),
            ),
        ),
    ),
)

/** No accounts yet, Ethereum syncing from the first start and Gnosis Chain synced: its line alone (#66). */
@Preview
@Composable
private fun SimpleAccountsEmptySyncingPreview() = SimpleAccountsPreview(
    AccountsUiState.Ready(
        emptyList(),
        lightClients = listOf(
            NetworkLightClient(
                1,
                "Ethereum",
                testnet = false,
                status = catchingUpStripSample.copy(simpleText = "Syncing Ethereum · 1 of 4 periods"),
            ),
            NetworkLightClient(100, "Gnosis Chain", testnet = false, status = verifiedStripSample),
        ),
    ),
)

/** The stored list was damaged and set aside: said above the empty list that follows. */
@Preview
@Composable
private fun SimpleAccountsSetAsidePreview() = SimpleAccountsPreview(
    AccountsUiState.Ready(
        emptyList(),
        notice = "Your saved account list was damaged and has been set aside. Add your accounts again.",
    ),
)

/** The stored list cannot be read: nothing can be added until it can, in the error colour. */
@Preview
@Composable
private fun SimpleAccountsListUnavailablePreview() = SimpleAccountsPreview(
    AccountsUiState.ListUnavailable(
        "Your saved account list cannot be read right now. Nothing has been changed, and no account can be " +
            "added until it can be read. Punicus keeps trying.",
    ),
)

/** Amounts hidden: the eye at the top left closed, every figure masked beside its unit. */
@Preview
@Composable
private fun SimpleAccountsHiddenPreview() = SimpleAccountsPreview(everyday.withAmountsHidden(true))

@Preview
@Composable
private fun SimpleAccountsHiddenDarkPreview() = SimpleAccountsPreview(everyday.withAmountsHidden(true), darkTheme = true)

/** The list not read yet. */
@Preview
@Composable
private fun SimpleAccountsLoadingPreview() = SimpleAccountsPreview(AccountsUiState.Loading())

/** Lenient mode: what each network has queued, under the freshness line. */
private val queued = everyday.copy(
    freshness = "Syncing Ethereum · 2 of 6 periods · updated 40 s ago",
    queued = listOf(
        "Ethereum: 1 waiting to send · Keep Punicus open to send it sooner",
        "Gnosis Chain: 1 needs your signature again",
    ),
)

@Preview
@Composable
private fun SimpleAccountsQueuedPreview() = SimpleAccountsPreview(queued)

@Preview
@Composable
private fun SimpleAccountsQueuedDarkPreview() = SimpleAccountsPreview(queued, darkTheme = true)

/** A queued transaction's sealed bytes could not be opened: said once, until dismissed. */
@Preview
@Composable
private fun SimpleAccountsLostQueuedPreview() = SimpleAccountsPreview(
    everyday.copy(
        lostNotice = "A transaction waiting to be sent was lost: the key that sealed it on this device is gone " +
            "(the key store was reset, or the app was restored on another device). If it was being sent " +
            "when Punicus last closed, it may have gone out. Before you sign it again, wait until " +
            "Punicus has caught up, and check the account's balance, or for a Safe, its queue.",
    ),
)
/** Every preview above, which SimpleAccountsScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleAccountsScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("everyday") { SimpleAccountsEverydayPreview() },
    ScreenPreview("everyday-dark") { SimpleAccountsEverydayDarkPreview() },
    ScreenPreview("refreshing") { SimpleAccountsRefreshingPreview() },
    ScreenPreview("syncing") { SimpleAccountsSyncingPreview() },
    ScreenPreview("unreachable") { SimpleAccountsUnreachablePreview() },
    ScreenPreview("live") { SimpleAccountsLivePreview() },
    ScreenPreview("stale-anchor") { SimpleAccountsStaleAnchorPreview() },
    ScreenPreview("empty") { SimpleAccountsEmptyPreview() },
    ScreenPreview("empty-syncing") { SimpleAccountsEmptySyncingPreview() },
    ScreenPreview("empty-stale-anchor") { SimpleAccountsEmptyStaleAnchorPreview() },
    ScreenPreview("loading") { SimpleAccountsLoadingPreview() },
    ScreenPreview("set-aside") { SimpleAccountsSetAsidePreview() },
    ScreenPreview("list-unavailable") { SimpleAccountsListUnavailablePreview() },
    ScreenPreview("hidden") { SimpleAccountsHiddenPreview() },
    ScreenPreview("hidden-dark") { SimpleAccountsHiddenDarkPreview() },
    ScreenPreview("queued") { SimpleAccountsQueuedPreview() },
    ScreenPreview("queued-dark") { SimpleAccountsQueuedDarkPreview() },
    ScreenPreview("lost-queued") { SimpleAccountsLostQueuedPreview() },
)
