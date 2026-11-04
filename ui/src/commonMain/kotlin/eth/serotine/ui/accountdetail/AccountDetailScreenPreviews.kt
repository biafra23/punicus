package eth.serotine.ui.accountdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.activity.ActivityKey
import eth.serotine.ui.activity.ActivityRow
import eth.serotine.ui.activity.ActivityState
import eth.serotine.ui.activity.ActivityUiState
import eth.serotine.ui.activity.WaitingStep
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.LightClientHealth
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.POOL_AVERAGE
import eth.serotine.ui.components.catchingUpStripSample
import eth.serotine.ui.components.staleStripSample
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The account detail screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. AccountDetailScreenPreviewsTest renders each one in accountDetailScreenPreviews,
// at the end. The addresses are EIP-55's own examples, and the amounts and hashes are made up.

private const val DAILY = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val SAVINGS = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val TREASURY = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"

private const val PRICES = "Prices: Chainlink feeds, read through the light client, testnet prices"

private val sepolia = NetworkLabel(11_155_111, "Sepolia", testnet = true)

private val ether = AssetRow(
    "ETH",
    "Ether",
    "1.25 ETH",
    "eth",
    verified = true,
    verifiedAt = "block 8,412,307",
    value = "$3,125.00 · testnet prices",
)

private val usdc = AssetRow(
    "USDC",
    "USDC",
    "250 USDC",
    "usdc",
    verified = true,
    verifiedAt = "block 8,412,305",
    value = "$250.00 · testnet prices",
    contract = "0x27b1fdb04752bbc536007a920d24acb045561c26",
)

private val uniswap = AssetRow(
    "UNI",
    "Uniswap",
    "3 UNI",
    "uni",
    verified = true,
    verifiedAt = "block 8,412,306",
    value = "No price",
    contract = "0xde709f2102306220921060314715629080e2fb77",
)

private val daily = AccountDetailUiState.Ready(
    label = "Daily",
    address = DAILY,
    network = sepolia,
    kind = AccountKind.EOA,
    control = Control.FULL_CONTROL,
    total = "$3,375.00",
    totalNote = "Testnet prices · 1 asset without a price",
    assets = listOf(ether, usdc, uniswap),
    pricesNote = PRICES,
)

private val treasury = AccountDetailUiState.Ready(
    label = "Treasury",
    address = TREASURY,
    network = sepolia,
    kind = AccountKind.SAFE,
    control = Control.CO_SIGNER,
    total = "$1,650.00",
    totalNote = "Testnet prices",
    assets = listOf(
        ether.copy(amount = "0.5 ETH", value = "$1,250.00 · testnet prices"),
        usdc.copy(amount = "400 USDC", value = "$400.00 · testnet prices"),
    ),
    pricesNote = PRICES,
    safe = SafeDetails.Shown(
        title = "Safe 1.4.1 · nonce 12",
        signers = "2 of 3 owners must sign",
        owners = listOf(
            OwnerRow(DAILY, "Daily", heldHere = true),
            OwnerRow(SAVINGS, "Savings", heldHere = false),
            OwnerRow("0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb", null, heldHere = false),
        ),
        modules = listOf("0x52908400098527886E0F7030069857D2E4169EE7"),
        guard = "0x8617E340B3D01FA5F11F306F4090FD50E238070D",
        checkedAt = "Verified at block 8,412,307",
    ),
    kindNote = "Safe",
    threshold = "2/3",
    queue = SafeQueue.Shown(
        rows = listOf(
            QueueRow(
                hash = "0x" + "3c".repeat(32),
                action = "Send 40 USDC",
                about = SAVINGS,
                nonce = "Nonce 12 · next",
                signatures = "1 of 2 signatures on this device",
            ),
            QueueRow(
                hash = "0x" + "4d".repeat(32),
                action = "Delegate call",
                about = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
                nonce = "Nonce 13 · 1 before it",
                signatures = "0 of 2 signatures on this device",
                refusal = "Not signed here: a delegate call",
            ),
        ),
    ),
)

/** A swap of 100 USDC for LINK whose step 2 waits, as its card in the Activity says it. */
internal val WAITING_SWAP = WaitingStep(
    title = "Swap 100 USDC → LINK",
    message = "Step 1 done. Step 2 is waiting for you.",
    sell = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
)

private const val ACTIVITY_COVERAGE = "Transactions sent from this device. Transactions received, and those sent " +
    "from this account elsewhere, are not listed yet."

private val activityRows = listOf(
    // A CoW order signed here, live: it opens the order's status.
    ActivityRow(
        key = "0x" + "c0".repeat(56),
        title = "CoW order: 40 USDC for at least 0.015851 ETH",
        detail = "On CoW · 0x9008…ab41",
        about = "0x9008D19f58AAbD9eD0D60971565AA8510560ab41",
        time = "Oct 19, 2026, 4:22 PM",
        status = "May fill until Oct 19, 2026, 4:27 PM",
        state = ActivityState.WAITING,
        opens = ActivityKey("0x" + "c0".repeat(56), cowOrder = true),
    ),
    ActivityRow(
        key = "0x" + "6".repeat(64),
        title = "Swapped 100 USDC for at least 4.28845 LINK",
        detail = "On Uniswap · 0x3bFA…e48E",
        about = "0x3bFA4769FB09eefC5a80d6E87c3B9C650f7Ae48E",
        time = "Oct 19, 2026, 4:10 PM",
        status = "In block 9,412,190",
        state = ActivityState.DONE,
        opens = ActivityKey("0x" + "6".repeat(64)),
    ),
    // A dApp's transaction, its name the dApp's own claim.
    ActivityRow(
        key = "0x" + "d".repeat(64),
        title = "Sent for a dApp stating it is “Example dApp”",
        detail = "To 0xfB69…d359",
        about = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        time = "Oct 19, 2026, 3:30 PM",
        status = "In block 9,411,870",
        state = ActivityState.DONE,
        opens = ActivityKey("0x" + "d".repeat(64)),
    ),
    ActivityRow(
        key = "0x" + "1".repeat(64),
        title = "Sent 0.25 ETH",
        detail = "To Bob · 0x5aAe…eAed",
        about = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        time = "Oct 19, 2026, 2:02 PM",
        status = "No block seen to include it yet",
        state = ActivityState.WAITING,
        opens = ActivityKey("0x" + "1".repeat(64)),
    ),
    ActivityRow(
        key = "0x" + "2".repeat(64),
        title = "Executed: Send 0.25 ETH",
        detail = "Safe nonce 4 · Treasury · 0xfB69…d359",
        about = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        time = "Oct 18, 2026, 9:15 AM",
        status = "Finalized in block 9,412,118",
        state = ActivityState.DONE,
        opens = ActivityKey("0x" + "2".repeat(64)),
    ),
    ActivityRow(
        key = "0x" + "3".repeat(64),
        title = "Sent 40 USDC",
        detail = "To 0xdbF0…C6FB",
        about = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
        time = "Oct 17, 2026, 9:40 PM",
        status = "Reverted in block 9,401,220",
        state = ActivityState.FAILED,
        opens = ActivityKey("0x" + "3".repeat(64)),
    ),
    ActivityRow(
        key = "0x" + "4".repeat(64),
        title = "Sent 1 ETH",
        detail = "To Carol · 0xD122…9aDb",
        about = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
        time = "Oct 12, 2026, 11:03 AM",
        status = "Mined while the app was not watching, unless another transaction took its nonce",
        state = ActivityState.UNKNOWN,
        opens = ActivityKey("0x" + "4".repeat(64)),
    ),
    ActivityRow(
        key = "0x" + "5".repeat(64),
        title = "Deployed a Safe",
        detail = "Safe Vault · 0x5290…9EE7",
        about = "0x52908400098527886E0F7030069857D2E4169EE7",
        time = "Oct 10, 2026, 8:30 AM",
        status = "No peer confirmed its broadcast",
        state = ActivityState.UNCONFIRMED,
        opens = ActivityKey("0x" + "5".repeat(64)),
    ),
)

private const val SAFE_ACTIVITY_COVERAGE = "Transactions sent from this device. On chain from block 9,400,000 on, " +
    "when it was added here: its events, checked from block 9,410,000 so far; its token transfers."

private const val TOKENS_ACTIVITY_COVERAGE = "Transactions sent from this device. On chain from block 9,400,000 on, " +
    "when it was added here: its token transfers. ETH it received, and transactions sent from it elsewhere, " +
    "are not listed yet."

/**
 * An account's token transfers among a transaction sent from here, by date: each event dated by its
 * block's verified header where the engine reported it, and by its block alone where it did not.
 */
private val tokenActivityRows = listOf(
    ActivityRow(
        key = "0x" + "f".repeat(64) + ":1",
        title = "Received 0.01 USDC",
        detail = "From 0x5290…9EE7",
        about = "0x52908400098527886E0F7030069857D2E4169EE7",
        time = "Oct 19, 2026, 6:48 PM",
        status = "From an address you have not sent to: compare every character before you copy it",
        state = ActivityState.CAUTION,
        opens = ActivityKey("0x" + "f".repeat(64), 1),
    ),
    ActivityRow(
        key = "0x" + "b".repeat(64) + ":2",
        title = "Received 250 USDC",
        detail = "From Treasury · 0xfB69…d359",
        about = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        time = "Oct 19, 2026, 3:06 PM",
        status = "On chain, from the token's verified logs",
        state = ActivityState.DONE,
        opens = ActivityKey("0x" + "b".repeat(64), 2),
    ),
    ActivityRow(
        key = "0x" + "c".repeat(64),
        title = "Sent 40.5 USDC",
        detail = "To Bob · 0x5aAe…eAed",
        about = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        time = "Oct 19, 2026, 2:02 PM",
        status = "Finalized in block 9,412,118",
        state = ActivityState.DONE,
        opens = ActivityKey("0x" + "c".repeat(64)),
    ),
    // Dated by its block alone: the engine did not report when the block was produced.
    ActivityRow(
        key = "0x" + "d".repeat(64) + ":0",
        title = "An unlisted token logged a transfer to it",
        detail = "Contract 0x27b1…1c26",
        about = "0x27b1fdb04752bbc536007a920d24acb045561c26",
        time = "Block 9,408,511",
        simpleTime = null,
        status = "The claim of a contract the wallet does not list: open it to add it as a token and see what moved",
        state = ActivityState.UNKNOWN,
        opens = ActivityKey("0x" + "d".repeat(64), 0),
    ),
    ActivityRow(
        key = "0x" + "e".repeat(64) + ":5",
        title = "Received 1.25 LINK",
        detail = "From Carol · 0xdbF0…C6FB",
        about = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
        time = "Oct 17, 2026, 4:21 PM",
        status = "On chain, from the token's verified logs",
        state = ActivityState.DONE,
        opens = ActivityKey("0x" + "e".repeat(64), 5),
    ),
)

/**
 * A Safe's events among a transaction sent from here, as its Activity merges them by date; an
 * execution says what the Safe transaction did where this device holds its request.
 */
private val safeActivityRows = listOf(
    ActivityRow(
        key = "0x" + "a".repeat(64) + ":0",
        title = "Module enabled",
        detail = "0x8617…070D",
        about = "0x8617E340B3D01FA5F11F306F4090FD50E238070D",
        time = "Oct 19, 2026, 5:42 PM",
        status = "A module can move this Safe's funds without its owners",
        state = ActivityState.WARNING,
        opens = ActivityKey("0x" + "a".repeat(64), 0),
    ),
    ActivityRow(
        key = "0x" + "6".repeat(64) + ":0",
        title = "Received 0.5 ETH",
        detail = "From Bob · 0x5aAe…eAed",
        about = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        time = "Oct 19, 2026, 2:38 PM",
        status = "On chain, from the Safe's verified logs",
        state = ActivityState.DONE,
        opens = ActivityKey("0x" + "6".repeat(64), 0),
    ),
    ActivityRow(
        key = "0x" + "7".repeat(64),
        title = "Executed transaction 4: Send 0.25 ETH",
        detail = "By Alice · 0xfB69…d359",
        about = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        time = "Oct 19, 2026, 2:02 PM",
        status = "In block 9,412,118",
        state = ActivityState.DONE,
        opens = ActivityKey("0x" + "7".repeat(64)),
    ),
    ActivityRow(
        key = "0x" + "8".repeat(64) + ":3",
        title = "Owner added",
        detail = "Carol · 0xdbF0…C6FB",
        about = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
        time = "Oct 19, 2026, 9:51 AM",
        status = "On chain, from the Safe's verified logs",
        state = ActivityState.DONE,
        opens = ActivityKey("0x" + "8".repeat(64), 3),
    ),
    ActivityRow(
        key = "0x" + "9".repeat(64) + ":1",
        title = "Failed: Send 40 USDC",
        detail = "Safe transaction 0x3f1c…a9e2",
        about = "0x52908400098527886E0F7030069857D2E4169EE7",
        time = "Oct 19, 2026, 7:32 AM",
        status = "Failed on chain: its call reverted",
        state = ActivityState.FAILED,
        opens = ActivityKey("0x" + "9".repeat(64), 1),
    ),
)

@Composable
private fun AccountDetailPreview(
    state: AccountDetailUiState,
    darkTheme: Boolean = false,
    lightClient: LightClientItem = verifiedStripSample,
) {
    SerotineTheme(darkTheme = darkTheme) {
        AccountDetailScreen(
            state = state,
            chrome = AccountChrome.Strip(lightClient),
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
        )
    }
}

@Preview
@Composable
private fun AccountDetailAssetsPreview() = AccountDetailPreview(daily)

@Preview
@Composable
private fun AccountDetailAssetsDarkPreview() = AccountDetailPreview(daily, darkTheme = true)

/** Amounts hidden: the eye beside Back closed, the total and each asset's amount and value masked. */
@Preview
@Composable
private fun AccountDetailAssetsHiddenPreview() = AccountDetailPreview(daily.withAmountsHidden(true))

@Preview
@Composable
private fun AccountDetailAssetsHiddenDarkPreview() =
    AccountDetailPreview(daily.withAmountsHidden(true), darkTheme = true)

@Preview
@Composable
private fun AccountDetailCheckingPreview() = AccountDetailPreview(
    daily.copy(
        label = "Savings",
        address = SAVINGS,
        control = Control.WATCH_ONLY,
        total = "Checking…",
        totalNote = "Testnet prices · still checking",
        assets = listOf(ether.copy(amount = "Checking…", verified = false, verifiedAt = null, value = null)),
        pendingAssets = 3,
        tokensNote = "Checking 3 tokens",
    ),
    lightClient = catchingUpStripSample,
)

@Preview
@Composable
private fun AccountDetailCheckingDarkPreview() = AccountDetailPreview(
    daily.copy(
        label = "Savings",
        address = SAVINGS,
        control = Control.WATCH_ONLY,
        total = "Checking…",
        totalNote = "Testnet prices · still checking",
        assets = listOf(ether.copy(amount = "Checking…", verified = false, verifiedAt = null, value = null)),
        pendingAssets = 3,
        tokensNote = "Checking 3 tokens",
    ),
    darkTheme = true,
    lightClient = catchingUpStripSample,
)

private const val LAST_KNOWN = "Last verified Oct 19, 2026, 2:32 PM · may have changed since"

/** The verified head too old to read at: the amounts last verified, each said to be so. */
@Preview
@Composable
private fun AccountDetailHeadTooOldPreview() = AccountDetailPreview(
    daily.copy(
        total = "Checking…",
        totalNote = "Testnet prices · still checking",
        assets = listOf(ether, usdc, uniswap).map {
            it.copy(verified = false, verifiedAt = null, value = null, lastKnown = LAST_KNOWN)
        },
    ),
    lightClient = staleStripSample,
)

@Preview
@Composable
private fun AccountDetailByNameWithImpostorPreview() = AccountDetailPreview(
    daily.copy(
        assets = listOf(
            ether,
            usdc,
            AssetRow(
                "USDC",
                "USD Coin",
                "5000 USDC",
                null,
                verified = true,
                verifiedAt = "block 8,412,306",
                value = "No price",
                contract = "0xde709f2102306220921060314715629080e2fb77",
                warning = "Not the built-in USDC: another contract",
            ),
        ),
        sort = AssetSort.NAME,
    ),
)

@Preview
@Composable
private fun AccountDetailSafeAssetsPreview() = AccountDetailPreview(treasury)

@Preview
@Composable
private fun AccountDetailSafeOwnersPreview() = AccountDetailPreview(treasury.copy(segment = DetailSegment.OWNERS))

@Preview
@Composable
private fun AccountDetailSafeOwnersNetworkOffPreview() =
    AccountDetailPreview(treasury.copy(segment = DetailSegment.OWNERS, networkOn = false))

@Preview
@Composable
private fun AccountDetailSafeQueuePreview() = AccountDetailPreview(treasury.copy(segment = DetailSegment.QUEUE))

@Preview
@Composable
private fun AccountDetailSafeQueueDarkPreview() =
    AccountDetailPreview(treasury.copy(segment = DetailSegment.QUEUE), darkTheme = true)

@Preview
@Composable
private fun AccountDetailSafeQueueEmptyPreview() = AccountDetailPreview(
    treasury.copy(segment = DetailSegment.QUEUE, queue = SafeQueue.Shown(rows = emptyList())),
)

@Preview
@Composable
private fun AccountDetailSafeQueueUnverifiedPreview() = AccountDetailPreview(
    treasury.copy(
        segment = DetailSegment.QUEUE,
        queue = (treasury.queue as SafeQueue.Shown).copy(
            note = "The Safe cannot be verified right now, so neither its nonce nor these signatures are checked.",
        ),
    ),
)

@Preview
@Composable
private fun AccountDetailSafeQueueUnreadablePreview() = AccountDetailPreview(
    treasury.copy(
        segment = DetailSegment.QUEUE,
        queue = SafeQueue.Unavailable(
            "This device's queue of Safe transactions cannot be read right now. Serotine keeps trying.",
        ),
    ),
)

@Preview
@Composable
private fun AccountDetailSafeQueueReadingPreview() =
    AccountDetailPreview(treasury.copy(segment = DetailSegment.QUEUE, queue = SafeQueue.Checking))

@Preview
@Composable
private fun AccountDetailSafeReadingPreview() = AccountDetailPreview(
    treasury.copy(
        control = Control.WATCH_ONLY,
        safe = SafeDetails.Checking,
        segment = DetailSegment.OWNERS,
        threshold = null,
    ),
)

@Preview
@Composable
private fun AccountDetailNotASafePreview() = AccountDetailPreview(
    treasury.copy(
        control = Control.WATCH_ONLY,
        safe = SafeDetails.Unavailable("This address no longer holds a recognised Safe: its owners are not shown."),
        segment = DetailSegment.OWNERS,
        kindNote = "Not a recognised Safe",
        threshold = null,
    ),
)

@Preview
@Composable
private fun AccountDetailActivityPreview() = AccountDetailPreview(
    daily.copy(segment = DetailSegment.ACTIVITY, activity = ActivityUiState.Shown(activityRows, ACTIVITY_COVERAGE)),
)

/** A swap whose step 2 waits: its card above the Activity, the same in both views. */
@Preview
@Composable
private fun AccountDetailWaitingSwapPreview() = AccountDetailPreview(
    daily.copy(
        segment = DetailSegment.ACTIVITY,
        activity = ActivityUiState.Shown(activityRows, ACTIVITY_COVERAGE, waiting = listOf(WAITING_SWAP)),
    ),
)

/** The same card, in the dark theme. */
@Preview
@Composable
private fun AccountDetailWaitingSwapDarkPreview() = AccountDetailPreview(
    daily.copy(
        segment = DetailSegment.ACTIVITY,
        activity = ActivityUiState.Shown(activityRows, ACTIVITY_COVERAGE, waiting = listOf(WAITING_SWAP)),
    ),
    darkTheme = true,
)

@Preview
@Composable
private fun AccountDetailActivityDarkPreview() = AccountDetailPreview(
    daily.copy(segment = DetailSegment.ACTIVITY, activity = ActivityUiState.Shown(activityRows, ACTIVITY_COVERAGE)),
    darkTheme = true,
)

@Preview
@Composable
private fun AccountDetailActivityEmptyPreview() = AccountDetailPreview(
    daily.copy(segment = DetailSegment.ACTIVITY, activity = ActivityUiState.Shown(emptyList(), ACTIVITY_COVERAGE)),
)

@Preview
@Composable
private fun AccountDetailActivityLoadingPreview() =
    AccountDetailPreview(daily.copy(segment = DetailSegment.ACTIVITY, activity = ActivityUiState.Loading))

@Preview
@Composable
private fun AccountDetailActivityUnavailablePreview() = AccountDetailPreview(
    daily.copy(
        segment = DetailSegment.ACTIVITY,
        activity = ActivityUiState.Unavailable(
            "The history of your transactions cannot be read right now. Serotine keeps trying.",
        ),
    ),
)

@Preview
@Composable
private fun AccountDetailActivityTokensPreview() = AccountDetailPreview(
    daily.copy(
        segment = DetailSegment.ACTIVITY,
        activity = ActivityUiState.Shown(tokenActivityRows, TOKENS_ACTIVITY_COVERAGE),
    ),
)

@Preview
@Composable
private fun AccountDetailSafeActivityPreview() = AccountDetailPreview(
    treasury.copy(
        segment = DetailSegment.ACTIVITY,
        activity = ActivityUiState.Shown(safeActivityRows, SAFE_ACTIVITY_COVERAGE),
    ),
)

@Preview
@Composable
private fun AccountDetailSafeActivityDarkPreview() = AccountDetailPreview(
    treasury.copy(
        segment = DetailSegment.ACTIVITY,
        activity = ActivityUiState.Shown(safeActivityRows, SAFE_ACTIVITY_COVERAGE),
    ),
    darkTheme = true,
)

/** On a phone narrower than an iPhone mini: Send, Swap and Receive leave out their icons. */
@Preview
@Composable
private fun AccountDetailNarrowPreview() = Box(Modifier.width(320.dp)) { AccountDetailPreview(daily) }

@Preview
@Composable
private fun AccountDetailLoadingPreview() = AccountDetailPreview(AccountDetailUiState.Loading)

@Preview
@Composable
private fun AccountDetailListUnavailablePreview() = AccountDetailPreview(
    AccountDetailUiState.Unavailable("Your account list cannot be read right now. Serotine keeps trying."),
)

@Preview
@Composable
private fun AccountDetailEnsNameMovedPreview() = AccountDetailPreview(
    daily.copy(
        label = "0xfB69…d359",
        address = SAVINGS,
        control = Control.WATCH_ONLY,
        nameNote = "ENS name “savings-example.eth” no longer points to this address",
    ),
)

/**
 * Docs/networks.md's N3: an account on Ethereum, real prices, no testnet note; COW, which no feed
 * prices, at its pool's average, labelled, and counted in the total, which says so (#205).
 */
@Preview
@Composable
private fun AccountDetailEthereumPreview() = AccountDetailPreview(
    daily.copy(
        label = "Main",
        network = NetworkLabel(1, "Ethereum", testnet = false),
        total = "€9,722.30",
        totalNote = "Includes Uniswap averages",
        assets = listOf(
            ether.copy(amount = "3.2041 ETH", verifiedAt = "block 23,618,402", value = "€9,612.30"),
            AssetRow(
                "COW",
                "CoW Protocol Token",
                "1,000 COW",
                "cow",
                verified = true,
                verifiedAt = "block 23,618,401",
                value = "≈ €110.00 · $POOL_AVERAGE",
                contract = "0xdef1ca1fb7fbcdc777520aa7f396b4e015f497ab",
            ),
        ),
        pricesNote = "Prices: Chainlink feeds and Uniswap 30-min averages, read through the light client",
    ),
)

/** N19: an account on a network that is off; Send and Swap greyed out, Receive kept. */
@Preview
@Composable
private fun AccountDetailNetworkOffPreview() = AccountDetailPreview(
    daily.copy(
        label = "Test",
        total = "Off",
        totalNote = null,
        assets = listOf(
            ether.copy(
                amount = "0.84 ETH",
                verified = false,
                verifiedAt = null,
                value = null,
                lastKnown = "Last verified Oct 21, 2026, 6:02 PM · Sepolia is off",
            ),
        ),
        networkOn = false,
    ),
    lightClient = LightClientItem("Turned off in this wallet", null, false, LightClientHealth.NOT_RUNNING),
)

/** Every preview above, which AccountDetailScreenPreviewsTest renders: a new preview goes in here too. */
internal val accountDetailScreenPreviews: List<@Composable () -> Unit> = listOf(
    { AccountDetailAssetsPreview() },
    { AccountDetailAssetsDarkPreview() },
    { AccountDetailCheckingPreview() },
    { AccountDetailHeadTooOldPreview() },
    { AccountDetailByNameWithImpostorPreview() },
    { AccountDetailSafeAssetsPreview() },
    { AccountDetailSafeOwnersPreview() },
    { AccountDetailSafeQueuePreview() },
    { AccountDetailSafeQueueDarkPreview() },
    { AccountDetailSafeQueueEmptyPreview() },
    { AccountDetailSafeQueueUnverifiedPreview() },
    { AccountDetailSafeQueueUnreadablePreview() },
    { AccountDetailSafeReadingPreview() },
    { AccountDetailNotASafePreview() },
    { AccountDetailLoadingPreview() },
    { AccountDetailListUnavailablePreview() },
    { AccountDetailEnsNameMovedPreview() },
    { AccountDetailActivityPreview() },
    { AccountDetailActivityDarkPreview() },
    { AccountDetailWaitingSwapPreview() },
    { AccountDetailWaitingSwapDarkPreview() },
    { AccountDetailActivityEmptyPreview() },
    { AccountDetailActivityLoadingPreview() },
    { AccountDetailActivityUnavailablePreview() },
    { AccountDetailActivityTokensPreview() },
    { AccountDetailSafeActivityPreview() },
    { AccountDetailSafeActivityDarkPreview() },
    { AccountDetailNarrowPreview() },
    { AccountDetailSafeQueueReadingPreview() },
    { AccountDetailCheckingDarkPreview() },
    { AccountDetailEthereumPreview() },
    { AccountDetailNetworkOffPreview() },
    { AccountDetailSafeOwnersNetworkOffPreview() },
    { AccountDetailAssetsHiddenPreview() },
    { AccountDetailAssetsHiddenDarkPreview() },
)
