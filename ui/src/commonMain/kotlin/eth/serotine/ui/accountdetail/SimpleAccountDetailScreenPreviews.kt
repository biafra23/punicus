package eth.serotine.ui.accountdetail

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.accounts.UnreachableNetwork
import eth.serotine.ui.activity.ActivityKey
import eth.serotine.ui.activity.ActivityRow
import eth.serotine.ui.activity.ActivityState
import eth.serotine.ui.activity.ActivityUiState
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// One account in simple mode (docs/simple-ui.md, H4 and H5) in Android Studio's preview pane, one
// preview per state it draws differently. Built from display-ready values, as the screen tests
// are, never from the ViewModel. SimpleAccountDetailScreenPreviewsTest renders each one in
// simpleAccountDetailScreenPreviews, at the end. The addresses are EIP-55's own examples, and the
// amounts and hashes are made up.

private const val MAIN = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val SAVINGS = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val FAMILY_SAFE = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"

private val ethereum = NetworkLabel(1, "Ethereum", testnet = false)
private val gnosis = NetworkLabel(100, "Gnosis Chain", testnet = false)

// The block a detailed row names is in the state; simple mode leaves it out.
private val ether = AssetRow("ETH", "Ether", "3.2041 ETH", "eth", true, "block 23,614,077", "€9,612.30")
private val usdc = AssetRow(
    "USDC",
    "USD Coin",
    "1,250 USDC",
    "usdc",
    true,
    "block 23,614,077",
    "€1,151.25",
    contract = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
)
private val link = AssetRow(
    "LINK",
    "Chainlink",
    "40 LINK",
    "link",
    true,
    "block 23,614,077",
    "€512.00",
    contract = "0x514910771AF9Ca656af840dff83E8264EcF986CA",
)

private val main = AccountDetailUiState.Ready(
    label = "Main",
    address = MAIN,
    network = ethereum,
    kind = AccountKind.EOA,
    control = Control.FULL_CONTROL,
    total = "€11,275.55",
    totalNote = null,
    assets = listOf(ether, usdc, link),
    pricesNote = "Prices: Chainlink feeds, read through the light client",
    freshness = "Updated 40 s ago · pull down to refresh",
)

@Composable
private fun SimpleAccountDetailPreview(state: AccountDetailUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleAccountDetailScreen(
            state = state,
            onBack = {},
            onSend = {},
            onSwap = {},
            onReceive = {},
            onCopy = {},
            onRename = {},
            onRemove = {},
            onHideToken = { _, _ -> },
            onMessageShown = {},
            onSort = {},
            onSegment = {},
            onOpenSafeTx = {},
            onImportRequest = {},
            onOpenActivity = {},
            onRefresh = {},
        )
    }
}

/** H4: the account's assets, how fresh they are, the network on its blockie. */
@Preview
@Composable
private fun SimpleAccountAssetsPreview() = SimpleAccountDetailPreview(main)

@Preview
@Composable
private fun SimpleAccountAssetsDarkPreview() = SimpleAccountDetailPreview(main, darkTheme = true)

/** Amounts hidden: the eye beside Back closed, the total and each asset's amount and value masked. */
@Preview
@Composable
private fun SimpleAccountHiddenPreview() = SimpleAccountDetailPreview(main.withAmountsHidden(true))

@Preview
@Composable
private fun SimpleAccountHiddenDarkPreview() = SimpleAccountDetailPreview(main.withAmountsHidden(true), darkTheme = true)

/** Ethereum's amounts from before: each says how old it is, out of the total. */
private val fromBefore = main.copy(
    total = "Checking…",
    freshness = null,
    assets = listOf(
        ether.copy(
            verified = false,
            verifiedAt = null,
            value = null,
            lastKnown = "Last verified Oct 27, 2026, 9:12 AM · may have changed since",
            ageNote = "3 h ago · may have changed",
        ),
        usdc.copy(
            verified = false,
            verifiedAt = null,
            value = null,
            lastKnown = "Last verified Oct 27, 2026, 9:12 AM · may have changed since",
            ageNote = "3 h ago · may have changed",
        ),
    ),
)

/** Ethereum cannot be checked: a banner, and each amount says how old it is. */
@Preview
@Composable
private fun SimpleAccountLastVerifiedPreview() = SimpleAccountDetailPreview(
    fromBefore.copy(
        cantCheck = UnreachableNetwork(
            1,
            "Can't check Ethereum right now",
            "Its amounts were last confirmed 3 h ago and may have changed. Sending from it waits until it can be " +
                "checked. The app keeps trying.",
        ),
    ),
)

/** Ethereum still catching up: the freshness line says so with its progress, and the banner that it is syncing. */
@Preview
@Composable
private fun SimpleAccountSyncingPreview() = SimpleAccountDetailPreview(
    fromBefore.copy(
        freshness = "Syncing Ethereum · 2 of 6 periods",
        cantCheck = UnreachableNetwork(
            1,
            "Ethereum is still syncing",
            "Its amounts were last confirmed 3 h ago and may have changed. Sending from it waits until it has " +
                "caught up.",
        ),
    ),
)

/** H5: the Activity, a time where one is known and how each ended in words, no block. */
@Preview
@Composable
private fun SimpleAccountActivityPreview() = SimpleAccountDetailPreview(
    main.copy(
        segment = DetailSegment.ACTIVITY,
        activity = ActivityUiState.Shown(
            rows = listOf(
                ActivityRow(
                    key = "0x" + "a1".repeat(32),
                    title = "Sent 250 USDC",
                    detail = "To vitalik.eth · 0xd8dA…6045",
                    about = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
                    time = "Oct 27, 2026, 2:02 PM",
                    status = "In block 23,614,012",
                    state = ActivityState.DONE,
                    opens = ActivityKey("0x" + "a1".repeat(32)),
                    simpleStatus = "Confirmed",
                ),
                ActivityRow(
                    key = "0x" + "b2".repeat(32) + ":4",
                    title = "Received 0.5 ETH",
                    detail = "From Family Safe · 0xdbF0…C6FB",
                    about = FAMILY_SAFE,
                    time = "Block 23,598,301",
                    status = "On chain",
                    state = ActivityState.DONE,
                    opens = ActivityKey("0x" + "b2".repeat(32), 4),
                    simpleTime = null,
                ),
            ),
            coverage = "Transactions sent from this device. On chain from block 23,590,000 on, when it was added " +
                "here: its token transfers.",
            simpleCoverage = "Activity since you added it. ETH it received, and transactions sent from it " +
                "elsewhere, are not listed yet.",
        ),
    ),
)

/** A Safe on Gnosis Chain: its threshold and network on its blockie, its owners checked on chain. */
@Preview
@Composable
private fun SimpleAccountSafePreview() = SimpleAccountDetailPreview(
    main.copy(
        label = "Family Safe",
        address = FAMILY_SAFE,
        network = gnosis,
        kind = AccountKind.SAFE,
        control = Control.CO_SIGNER,
        total = "€4,500.00",
        assets = listOf(AssetRow("XDAI", "xDAI", "4,861.20 XDAI", "xdai", true, "block 42,511,906", "€4,500.00")),
        kindNote = "Safe",
        threshold = "2/3",
        segment = DetailSegment.OWNERS,
        safe = SafeDetails.Shown(
            title = "Safe 1.4.1 · nonce 12",
            signers = "2 of 3 owners must sign",
            owners = listOf(
                OwnerRow(MAIN, "Main", heldHere = true),
                OwnerRow(SAVINGS, "Savings", heldHere = false),
                OwnerRow("0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb", null, heldHere = false),
            ),
            modules = emptyList(),
            guard = null,
            checkedAt = "Verified at block 42,511,906",
        ),
        queue = SafeQueue.Shown(emptyList()),
        freshness = "Updated 2 min ago · pull down to refresh",
    ),
)

/** H5: a swap whose step 2 waits, its card above the Activity with Continue and Dismiss. */
@Preview
@Composable
private fun SimpleAccountWaitingSwapPreview() = SimpleAccountDetailPreview(
    main.copy(
        segment = DetailSegment.ACTIVITY,
        activity = ActivityUiState.Shown(
            rows = emptyList(),
            coverage = "Transactions sent from this device.",
            simpleCoverage = "Activity since you added it.",
            waiting = listOf(WAITING_SWAP),
        ),
    ),
)

/** Ethereum turned off in this wallet: Refresh greyed out, each amount says the network is off. */
@Preview
@Composable
private fun SimpleAccountNetworkOffPreview() = SimpleAccountDetailPreview(
    main.copy(
        total = "Off",
        freshness = null,
        networkOn = false,
        assets = listOf(
            ether.copy(
                verified = false,
                verifiedAt = null,
                value = null,
                lastKnown = "Last verified Oct 27, 2026, 9:12 AM · Ethereum is off",
                ageNote = "Ethereum is off · 3 h ago",
            ),
        ),
    ),
)

/** Every preview above, which SimpleAccountDetailScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleAccountDetailScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SimpleAccountAssetsPreview() },
    { SimpleAccountAssetsDarkPreview() },
    { SimpleAccountLastVerifiedPreview() },
    { SimpleAccountSyncingPreview() },
    { SimpleAccountActivityPreview() },
    { SimpleAccountWaitingSwapPreview() },
    { SimpleAccountSafePreview() },
    { SimpleAccountNetworkOffPreview() },
    { SimpleAccountHiddenPreview() },
    { SimpleAccountHiddenDarkPreview() },
)
