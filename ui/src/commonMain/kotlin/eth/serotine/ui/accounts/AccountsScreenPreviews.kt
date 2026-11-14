package eth.serotine.ui.accounts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.LightClientHealth.NOT_RUNNING
import eth.serotine.ui.components.LightClientHealth.NOT_VERIFIED
import eth.serotine.ui.components.LightClientHealth.STALE
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.StaleAnchorDialog
import eth.serotine.ui.components.StaleAnchorWarning
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableFix
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.catchingUpStripSample
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.components.CheckpointFetchState
import eth.serotine.ui.theme.SerotineTheme
import eth.serotine.ui.update.UpgradeBannerItem
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Accounts screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. AccountsScreenPreviewsTest renders each one in accountsScreenPreviews, at the end.
// The addresses are EIP-55's own examples, and the amounts are made up. Ethereum and Gnosis Chain
// come with parts 4 and 5 of docs/networks.md; their sections here are drawn as they will be.

private const val ETHEREUM = 1L
private const val GNOSIS = 100L
private val sepoliaId = Network.SEPOLIA.chainId

/** A Sepolia section with [accounts] under [status]: the only network of a debug build. */
private fun sepolia(
    status: LightClientItem,
    accounts: List<AccountItem>,
    total: String? = "$3,125.00",
    totalNote: String? = "Testnet prices · 1 not verified",
) = NetworkSection(sepoliaId, "Sepolia", testnet = true, on = true, status, accounts, total, totalNote)

private val synced = LightClientItem("Verified at block 8,412,307 · 8 s ago", null, false, VERIFIED)

private val accounts = listOf(
    AccountItem(
        "Daily",
        "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        Network.SEPOLIA.chainId,
        AccountKind.EOA,
        Control.FULL_CONTROL,
        "1.25 ETH",
        balanceVerified = true,
        value = "$3,125.00 · testnet prices",
    ),
    AccountItem(
        "Savings",
        "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        Network.SEPOLIA.chainId,
        AccountKind.EOA,
        Control.WATCH_ONLY,
        "< 0.0001 ETH",
        balanceVerified = true,
        value = "< $0.01 · testnet prices",
    ),
    AccountItem(
        "Treasury",
        "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
        Network.SEPOLIA.chainId,
        AccountKind.SAFE,
        Control.CO_SIGNER,
        "Not verified",
        balanceVerified = false,
        signers = "2/3",
        kindNote = "Safe",
    ),
)

private val withAccounts = AccountsUiState.Ready(
    listOf(sepolia(synced, accounts)),
    total = "$3,125.00",
    totalLabel = "Total · USD · Sepolia",
    totalNote = "Testnet prices · 1 not verified",
)

@Composable
private fun AccountsPreview(state: AccountsUiState, darkTheme: Boolean = false, fabExpanded: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        AccountsScreen(
            state = state,
            onWatchAddress = {},
            onImportPhrase = {},
            onCreateAccount = {},
            onCreateSafe = {},
            onAccountAction = {},
            fabExpandedAtStart = fabExpanded,
        )
    }
}

/** No account whose key is held here: Create Safe greyed out in the open + menu (owner ruling #55). */
private val noKeyHere = AccountsUiState.Ready(emptyList())

@Preview
@Composable
private fun AccountsCreateSafeGreyedOutPreview() = AccountsPreview(noKeyHere, fabExpanded = true)

@Preview
@Composable
private fun AccountsCreateSafeReasonPreview() = SerotineTheme {
    UnavailableDialog(
        UnavailableNotice(
            "Create Safe",
            CREATE_SAFE_NEEDS_A_KEY,
            listOf(UnavailableFix("Import recovery phrase") {}, UnavailableFix("Create account") {}),
        ),
        onDismiss = {},
    )
}

/** The dialog a tap on a greyed-out Send opens, for a watched account (owner ruling, 2026-10-18, #55). */
@Preview
@Composable
private fun AccountsSendReasonPreview() = SerotineTheme {
    UnavailableDialog(
        UnavailableNotice("Send", sendBlockedReason(AccountKind.EOA, Control.WATCH_ONLY).orEmpty()),
        onDismiss = {},
    )
}

@Preview
@Composable
private fun AccountsAccountsPreview() = AccountsPreview(withAccounts)

@Preview
@Composable
private fun AccountsAccountsDarkPreview() = AccountsPreview(withAccounts, darkTheme = true)

/** Amounts hidden: the eye at the top left closed, the total, each network's and each row's masked. */
@Preview
@Composable
private fun AccountsHiddenPreview() = AccountsPreview(withAccounts.withAmountsHidden(true))

@Preview
@Composable
private fun AccountsHiddenDarkPreview() = AccountsPreview(withAccounts.withAmountsHidden(true), darkTheme = true)

/** The screen on a narrow phone (#68): a control badge too wide for its row shows as its icon. */
@Preview
@Composable
private fun AccountsNarrowPreview() = Box(Modifier.width(360.dp).fillMaxHeight()) { AccountsPreview(withAccounts) }

/** No verified head yet and no balance proven before: the strip red, every row syncing. */
private val catchingUp = AccountsUiState.Ready(
    listOf(
        sepolia(
            LightClientItem("Verifying sync committees · 1 of 4 periods", 0.25f, true, NOT_VERIFIED),
            // Until a Safe is read it is never shown as more than watch-only (AccountItem.from).
            accounts.map {
                it.copy(
                    balance = "Syncing…",
                    balanceVerified = false,
                    value = null,
                    signers = null,
                    control = if (it.kind == AccountKind.SAFE) Control.WATCH_ONLY else it.control,
                )
            },
            total = "Checking…",
            totalNote = "Testnet prices · still checking",
        ),
    ),
    total = "Checking…",
    totalLabel = "Total · USD · Sepolia",
    totalNote = "Testnet prices · still checking",
)

@Preview
@Composable
private fun AccountsCatchingUpPreview() = AccountsPreview(catchingUp)

@Preview
@Composable
private fun AccountsCatchingUpDarkPreview() = AccountsPreview(catchingUp, darkTheme = true)

/** The verified head too old to read at: the amounts last verified, each said to be so. */
private val headTooOld = AccountsUiState.Ready(
    listOf(
        sepolia(
            LightClientItem("Synced · the verified head is 4 min old, waiting for a fresh one", null, true, STALE),
            accounts.map {
                it.copy(
                    balanceVerified = false,
                    balanceNote = if (it.balanceVerified) {
                        "Last verified Oct 19, 2026, 2:32 PM · may have changed since"
                    } else {
                        null
                    },
                    value = null,
                )
            },
            total = "Checking…",
            totalNote = "Testnet prices · still checking",
        ),
    ),
    total = "Checking…",
    totalLabel = "Total · USD · Sepolia",
    totalNote = "Testnet prices · still checking",
)

@Preview
@Composable
private fun AccountsHeadTooOldPreview() = AccountsPreview(headTooOld)

@Preview
@Composable
private fun AccountsHeadTooOldDarkPreview() = AccountsPreview(headTooOld, darkTheme = true)

@Preview
@Composable
private fun AccountsNoAccountsPreview() = AccountsPreview(AccountsUiState.Ready(emptyList()))

/** No accounts yet, and the networks that are on syncing from the first start (#66). */
private val noAccountsSyncing = AccountsUiState.Ready(
    emptyList(),
    lightClients = listOf(
        NetworkLightClient(1, "Ethereum", testnet = false, status = catchingUpStripSample),
        NetworkLightClient(100, "Gnosis Chain", testnet = false, status = verifiedStripSample),
    ),
)

@Preview
@Composable
private fun AccountsNoAccountsSyncingPreview() = AccountsPreview(noAccountsSyncing)

@Preview
@Composable
private fun AccountsNoAccountsSyncingDarkPreview() = AccountsPreview(noAccountsSyncing, darkTheme = true)

/** No accounts yet, and Gnosis Chain's light client refusing a stale checkpoint on the first start. */
@Preview
@Composable
private fun AccountsNoAccountsStaleAnchorPreview() = AccountsPreview(
    noAccountsSyncing.copy(
        lightClients = listOf(
            noAccountsSyncing.lightClients[0],
            NetworkLightClient(
                100,
                "Gnosis Chain",
                testnet = false,
                status = LightClientItem(
                    "Not syncing: the last trusted checkpoint is 6 periods old, past the safe limit of 3",
                    null,
                    false,
                    NOT_VERIFIED,
                ),
                staleAnchor = StaleAnchorWarning.of(Network.GNOSIS, LightClientStatus.StaleAnchor(6, 3)),
            ),
        ),
    ),
)

@Preview
@Composable
private fun AccountsLoadingPreview() =
    AccountsPreview(AccountsUiState.Loading())

@Preview
@Composable
private fun AccountsLoadingCreateSafeGreyedOutPreview() = AccountsPreview(
    AccountsUiState.Loading(),
    fabExpanded = true,
)

@Preview
@Composable
private fun AccountsListSetAsidePreview() = AccountsPreview(
    AccountsUiState.Ready(
        listOf(
            sepolia(
                synced,
                listOf(accounts.first().copy(balance = "Checking…", balanceVerified = false, value = null)),
                total = "Checking…",
                totalNote = "Testnet prices · still checking",
            ),
        ),
        notice = "Your saved account list was damaged and has been set aside. Add your accounts again.",
        total = "Checking…",
        totalLabel = "Total · USD · Sepolia",
        totalNote = "Testnet prices · still checking",
    ),
)

private const val UPGRADE_DETAILS = "Reported by peers in 5 networks, not verified · fork id 0x6c1d9423"

@Preview
@Composable
private fun AccountsUpgradeAheadPreview() = AccountsPreview(
    withAccounts.copy(
        upgrades = listOf(
            UpgradeBannerItem(
                "Network upgrade ahead on Sepolia: update Punicus",
                "Peers announce a Sepolia network upgrade on Nov 6, 2026, 3:00 PM that this version of Punicus " +
                    "does not support. Update the app before it activates, or Punicus stops verifying at the " +
                    "upgrade.",
                UPGRADE_DETAILS,
                urgent = false,
            ),
        ),
    ),
)

@Preview
@Composable
private fun AccountsUpgradeActivatedDarkPreview() = AccountsPreview(
    withAccounts.copy(
        upgrades = listOf(
            UpgradeBannerItem(
                "Network upgrade reported on Sepolia: update Punicus",
                "Peers report that Sepolia upgraded on Nov 6, 2026, 3:00 PM to rules this version of Punicus " +
                    "does not support. Update the app to keep using your wallet.",
                UPGRADE_DETAILS,
                urgent = false,
            ),
        ),
    ),
    darkTheme = true,
)

@Preview
@Composable
private fun AccountsListUnavailablePreview() = AccountsPreview(
    AccountsUiState.ListUnavailable(
        "Your saved account list cannot be read right now. Nothing has been changed, and no account can be " +
            "added until it can be read. Punicus keeps trying.",
    ),
)

@Preview
@Composable
private fun AccountsEnsNamesPreview() = AccountsPreview(
    withAccounts.copy(
        sections = listOf(
            sepolia(
                synced,
                listOf(
                    accounts[0],
                    // Named by the ENS name it was watched by, which still points to it.
                    accounts[1].copy(label = "savings-example.eth"),
                    // Its ENS name no longer points to it: the address stands in for the name.
                    AccountItem(
                        "0xD122…9aDb",
                        "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
                        Network.SEPOLIA.chainId,
                        AccountKind.EOA,
                        Control.WATCH_ONLY,
                        "0.42 ETH",
                        balanceVerified = true,
                        value = "$1,050.00 · testnet prices",
                        nameNote = "ENS name “old-savings-example.eth” no longer points to this address",
                    ),
                ),
            ),
        ),
    ),
)

private val main = AccountItem(
    "Main",
    "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
    ETHEREUM,
    AccountKind.EOA,
    Control.FULL_CONTROL,
    "3.2041 ETH",
    balanceVerified = true,
    value = "€9,612.30",
)

private val ethereum = NetworkSection(
    ETHEREUM,
    "Ethereum",
    testnet = false,
    on = true,
    LightClientItem("Verified at block 23,618,402 · 6 s ago", null, false, VERIFIED),
    listOf(
        main,
        AccountItem(
            "Family Safe",
            "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
            ETHEREUM,
            AccountKind.SAFE,
            Control.CO_SIGNER,
            "1.076 ETH",
            balanceVerified = true,
            value = "€3,227.80",
            signers = "2/3",
            kindNote = "Safe",
        ),
    ),
    total = "€12,840.10",
)

private val gnosis = NetworkSection(
    GNOSIS,
    "Gnosis Chain",
    testnet = false,
    on = true,
    LightClientItem("Verified at block 42,907,115 · 3 s ago", null, false, VERIFIED),
    listOf(main.copy(chainId = GNOSIS, balance = "1,571.2 XDAI", value = "€1,446.30")),
    total = "€1,446.30",
)

private val savings = AccountItem(
    "Savings",
    "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
    sepoliaId,
    AccountKind.EOA,
    Control.WATCH_ONLY,
    "0.84 ETH",
    balanceVerified = true,
    value = "€2,520.00 · testnet prices",
)

/** Docs/networks.md's N1: a section per network, the top total leaving Sepolia out. */
private val threeNetworks = AccountsUiState.Ready(
    listOf(
        ethereum,
        gnosis,
        sepolia(
            LightClientItem("Verified at block 9,412,118 · 8 s ago", null, false, VERIFIED),
            listOf(savings),
            total = "€2,520.00",
            totalNote = "Testnet prices",
        ),
    ),
    total = "€14,286.40",
    totalLabel = "Total · EUR · Ethereum and Gnosis Chain",
    totalNote = "Sepolia not included",
)

@Preview
@Composable
private fun AccountsThreeNetworksPreview() = AccountsPreview(threeNetworks)

@Preview
@Composable
private fun AccountsThreeNetworksDarkPreview() = AccountsPreview(threeNetworks, darkTheme = true)

/** N2: Gnosis Chain catching up, its row last verified; the other sections unaffected. */
@Preview
@Composable
private fun AccountsOneNetworkCatchingUpPreview() = AccountsPreview(
    threeNetworks.copy(
        sections = listOf(
            ethereum,
            gnosis.copy(
                status = LightClientItem("Verifying sync committees · 3 of 7 periods", 3f / 7, true, NOT_VERIFIED),
                accounts = gnosis.accounts.map {
                    it.copy(
                        balanceVerified = false,
                        balanceNote = "Last verified Oct 21, 2026, 6:02 PM · may have changed since",
                        value = null,
                    )
                },
                total = "Checking…",
                totalNote = "1 not verified",
            ),
            threeNetworks.sections[2],
        ),
        total = "€12,840.10",
        totalNote = "1 not verified · Sepolia not included",
    ),
)

/**
 * A network this version can no longer follow while the others still can: its banner, urgent, and
 * its section in the error colour, its accounts' Send greyed out (docs/networks.md, *Upgrades and
 * the update screen*).
 */
private val networkBlocked =
    threeNetworks.copy(
        sections = listOf(
            ethereum,
            gnosis.copy(
                status = LightClientItem("Catching up · 2 of 3 periods", 2f / 3, true, NOT_VERIFIED),
                accounts = gnosis.accounts.map {
                    it.copy(
                        balanceVerified = false,
                        balanceNote = "Last verified Nov 6, 2026, 2:58 PM · may have changed since",
                        value = null,
                        networkBlocked = GNOSIS_BLOCKED,
                    )
                },
                total = "Checking…",
                totalNote = "1 not verified",
                blocked = GNOSIS_BLOCKED,
            ),
            threeNetworks.sections[2],
        ),
        total = "€12,840.10",
        totalNote = "1 not verified · Sepolia not included",
        upgrades = listOf(
            UpgradeBannerItem(
                "Update required to follow Gnosis Chain",
                "Peers report that Gnosis Chain upgraded on Nov 6, 2026, 3:00 PM to rules this version of " +
                    "Punicus does not support, and its light client is not verifying the chain. Update the app " +
                    "to keep using your wallet.",
                UPGRADE_DETAILS,
                urgent = true,
            ),
        ),
    )

@Preview
@Composable
private fun AccountsNetworkBlockedPreview() = AccountsPreview(networkBlocked)

@Preview
@Composable
private fun AccountsNetworkBlockedDarkPreview() = AccountsPreview(networkBlocked, darkTheme = true)

private const val GNOSIS_BLOCKED = "This version of Punicus can no longer follow Gnosis Chain: nothing can be sent " +
    "from its accounts until you update the app."

/**
 * Gnosis Chain's light client refusing a checkpoint past its weak-subjectivity window, the
 * section warning with its "Catch up…" (owner's request, 2026-10-24).
 */
private val staleAnchor = threeNetworks.copy(
    sections = listOf(
        ethereum,
        gnosis.copy(
            status = LightClientItem(
                "Not syncing: the last trusted checkpoint is 6 periods old, past the safe limit of 3",
                null,
                false,
                NOT_VERIFIED,
            ),
            accounts = gnosis.accounts.map {
                it.copy(balance = "Sync refused", balanceVerified = false, value = null)
            },
            total = null,
            staleAnchor = StaleAnchorWarning.of(Network.GNOSIS, LightClientStatus.StaleAnchor(6, 3)),
        ),
        threeNetworks.sections[2],
    ),
)

@Preview
@Composable
private fun AccountsStaleAnchorPreview() = AccountsPreview(staleAnchor)

@Preview
@Composable
private fun AccountsStaleAnchorDarkPreview() = AccountsPreview(staleAnchor, darkTheme = true)

/** What "Catch up…" offers: a fresh checkpoint from the servers, or catching up from the old one anyway. */
@Preview
@Composable
private fun AccountsStaleAnchorDialogPreview() = SerotineTheme {
    StaleAnchorDialog(StaleAnchorWarning.of(Network.GNOSIS, LightClientStatus.StaleAnchor(6, 3)), {}, {})
}

/** The dialog while the checkpoint servers and the long-known peers are asked: nothing else can be chosen. */
@Preview
@Composable
private fun AccountsStaleAnchorFetchingPreview() = SerotineTheme {
    StaleAnchorDialog(
        StaleAnchorWarning.of(Network.GNOSIS, LightClientStatus.StaleAnchor(6, 3), CheckpointFetchState.Fetching),
        {},
        {},
    )
}

/** The servers agreed, but no long-known peer confirmed it: the warning, and "Use it anyway". */
@Preview
@Composable
private fun AccountsStaleAnchorUnconfirmedPreview() = SerotineTheme {
    StaleAnchorDialog(
        StaleAnchorWarning.of(
            Network.GNOSIS,
            LightClientStatus.StaleAnchor(6, 3),
            CheckpointFetchState.Refused("no long-known peer could be reached to confirm it", unconfirmed = true),
        ),
        {},
        {},
    )
}

/** One server named another checkpoint: nothing changed, and the one that differs is named. */
@Preview
@Composable
private fun AccountsStaleAnchorRefusedPreview() = SerotineTheme {
    StaleAnchorDialog(
        StaleAnchorWarning.of(
            Network.GNOSIS,
            LightClientStatus.StaleAnchor(6, 3),
            CheckpointFetchState.Refused(
                "checkpoint-b.example named another root for epoch 1,204,512 than checkpoint-a.example",
                unconfirmed = false,
            ),
        ),
        {},
        {},
    )
}

/** A display currency Gnosis Chain and Sepolia have no rate for: each section says so, out of the total. */
@Preview
@Composable
private fun AccountsNoRateOnANetworkPreview() = AccountsPreview(
    threeNetworks.copy(
        sections = listOf(
            ethereum.copy(total = "CHF 11,912.40"),
            gnosis.copy(
                accounts = gnosis.accounts.map { it.copy(value = "No CHF rate") },
                total = "No CHF rate",
                totalNote = "No CHF rate on Gnosis Chain · not in the total",
            ),
            threeNetworks.sections[2].let { sepolia ->
                sepolia.copy(
                    accounts = sepolia.accounts.map { it.copy(value = "No CHF rate") },
                    total = "No CHF rate",
                    totalNote = "No CHF rate on Sepolia · not in the total",
                )
            },
        ),
        total = "CHF 11,912.40",
        totalLabel = "Total · CHF · Ethereum",
        totalNote = "Sepolia not included · Gnosis Chain has no CHF rate",
    ),
)

/** N18: Sepolia off, its account dimmed with its last verified balance, out of every total. */
private val sepoliaOff = threeNetworks.copy(
    sections = listOf(
        ethereum,
        gnosis,
        NetworkSection(
            sepoliaId,
            "Sepolia",
            testnet = true,
            on = false,
            LightClientItem("Off in this wallet · nothing is checked", null, false, NOT_RUNNING),
            listOf(
                savings.copy(
                    label = "Test",
                    control = Control.FULL_CONTROL,
                    balanceVerified = false,
                    balanceNote = "Last verified Oct 21, 2026, 6:02 PM · Sepolia is off",
                    value = null,
                    networkOff = "Sepolia",
                ),
            ),
            total = "Off",
        ),
    ),
    totalNote = null,
)

@Preview
@Composable
private fun AccountsNetworkOffPreview() = AccountsPreview(sepoliaOff)

@Preview
@Composable
private fun AccountsNetworkOffDarkPreview() = AccountsPreview(sepoliaOff, darkTheme = true)

/** A tap on Send of an account whose network is off: why, and Turn on (N19). */
@Preview
@Composable
private fun AccountsSendNeedsNetworkPreview() = SerotineTheme {
    UnavailableDialog(
        UnavailableNotice(
            "Send needs Sepolia",
            networkOffReason("Sepolia", "Test"),
            listOf(UnavailableFix("Turn on Sepolia") {}),
        ),
        onDismiss = {},
    )
}

/** Lenient mode: a transfer queued from Sepolia while it catches up, said under its line. */
private val queuedWhileCatchingUp = catchingUp.copy(
    sections = catchingUp.sections.map {
        it.copy(queued = "1 waiting to send · Keep Punicus open to send it sooner")
    },
)

@Preview
@Composable
private fun AccountsQueuedPreview() = AccountsPreview(queuedWhileCatchingUp)

@Preview
@Composable
private fun AccountsQueuedDarkPreview() = AccountsPreview(queuedWhileCatchingUp, darkTheme = true)

/** A queued transaction's sealed bytes could not be opened (the seal key gone): said once, until dismissed. */
@Preview
@Composable
private fun AccountsLostQueuedPreview() = AccountsPreview(
    withAccounts.copy(
        lostNotice = "A transaction waiting to be sent was lost: the key that sealed it on this device is gone " +
            "(the key store was reset, or the app was restored on another device). If it was being sent " +
            "when Punicus last closed, it may have gone out. Before you sign it again, wait until " +
            "Punicus has caught up, and check the account's balance, or for a Safe, its queue.",
    ),
)
/** Every preview above, which AccountsScreenPreviewsTest renders: a new preview goes in here too. */
internal val accountsScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("accounts") { AccountsAccountsPreview() },
    ScreenPreview("accounts-dark") { AccountsAccountsDarkPreview() },
    ScreenPreview("catching-up") { AccountsCatchingUpPreview() },
    ScreenPreview("head-too-old") { AccountsHeadTooOldPreview() },
    ScreenPreview("head-too-old-dark") { AccountsHeadTooOldDarkPreview() },
    ScreenPreview("no-accounts") { AccountsNoAccountsPreview() },
    ScreenPreview("no-accounts-syncing") { AccountsNoAccountsSyncingPreview() },
    ScreenPreview("no-accounts-syncing-dark") { AccountsNoAccountsSyncingDarkPreview() },
    ScreenPreview("no-accounts-stale-anchor") { AccountsNoAccountsStaleAnchorPreview() },
    ScreenPreview("create-safe-greyed-out") { AccountsCreateSafeGreyedOutPreview() },
    ScreenPreview("create-safe-reason") { AccountsCreateSafeReasonPreview() },
    ScreenPreview("send-reason") { AccountsSendReasonPreview() },
    ScreenPreview("loading") { AccountsLoadingPreview() },
    ScreenPreview("loading-create-safe-greyed-out") { AccountsLoadingCreateSafeGreyedOutPreview() },
    ScreenPreview("list-set-aside") { AccountsListSetAsidePreview() },
    ScreenPreview("upgrade-ahead") { AccountsUpgradeAheadPreview() },
    ScreenPreview("upgrade-activated-dark") { AccountsUpgradeActivatedDarkPreview() },
    ScreenPreview("list-unavailable") { AccountsListUnavailablePreview() },
    ScreenPreview("catching-up-dark") { AccountsCatchingUpDarkPreview() },
    ScreenPreview("narrow") { AccountsNarrowPreview() },
    ScreenPreview("ens-names") { AccountsEnsNamesPreview() },
    ScreenPreview("three-networks") { AccountsThreeNetworksPreview() },
    ScreenPreview("three-networks-dark") { AccountsThreeNetworksDarkPreview() },
    ScreenPreview("one-network-catching-up") { AccountsOneNetworkCatchingUpPreview() },
    ScreenPreview("network-off") { AccountsNetworkOffPreview() },
    ScreenPreview("network-off-dark") { AccountsNetworkOffDarkPreview() },
    ScreenPreview("send-needs-network") { AccountsSendNeedsNetworkPreview() },
    ScreenPreview("network-blocked") { AccountsNetworkBlockedPreview() },
    ScreenPreview("no-rate-on-a-network") { AccountsNoRateOnANetworkPreview() },
    ScreenPreview("network-blocked-dark") { AccountsNetworkBlockedDarkPreview() },
    ScreenPreview("stale-anchor") { AccountsStaleAnchorPreview() },
    ScreenPreview("stale-anchor-dark") { AccountsStaleAnchorDarkPreview() },
    ScreenPreview("stale-anchor-dialog") { AccountsStaleAnchorDialogPreview() },
    ScreenPreview("stale-anchor-fetching") { AccountsStaleAnchorFetchingPreview() },
    ScreenPreview("stale-anchor-unconfirmed") { AccountsStaleAnchorUnconfirmedPreview() },
    ScreenPreview("stale-anchor-refused") { AccountsStaleAnchorRefusedPreview() },
    ScreenPreview("hidden") { AccountsHiddenPreview() },
    ScreenPreview("hidden-dark") { AccountsHiddenDarkPreview() },
    ScreenPreview("queued") { AccountsQueuedPreview() },
    ScreenPreview("queued-dark") { AccountsQueuedDarkPreview() },
    ScreenPreview("lost-queued") { AccountsLostQueuedPreview() },
)
