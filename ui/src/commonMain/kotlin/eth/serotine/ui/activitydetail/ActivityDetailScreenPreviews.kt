package eth.serotine.ui.activitydetail

import androidx.compose.runtime.Composable
import eth.serotine.ui.activity.ActivityState
import eth.serotine.ui.components.AMOUNT_MASK
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Activity detail in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. ActivityDetailScreenPreviewsTest renders each one in activityDetailScreenPreviews, at
// the end. The addresses are EIP-55's own examples and Sepolia's USDC, and the hashes made up.

private const val ALICE = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"
private const val BOB = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val TREASURY = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val USDC = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
private const val UNLISTED = "0x8617E340B3D01FA5F11F306F4090FD50E238070D"
private const val ROUTER = "0x3bFA4769FB09eefC5a80d6E87c3B9C650f7Ae48E"
private const val STRANGER = "0x52908400098527886E0F7030069857D2E4169EE7"

private val sent = ActivityDetailUiState.Shown(
    title = "Alice",
    headline = "Sent 40 USDC",
    about = BOB,
    status = "Finalized in block 9,412,118",
    state = ActivityState.DONE,
    fields = listOf(
        DetailField("From", ALICE, DetailKind.ADDRESS, "Alice"),
        DetailField("To", BOB, DetailKind.ADDRESS, "Bob"),
        DetailField("Amount", "40 USDC"),
        DetailField("Token contract", USDC, DetailKind.ADDRESS, "USDC"),
        DetailField("Transaction hash", "0x" + "1".repeat(64), DetailKind.HASH),
        DetailField("Nonce", "7"),
        DetailField("Sent", "Oct 19, 2026, 2:02 PM"),
        DetailField("Block", "9,412,118", DetailKind.BLOCK),
    ),
    opensStatus = "0x" + "1".repeat(64),
)

private val waiting = sent.copy(
    headline = "Sent 0.25 ETH",
    status = "No peer confirmed its broadcast",
    state = ActivityState.UNCONFIRMED,
    fields = listOf(
        DetailField("From", ALICE, DetailKind.ADDRESS, "Alice"),
        DetailField("To", TREASURY, DetailKind.ADDRESS, "Treasury"),
        DetailField("Amount", "0.25 ETH"),
        DetailField("Transaction hash", "0x" + "2".repeat(64), DetailKind.HASH),
        DetailField("Nonce", "8"),
        DetailField("Sent", "Oct 19, 2026, 4:10 PM"),
    ),
    opensStatus = "0x" + "2".repeat(64),
)

private val unlisted = ActivityDetailUiState.Shown(
    title = "Alice",
    headline = "An unlisted token logged a transfer to it",
    about = UNLISTED,
    status = "The claim of a contract the wallet does not list",
    state = ActivityState.UNKNOWN,
    fields = listOf(
        DetailField("Token contract", UNLISTED, DetailKind.ADDRESS),
        DetailField("Transaction hash", "0x" + "e".repeat(64), DetailKind.HASH),
        DetailField("Block", "9,402,000", DetailKind.BLOCK),
    ),
    note = "A contract the wallet does not show can log any transfer it likes, between any addresses, so only " +
        "the contract is named here: an address it names could be a look-alike planted for you to copy. Add it " +
        "as a token to see what moved: its symbol, name and decimals are read from the contract first.",
    addToken = UNLISTED,
)

private val unfamiliarSender = ActivityDetailUiState.Shown(
    title = "Alice",
    headline = "Received 0.01 USDC",
    about = STRANGER,
    status = "From an address you have not sent to: compare every character before you copy it",
    state = ActivityState.CAUTION,
    fields = listOf(
        DetailField("From", STRANGER, DetailKind.ADDRESS),
        DetailField("To", ALICE, DetailKind.ADDRESS, "Alice"),
        DetailField("Amount", "0.01 USDC"),
        DetailField("Token contract", USDC, DetailKind.ADDRESS, "USDC"),
        DetailField("Transaction hash", "0x" + "f".repeat(64), DetailKind.HASH),
        // When its block was produced, as the engine reported it from the block's verified header.
        DetailField("Time", "Oct 19, 2026, 6:48 PM"),
        DetailField("Block", "9,418,200", DetailKind.BLOCK),
    ),
    note = "You have not sent to the sender's address from this device, and it is not one of your accounts. " +
        "Anyone can send you a little from an address made to look like one you use, so that you copy it from " +
        "your history later: compare every character before you send anything to it.",
)

/** The network's own log of an ETH transfer (EIP-7708): both sides and the ETH, no contract. */
private val ethLogged = ActivityDetailUiState.Shown(
    title = "Treasury",
    headline = "Sent 0.5 ETH",
    about = ROUTER,
    status = "On chain, from the network's own verified log of ETH transfers",
    state = ActivityState.DONE,
    fields = listOf(
        DetailField("From", TREASURY, DetailKind.ADDRESS, "Treasury"),
        DetailField("To", ROUTER, DetailKind.ADDRESS),
        DetailField("Amount", "0.5 ETH"),
        DetailField("Transaction hash", "0x" + "2f".repeat(32), DetailKind.HASH),
        DetailField("Time", "Oct 19, 2026, 5:12 PM"),
        DetailField("Block", "9,417,600", DetailKind.BLOCK),
    ),
    note = "A plain ETH transfer, which the network itself logs since its upgrade with EIP-7708: no token is " +
        "involved, whatever explorers list it under.",
)

private val safeEvent = ActivityDetailUiState.Shown(
    title = "Treasury",
    headline = "Module enabled",
    about = BOB,
    status = "A module can move this Safe's funds without its owners",
    state = ActivityState.WARNING,
    fields = listOf(
        DetailField("Module", BOB, DetailKind.ADDRESS, "Bob"),
        DetailField("Safe", TREASURY, DetailKind.ADDRESS, "Treasury"),
        DetailField("Transaction hash", "0x" + "a".repeat(64), DetailKind.HASH),
        DetailField("Time", "Oct 19, 2026, 5:42 PM"),
        DetailField("Block", "9,415,302", DetailKind.BLOCK),
    ),
)

@Composable
private fun ActivityDetailPreview(state: ActivityDetailUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        ActivityDetailScreen(
            state = state,
            lightClient = verifiedStripSample,
            onBack = {},
            onCopy = {},
            onStatus = {},
            onAddToken = {},
        )
    }
}

@Preview
@Composable
private fun ActivityDetailSentPreview() = ActivityDetailPreview(sent)

@Preview
@Composable
private fun ActivityDetailSentDarkPreview() = ActivityDetailPreview(sent, darkTheme = true)

/** Amounts hidden: the eye beside Back closed, what it moved masked in its headline and its Amount. */
private val sentHidden = sent.copy(
    headline = "Sent $AMOUNT_MASK USDC",
    fields = sent.fields.map { if (it.label == "Amount") it.copy(value = "$AMOUNT_MASK USDC") else it },
    amountsHidden = true,
)

@Preview
@Composable
private fun ActivityDetailHiddenPreview() = ActivityDetailPreview(sentHidden)

@Preview
@Composable
private fun ActivityDetailHiddenDarkPreview() = ActivityDetailPreview(sentHidden, darkTheme = true)

@Preview
@Composable
private fun ActivityDetailUnconfirmedPreview() = ActivityDetailPreview(waiting)

@Preview
@Composable
private fun ActivityDetailUnlistedTokenPreview() = ActivityDetailPreview(unlisted)

@Preview
@Composable
private fun ActivityDetailUnfamiliarSenderPreview() = ActivityDetailPreview(unfamiliarSender)

@Preview
@Composable
private fun ActivityDetailEthPreview() = ActivityDetailPreview(ethLogged)

@Preview
@Composable
private fun ActivityDetailSafeEventPreview() = ActivityDetailPreview(safeEvent)

@Preview
@Composable
private fun ActivityDetailLoadingPreview() = ActivityDetailPreview(ActivityDetailUiState.Loading("Alice"))

@Preview
@Composable
private fun ActivityDetailUnavailablePreview() = ActivityDetailPreview(
    ActivityDetailUiState.Unavailable(
        "Alice",
        "The account's history on chain cannot be read right now. Serotine keeps trying.",
    ),
)

/** Every preview above, which ActivityDetailScreenPreviewsTest renders: a new preview goes in here too. */
internal val activityDetailScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("sent") { ActivityDetailSentPreview() },
    ScreenPreview("sent-dark") { ActivityDetailSentDarkPreview() },
    ScreenPreview("unconfirmed") { ActivityDetailUnconfirmedPreview() },
    ScreenPreview("unlisted-token") { ActivityDetailUnlistedTokenPreview() },
    ScreenPreview("safe-event") { ActivityDetailSafeEventPreview() },
    ScreenPreview("loading") { ActivityDetailLoadingPreview() },
    ScreenPreview("unavailable") { ActivityDetailUnavailablePreview() },
    ScreenPreview("unfamiliar-sender") { ActivityDetailUnfamiliarSenderPreview() },
    ScreenPreview("hidden") { ActivityDetailHiddenPreview() },
    ScreenPreview("hidden-dark") { ActivityDetailHiddenDarkPreview() },
    ScreenPreview("eth-logged") { ActivityDetailEthPreview() },
)
