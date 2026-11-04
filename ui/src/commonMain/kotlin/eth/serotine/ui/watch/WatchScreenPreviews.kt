package eth.serotine.ui.watch

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Watch address screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. WatchScreenPreviewsTest renders each one in watchScreenPreviews, at the end. The
// addresses are EIP-55's own examples, and the names and blocks are made up.

private const val SAVINGS = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val TREASURY = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"

private const val CHECKED = "Checked at block 8,412,307"

private val account = WatchUiState(
    address = SAVINGS,
    name = "Savings",
    detection = Detection.Account(SAVINGS, CHECKED, delegate = null),
    cardNetwork = NetworkLabel(1, "Ethereum", testnet = false),
)

@Composable
private fun WatchPreview(
    state: WatchUiState,
    darkTheme: Boolean = false,
    lightClient: LightClientItem = verifiedStripSample,
) {
    SerotineTheme(darkTheme = darkTheme) {
        WatchScreen(
            state = state,
            lightClient = lightClient,
            onAddressChange = {},
            onNameChange = {},
            onAdd = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun WatchEmptyPreview() = WatchPreview(WatchUiState())

@Preview
@Composable
private fun WatchCheckingPreview() = WatchPreview(account.copy(detection = Detection.Checking))

@Preview
@Composable
private fun WatchAccountPreview() = WatchPreview(account)

@Preview
@Composable
private fun WatchAccountDarkPreview() = WatchPreview(account, darkTheme = true)

@Preview
@Composable
private fun WatchDelegatedAccountPreview() = WatchPreview(
    account.copy(
        detection = Detection.Account(SAVINGS, CHECKED, delegate = "0x8617E340B3D01FA5F11F306F4090FD50E238070D"),
    ),
)

@Preview
@Composable
private fun WatchSafePreview() = WatchPreview(
    WatchUiState(
        address = TREASURY,
        name = "Treasury",
        detection = Detection.Safe(
            address = TREASURY,
            title = "Safe 1.4.1",
            signers = "2 of 3 owners",
            owners = listOf(
                "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
                SAVINGS,
                "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
            ),
            modules = listOf("0x52908400098527886E0F7030069857D2E4169EE7"),
            guard = "0xde709f2102306220921060314715629080e2fb77",
            checkedAt = CHECKED,
            control = Control.CO_SIGNER,
            moduleGuard = "0x27b1fdb04752bbc536007a920d24acb045561c26",
            threshold = "2/3",
        ),
    ),
)

@Preview
@Composable
private fun WatchNotASafePreview() = WatchPreview(
    WatchUiState(
        address = TREASURY,
        detection = Detection.Contract(
            "This address holds a contract that is not a recognised Safe. Serotine watches accounts and Safes.",
        ),
    ),
)

@Preview
@Composable
private fun WatchInWalletPreview() = WatchPreview(account.copy(name = "", detection = Detection.InWallet("Savings")))

@Preview
@Composable
private fun WatchUnavailablePreview() = WatchPreview(
    account.copy(
        detection = Detection.Unavailable(
            "The light client is not synced yet. The address is checked as soon as it is.",
        ),
    ),
)

@Preview
@Composable
private fun WatchInvalidAddressPreview() = WatchPreview(
    WatchUiState(
        address = "0xfb6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        addressError = "The capitals do not match this address's checksum: check it for a typo.",
    ),
)

// Gnosis Chain alone on: it resolves no names, so the field takes an address and refuses a name.
@Preview
@Composable
private fun WatchGnosisNoNamesPreview() = WatchPreview(
    WatchUiState(
        address = "friend-example.eth",
        addressError = "Names are not resolved on Gnosis Chain yet: enter the address.",
        addressLabel = "Address",
        addressHint = "0x and 40 hexadecimal digits: names are not resolved on Gnosis Chain yet",
    ),
)

@Preview
@Composable
private fun WatchNameTooLongPreview() = WatchPreview(
    account.copy(
        name = "Savings for the long winter, kept apart from all the rest",
        nameError = "Use at most 40 characters",
    ),
)

@Preview
@Composable
private fun WatchAddingPreview() = WatchPreview(account.copy(adding = true))

@Preview
@Composable
private fun WatchNotSavedPreview() =
    WatchPreview(account.copy(error = "The account could not be saved. Nothing was added."))

@Preview
@Composable
private fun WatchResolvingNamePreview() =
    WatchPreview(
        WatchUiState(address = "savings-example.eth", addressNote = "Resolving the name with verified calls…"),
    )

/** A name on Sepolia's registry alone: each registry's answer named, the card on Sepolia alone. */
@Preview
@Composable
private fun WatchNameOnOneRegistryPreview() = WatchPreview(
    account.copy(
        address = "savings-example.eth",
        addressNote = "On Ethereum: no address\nOn Sepolia's test registry: resolves to $SAVINGS, verified at " +
            "finalized block 8,412,240",
        cardNetwork = NetworkLabel(11_155_111, "Sepolia", testnet = true),
    ),
)

/** A name found on Ethereum's registry and not verified on Sepolia's: each said, Try again for Sepolia's. */
@Preview
@Composable
private fun WatchNameUnverifiedOnOneRegistryPreview() = WatchPreview(
    account.copy(
        address = "savings-example.eth",
        name = "",
        addressNote = "On Ethereum: resolves to $SAVINGS, verified at finalized block 8,412,240\nOn Sepolia's test " +
            "registry: the name could not be resolved with verified calls this time.",
        canRetryLookup = true,
        nameDefault = "savings-example.eth",
    ),
)

@Preview
@Composable
private fun WatchNameResolvedPreview() = WatchPreview(
    account.copy(
        address = "savings-example.eth",
        addressNote = "Resolves to $SAVINGS, verified at finalized block 8,412,240",
    ),
)

@Preview
@Composable
private fun WatchNameMovedPreview() = WatchPreview(
    account.copy(
        address = "savings-example.eth",
        addressNote = "Resolves to $TREASURY, verified at finalized block 8,412,288",
        detection = Detection.Account(TREASURY, "Checked at block 8,412,351", delegate = null),
        error = "“savings-example.eth” no longer resolves to the address that was shown: check the address it " +
            "has now. Nothing was added.",
    ),
)

@Preview
@Composable
private fun WatchNameUnavailablePreview() = WatchPreview(
    WatchUiState(
        address = "savings-example.eth",
        addressError = "The name could not be resolved with verified calls this time.",
        canRetryLookup = true,
    ),
)

@Preview
@Composable
private fun WatchNameResolvedNoNamePreview() = WatchPreview(
    account.copy(
        address = "savings-example.eth",
        name = "",
        addressNote = "Resolves to $SAVINGS, verified at finalized block 8,412,240",
        nameDefault = "savings-example.eth",
    ),
)

private val ethereum = NetworkLabel(1, "Ethereum", testnet = false)
private val gnosis = NetworkLabel(100, "Gnosis Chain", testnet = false)
private val sepolia = NetworkLabel(11_155_111, "Sepolia", testnet = true)

/** Docs/networks.md's N10: an address with no code on both networks that are on; Sepolia off. */
private val everyNetwork = WatchUiState(
    address = SAVINGS,
    name = "Savings",
    networks = listOf(
        WatchNetwork(ethereum, Detection.Account(SAVINGS, "Checked at block 23,618,402", null), true, true),
        WatchNetwork(gnosis, Detection.Account(SAVINGS, "Checked at block 42,907,115", null), true, true),
    ),
    question = "This address has no code. Watch it on Ethereum and Gnosis Chain?",
    offNetworks = listOf(sepolia),
)

@Preview
@Composable
private fun WatchEveryNetworkPreview() = WatchPreview(everyNetwork)

/** N10 after Choose networks: the rows as check boxes. */
@Preview
@Composable
private fun WatchChooseNetworksPreview() = WatchPreview(
    everyNetwork.copy(
        choosing = true,
        networks = everyNetwork.networks.mapIndexed { i, row -> if (i == 1) row.copy(chosen = false) else row },
    ),
)

/** N11: a Safe on Ethereum, nothing at the address on Gnosis Chain: offered on Ethereum only. */
@Preview
@Composable
private fun WatchSafeOnOneNetworkPreview() = WatchPreview(
    WatchUiState(
        address = TREASURY,
        name = "Treasury",
        networks = listOf(
            WatchNetwork(
                ethereum,
                Detection.Safe(
                    address = TREASURY,
                    title = "Safe 1.4.1",
                    signers = "2 of 3 owners",
                    owners = listOf("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", SAVINGS),
                    modules = emptyList(),
                    guard = null,
                    checkedAt = "Checked at block 23,618,402",
                    control = Control.CO_SIGNER,
                    threshold = "2/3",
                ),
                offered = true,
                chosen = true,
            ),
            WatchNetwork(gnosis, Detection.Account(TREASURY, "Checked at block 42,907,115", null), false),
        ),
        offNetworks = listOf(sepolia),
    ),
)

/** No network on: nothing can be checked. */
@Preview
@Composable
private fun WatchNoNetworkPreview() = WatchPreview(
    WatchUiState(
        address = SAVINGS,
        noNetwork = "No network is on in this wallet, so the address cannot be checked. Turn one on in Settings → " +
            "Networks.",
        offNetworks = listOf(ethereum, gnosis, sepolia),
    ),
)

/** Every preview above, which WatchScreenPreviewsTest renders: a new preview goes in here too. */
internal val watchScreenPreviews: List<@Composable () -> Unit> = listOf(
    { WatchEmptyPreview() },
    { WatchCheckingPreview() },
    { WatchAccountPreview() },
    { WatchAccountDarkPreview() },
    { WatchDelegatedAccountPreview() },
    { WatchSafePreview() },
    { WatchNotASafePreview() },
    { WatchInWalletPreview() },
    { WatchUnavailablePreview() },
    { WatchInvalidAddressPreview() },
    { WatchNameTooLongPreview() },
    { WatchGnosisNoNamesPreview() },
    { WatchAddingPreview() },
    { WatchNotSavedPreview() },
    { WatchResolvingNamePreview() },
    { WatchNameResolvedPreview() },
    { WatchNameOnOneRegistryPreview() },
    { WatchNameUnverifiedOnOneRegistryPreview() },
    { WatchNameMovedPreview() },
    { WatchNameUnavailablePreview() },
    { WatchNameResolvedNoNamePreview() },
    { WatchEveryNetworkPreview() },
    { WatchChooseNetworksPreview() },
    { WatchSafeOnOneNetworkPreview() },
    { WatchNoNetworkPreview() },
)
