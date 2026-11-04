package eth.serotine.ui.createsafe

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NetworkChip
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Create Safe in Android Studio's preview pane, one preview per state it draws differently, and each
// of its three steps in the dark theme. Built from display-ready values, as the screen tests are,
// never from the ViewModel. CreateSafeScreenPreviewsTest renders each one in createSafeScreenPreviews,
// at the end. The addresses are EIP-55's own examples; the names, the ENS name, amounts, blocks and
// the password are made up, and the Safe's address is the one cast computes for its owners in
// domain's SafeCreationTest. A preview has no window to keep out of screen capture, so the review's
// password field shows the line saying so, which a device that blocks capture does not.

private const val DAILY = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val SAVINGS = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val FRIEND = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
private const val SAFE = "0x2Ca0226086a7C24808217F7F8707827608B17Dc5"

private const val DEVICE_KEY =
    "This device's hardware key (Secure Enclave or StrongBox) as an owner comes later: Serotine does not build " +
        "its passkey signer yet."

private val daily = OwnerChoice(DAILY, "Daily", "Key on this device", selected = true)
private val savings = OwnerChoice(SAVINGS, "Savings", "Watch-only: its key is elsewhere", selected = true)
private val friend =
    OwnerChoice(FRIEND, "friend-example.eth", "Resolved from friend-example.eth at finalized block 8,412,240", true)

private val sepoliaChip = NetworkChip(NetworkLabel(11_155_111, "Sepolia", testnet = true), selected = true)

private val owners = CreateSafeUiState.Owners(
    network = "Sepolia",
    networks = listOf(sepoliaChip),
    name = "Family vault",
    accounts = listOf(daily, savings),
    typed = listOf(friend),
    input = "",
    summary = "3 owners",
    deviceKeyNote = DEVICE_KEY,
    canContinue = true,
)

private val typing = owners.copy(
    name = "",
    accounts = listOf(daily, savings.copy(selected = false)),
    typed = emptyList(),
    input = "friend-example.eth",
    summary = "1 owner",
    inputOwner = friend.copy(selected = false, note = "Resolved at finalized block 8,412,240"),
    canAdd = true,
)

private val ownerItems = listOf(
    OwnerItem(DAILY, "Daily", "Key on this device"),
    OwnerItem(SAVINGS, "Savings", "Watch-only: its key is elsewhere"),
    OwnerItem(FRIEND, "friend-example.eth", "Resolved from friend-example.eth at finalized block 8,412,240"),
)

private val rules = listOf(
    "Each transaction of the Safe needs this many of its owners' signatures.",
    "A device's hardware key is never the only owner: it has no backup.",
    "Every owner signing (2 of 2, 3 of 3, …) takes a recovery module beside it, which Serotine does not offer " +
        "yet: with one owner lost, the others could never reach the threshold again.",
)

private val threshold = CreateSafeUiState.Threshold(
    network = "Sepolia",
    owners = ownerItems,
    threshold = 2,
    ownerCount = 3,
    shape = "2 of 3 owners sign each transaction",
    explanation = "With up to 1 owner lost, the others still reach the threshold, and can replace the lost ones.",
    rules = rules,
    canDecrease = true,
    canIncrease = true,
    canContinue = true,
)

private val allOfThem = threshold.copy(
    threshold = 3,
    shape = "3 of 3 owners sign each transaction",
    explanation = null,
    canIncrease = false,
    refusal = "Not 3 of 3: with one owner lost, the others could never reach the threshold again, not even to " +
        "replace the lost one. That takes a recovery module beside it, which Serotine does not offer yet. Lower " +
        "the threshold, or add an owner.",
    canContinue = false,
)

private val review = CreateSafeUiState.Review(
    network = "Sepolia",
    name = "Family vault",
    address = SAFE,
    signers = "2/3",
    version = "Safe 1.4.1",
    prediction = "Computed here (CREATE2) from the factory, the singleton, the owners, the threshold and a salt. " +
        "The factory's verified run at block 8,412,307 returns this address, which held no code at block 8,412,307.",
    owners = ownerItems,
    shape = "2 of 3 owners sign each transaction",
    payers = listOf(AccountChoice("Daily", DAILY)),
    payer = DAILY,
    fee = FeeLevel.NORMAL,
    unlock = UnlockStep(),
    quote = DeploymentQuoteItem(
        maxFee = "0.006534 ETH",
        feeDetail = "Normal fee: 297,000 gas × at most 22 gwei, from the verified fee history up to block " +
            "8,412,306. Only what the block charges is paid.",
        balance = BalanceChange(
            "ETH balance of Daily",
            "1.25 ETH",
            "at least 1.243466 ETH",
            "verified at block 8,412,307",
        ),
        simulation = "Run as a verified call from this account at block 8,412,307, the factory deploys the Safe at " +
            "this address and sets it up with these owners and this threshold.",
    ),
)

@Composable
private fun CreateSafePreview(
    state: CreateSafeUiState,
    darkTheme: Boolean = false,
    lightClient: LightClientItem = verifiedStripSample,
) {
    SerotineTheme(darkTheme = darkTheme) {
        CreateSafeScreen(state, lightClient, CreateSafeActions(), onBack = {})
    }
}

@Preview
@Composable
private fun CreateSafeOwnersPreview() = CreateSafePreview(owners)

@Preview
@Composable
private fun CreateSafeOwnersDarkPreview() = CreateSafePreview(owners, darkTheme = true)

@Preview
@Composable
private fun CreateSafeTypingAnOwnerPreview() = CreateSafePreview(typing)

@Preview
@Composable
private fun CreateSafeOwnersRefusedPreview() = CreateSafePreview(
    typing.copy(
        name = "A name longer than the forty characters allowed",
        typed = listOf(OwnerChoice("0x0000000000000000000000000000000000000001", null, "Typed", selected = true)),
        input = "",
        summary = "2 owners",
        inputOwner = null,
        canAdd = false,
        nameError = "At most 40 characters.",
        error = "0x0000000000000000000000000000000000000001 cannot own a Safe: the Safe reserves it for its own lists.",
        canContinue = false,
    ),
)

@Preview
@Composable
private fun CreateSafeThresholdPreview() = CreateSafePreview(threshold)

@Preview
@Composable
private fun CreateSafeThresholdDarkPreview() = CreateSafePreview(threshold, darkTheme = true)

@Preview
@Composable
private fun CreateSafeThresholdRefusedPreview() = CreateSafePreview(allOfThem)

@Preview
@Composable
private fun CreateSafeReviewPreview() = CreateSafePreview(review)

@Preview
@Composable
private fun CreateSafeReviewDarkPreview() = CreateSafePreview(review, darkTheme = true)

@Preview
@Composable
private fun CreateSafeReviewTypedPreview() =
    CreateSafePreview(review.copy(unlock = UnlockStep(password = WalletPassword("made-up preview password"))))

@Preview
@Composable
private fun CreateSafeReviewQuotingPreview() = CreateSafePreview(
    review.copy(
        quote = null,
        quoting = true,
        prediction = "Computed here (CREATE2) from the factory, the singleton, the owners, the threshold and a " +
            "salt. Checking it against the factory's verified run…",
    ),
)

@Preview
@Composable
private fun CreateSafeReviewRefusedPreview() = CreateSafePreview(
    review.copy(
        quote = null,
        prediction = "Computed here (CREATE2) from the factory, the singleton, the owners, the threshold and a " +
            "salt. The factory's verified run has not confirmed it, so nothing can be signed.",
        error = "Not enough ETH in this account for the most the fee can be. Transactions not mined yet count too.",
    ),
)

@Preview
@Composable
private fun CreateSafeReviewBiometricsPreview() = CreateSafePreview(review.copy(unlock = UnlockStep(biometrics = true)))

@Preview
@Composable
private fun CreateSafeNotSentPreview() = CreateSafePreview(
    review.copy(
        notSent = NotSentItem(
            "Signed, but not sent: no peer took it right now. Try again sends the same signed deployment, not a " +
                "new one.",
            canRetry = true,
        ),
    ),
)

@Preview
@Composable
private fun CreateSafeReviewSigningPreview() = CreateSafePreview(review.copy(signing = true))

@Preview
@Composable
private fun CreateSafeSentPreview() = CreateSafePreview(CreateSafeUiState.Sent("Sepolia", "0x" + "5e".repeat(32)))

@Preview
@Composable
private fun CreateSafeCannotCreatePreview() = CreateSafePreview(
    CreateSafeUiState.CannotCreate(
        "Sepolia",
        "Creating a Safe on Sepolia needs an account whose key is on this device there, to pay for its " +
            "deployment. Import or create an account on it first, and fund it.",
        listOf(sepoliaChip),
    ),
)

/**
 * Docs/networks.md's N12: the network first, Ethereum chosen; no account to pay on Gnosis Chain, and
 * Sepolia off, both greyed out. Ethereum and Gnosis Chain come with parts 4 and 5.
 */
@Preview
@Composable
private fun CreateSafeNetworksPreview() = CreateSafePreview(
    owners.copy(
        network = "Ethereum",
        networks = listOf(
            NetworkChip(NetworkLabel(1, "Ethereum", testnet = false), selected = true),
            NetworkChip(
                NetworkLabel(100, "Gnosis Chain", testnet = false),
                selected = false,
                reason = "Creating a Safe on Gnosis Chain needs an account whose key is on this device there.",
            ),
            sepoliaChip.copy(selected = false, reason = "Sepolia is off in this wallet.", off = true),
        ),
    ),
)

@Preview
@Composable
private fun CreateSafeLoadingPreview() = CreateSafePreview(CreateSafeUiState.Loading("Sepolia"))

/** Every preview above, which CreateSafeScreenPreviewsTest renders: a new preview goes in here too. */
internal val createSafeScreenPreviews: List<@Composable () -> Unit> = listOf(
    { CreateSafeOwnersPreview() },
    { CreateSafeOwnersDarkPreview() },
    { CreateSafeTypingAnOwnerPreview() },
    { CreateSafeOwnersRefusedPreview() },
    { CreateSafeThresholdPreview() },
    { CreateSafeThresholdDarkPreview() },
    { CreateSafeThresholdRefusedPreview() },
    { CreateSafeReviewPreview() },
    { CreateSafeReviewDarkPreview() },
    { CreateSafeReviewTypedPreview() },
    { CreateSafeReviewQuotingPreview() },
    { CreateSafeReviewRefusedPreview() },
    { CreateSafeReviewBiometricsPreview() },
    { CreateSafeNotSentPreview() },
    { CreateSafeReviewSigningPreview() },
    { CreateSafeSentPreview() },
    { CreateSafeCannotCreatePreview() },
    { CreateSafeLoadingPreview() },
    { CreateSafeNetworksPreview() },
)
