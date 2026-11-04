package eth.serotine.ui.watch

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.LightClientHealth
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Watch address in simple mode in Android Studio's preview pane, one preview per state it draws
// differently from Detailed view. Built from display-ready values, never from the ViewModel.
// SimpleWatchScreenPreviewsTest renders each one in simpleWatchScreenPreviews, at the end. The
// addresses are EIP-55's own examples, and the names and blocks are made up.

private const val SAVINGS = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val TREASURY = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"

private val account = WatchUiState(
    address = SAVINGS,
    name = "Savings",
    detection = Detection.Account(SAVINGS, "Checked at block 8,412,307", null, "Checked on chain"),
    cardNetwork = NetworkLabel(1, "Ethereum", testnet = false),
)

@Composable
private fun SimpleWatchPreview(state: WatchUiState, darkTheme: Boolean = false, syncLine: LightClientItem? = null) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleWatchScreen(
            state = state,
            onAddressChange = {},
            onNameChange = {},
            onAdd = {},
            onBack = {},
            syncLine = syncLine,
        )
    }
}

/** Ethereum's light client catching up, as simple mode's sync line says it. */
private val catchingUp = LightClientItem(
    "Verifying sync committees · 2 of 6 periods",
    2f / 6,
    true,
    LightClientHealth.NOT_VERIFIED,
    simpleText = "Syncing Ethereum · 2 of 6 periods",
)

private val syncing = WatchUiState(
    address = "savings-example.eth",
    addressError = "The light client is still syncing, so the name can't be resolved yet. It is looked up again " +
        "once it has caught up.",
    canRetryLookup = true,
    retryLookupWaits = "The light client is still syncing. The name is looked up again by itself every few " +
        "seconds until it has caught up.",
)

/** An account: checked on chain, no block, no strip. */
@Preview
@Composable
private fun SimpleWatchAccountPreview() = SimpleWatchPreview(account)

@Preview
@Composable
private fun SimpleWatchAccountDarkPreview() = SimpleWatchPreview(account, darkTheme = true)

/** A Safe, its owners and modules as in Detailed view, checked on chain. */
@Preview
@Composable
private fun SimpleWatchSafePreview() = SimpleWatchPreview(
    WatchUiState(
        address = TREASURY,
        name = "Treasury",
        detection = Detection.Safe(
            address = TREASURY,
            title = "Safe 1.4.1",
            signers = "2 of 3 owners",
            owners = listOf("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", SAVINGS),
            modules = emptyList(),
            guard = null,
            checkedAt = "Checked at block 8,412,307",
            control = Control.CO_SIGNER,
            threshold = "2/3",
            simpleCheckedAt = "Checked on chain",
        ),
    ),
)

/** An ENS name resolved: what it resolves to, checked on chain, with no block. */
@Preview
@Composable
private fun SimpleWatchNameResolvedPreview() = SimpleWatchPreview(
    account.copy(
        address = "savings-example.eth",
        name = "",
        addressNote = "Resolves to $SAVINGS, verified at finalized block 8,412,240",
        simpleAddressNote = "Resolves to $SAVINGS, checked on chain",
        nameDefault = "savings-example.eth",
    ),
)

/** A name on Sepolia's registry alone: each registry's answer named, checked on chain, Sepolia's card. */
@Preview
@Composable
private fun SimpleWatchNameOnOneRegistryPreview() = SimpleWatchPreview(
    account.copy(
        address = "savings-example.eth",
        name = "",
        addressNote = "On Ethereum: no address\nOn Sepolia's test registry: resolves to $SAVINGS, verified at " +
            "finalized block 8,412,240",
        simpleAddressNote = "On Ethereum: no address\nOn Sepolia's test registry: resolves to $SAVINGS, checked " +
            "on chain",
        nameDefault = "savings-example.eth",
        cardNetwork = NetworkLabel(11_155_111, "Sepolia", testnet = true),
    ),
)

/** A name found on Ethereum's registry and not verified on Sepolia's: each said with no block, Try again. */
@Preview
@Composable
private fun SimpleWatchNameUnverifiedOnOneRegistryPreview() = SimpleWatchPreview(
    account.copy(
        address = "savings-example.eth",
        name = "",
        addressNote = "On Ethereum: resolves to $SAVINGS, verified at finalized block 8,412,240\nOn Sepolia's test " +
            "registry: the name could not be resolved with verified calls this time.",
        simpleAddressNote = "On Ethereum: resolves to $SAVINGS, checked on chain\nOn Sepolia's test registry: the " +
            "name could not be resolved with verified calls this time.",
        canRetryLookup = true,
        nameDefault = "savings-example.eth",
    ),
)

/** Two networks on: each card checked on chain. */
@Preview
@Composable
private fun SimpleWatchEveryNetworkPreview() = SimpleWatchPreview(
    WatchUiState(
        address = SAVINGS,
        name = "Savings",
        networks = listOf(
            WatchNetwork(
                NetworkLabel(1, "Ethereum", testnet = false),
                Detection.Account(SAVINGS, "Checked at block 23,618,402", null, "Checked on chain"),
                offered = true,
                chosen = true,
            ),
            WatchNetwork(
                NetworkLabel(100, "Gnosis Chain", testnet = false),
                Detection.Account(SAVINGS, "Checked at block 42,907,115", null, "Checked on chain"),
                offered = true,
                chosen = true,
            ),
        ),
        question = "This address has no code. Watch it on Ethereum and Gnosis Chain?",
        offNetworks = listOf(NetworkLabel(11_155_111, "Sepolia", testnet = true)),
    ),
)

/** A name asked while Ethereum still syncs: the sync line, the reason, and Try again greyed out. */
@Preview
@Composable
private fun SimpleWatchSyncingPreview() = SimpleWatchPreview(syncing, syncLine = catchingUp)

/** The same, in the dark theme. */
@Preview
@Composable
private fun SimpleWatchSyncingDarkPreview() = SimpleWatchPreview(syncing, darkTheme = true, syncLine = catchingUp)

/** Every preview above, which SimpleWatchScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleWatchScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SimpleWatchAccountPreview() },
    { SimpleWatchAccountDarkPreview() },
    { SimpleWatchSafePreview() },
    { SimpleWatchNameResolvedPreview() },
    { SimpleWatchNameOnOneRegistryPreview() },
    { SimpleWatchNameUnverifiedOnOneRegistryPreview() },
    { SimpleWatchEveryNetworkPreview() },
    { SimpleWatchSyncingPreview() },
    { SimpleWatchSyncingDarkPreview() },
)
