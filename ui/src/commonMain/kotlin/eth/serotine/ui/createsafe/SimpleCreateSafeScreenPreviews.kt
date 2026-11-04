package eth.serotine.ui.createsafe

import androidx.compose.runtime.Composable
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.NetworkChip
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Create Safe in simple mode in Android Studio's preview pane, one preview per state it draws
// differently from Detailed view. Built from display-ready values, never from the ViewModel.
// SimpleCreateSafeScreenPreviewsTest renders each one in simpleCreateSafeScreenPreviews, at the end.
// The addresses are EIP-55's own examples; the names, the ENS name, amounts and blocks are made up.

private const val DAILY = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val FRIEND = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
private const val SAFE = "0x2Ca0226086a7C24808217F7F8707827608B17Dc5"

private const val RESOLVED = "Resolved from friend-example.eth at finalized block 8,412,240"
private const val SIMPLE_RESOLVED = "Resolved from friend-example.eth, checked on chain"

private val ownerItems = listOf(
    OwnerItem(DAILY, "Daily", "Key on this device"),
    OwnerItem(FRIEND, "friend-example.eth", RESOLVED, simpleNote = SIMPLE_RESOLVED),
)

/** Step 1: an owner resolved from a name says it was checked on chain, with no block. */
@Preview
@Composable
private fun SimpleCreateSafeOwnersPreview() = SimpleCreateSafePreview(
    CreateSafeUiState.Owners(
        network = "Sepolia",
        networks = listOf(NetworkChip(NetworkLabel(11_155_111, "Sepolia", testnet = true), selected = true)),
        name = "Family vault",
        accounts = listOf(OwnerChoice(DAILY, "Daily", "Key on this device", selected = true)),
        typed = listOf(OwnerChoice(FRIEND, "friend-example.eth", RESOLVED, true, simpleNote = SIMPLE_RESOLVED)),
        input = "",
        summary = "2 owners",
        deviceKeyNote = "This device's hardware key as an owner comes later.",
        canContinue = true,
    ),
)

private val review = CreateSafeUiState.Review(
    network = "Sepolia",
    name = "Family vault",
    address = SAFE,
    signers = "2/2",
    version = "Safe 1.4.1",
    prediction = "Computed here (CREATE2) from the factory, the singleton, the owners, the threshold and a salt. " +
        "The factory's verified run at block 8,412,307 returns this address, which held no code at block 8,412,307.",
    simplePrediction = "Computed here (CREATE2) from the factory, the singleton, the owners, the threshold and a " +
        "salt. The factory's run, checked on chain, returns this address, which held no code when it was checked.",
    owners = ownerItems,
    shape = "2 of 2 owners sign each transaction",
    payers = listOf(AccountChoice("Daily", DAILY)),
    payer = DAILY,
    fee = FeeLevel.NORMAL,
    unlock = UnlockStep(),
    quote = DeploymentQuoteItem(
        maxFee = "0.006534 ETH",
        feeDetail = "Normal fee: 297,000 gas × at most 22 gwei, from the verified fee history up to block 8,412,306.",
        balance = BalanceChange(
            "ETH balance of Daily",
            "1.25 ETH",
            "at least 1.243466 ETH",
            "verified at block 8,412,307",
        ),
        simulation = "Run as a verified call from this account at block 8,412,307, the factory deploys the Safe at " +
            "this address and sets it up with these owners and this threshold.",
        simpleSimulation = "Checked on chain: the factory should deploy the Safe at this address and set it up with " +
            "these owners and this threshold.",
    ),
)

/** Step 3: the review with no block; the blocks, the fee's make-up and the run behind Details. */
@Preview
@Composable
private fun SimpleCreateSafeReviewPreview() = SimpleCreateSafePreview(review)

@Preview
@Composable
private fun SimpleCreateSafeReviewDarkPreview() = SimpleCreateSafePreview(review, darkTheme = true)

@Composable
private fun SimpleCreateSafePreview(state: CreateSafeUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleCreateSafeScreen(state, CreateSafeActions(), onBack = {})
    }
}

/** Every preview above, which SimpleCreateSafeScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleCreateSafeScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SimpleCreateSafeOwnersPreview() },
    { SimpleCreateSafeReviewPreview() },
    { SimpleCreateSafeReviewDarkPreview() },
)
