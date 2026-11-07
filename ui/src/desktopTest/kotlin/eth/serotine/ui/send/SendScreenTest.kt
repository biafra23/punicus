package eth.serotine.ui.send

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.ASSET_OPTION_TAG
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.RecipientItem
import eth.serotine.ui.components.SEND_AMOUNT_TAG
import eth.serotine.ui.components.SEND_ASSET_TAG
import eth.serotine.ui.components.SEND_RECIPIENT_TAG
import eth.serotine.ui.components.SEND_REVIEW_TAG
import eth.serotine.ui.components.SEND_TO_TAG
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.TransferForm
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [SendScreen] headless for its steps. */
class SendScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val usdcContract = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
    private val savings = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
    private val form = TransferForm(
        network = "Sepolia",
        from = SendFrom("Daily", "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2"),
        assets = listOf(
            AssetChoice("ETH", "Ether", "eth", "1.5 ETH", verifiedAt = "block 9"),
            AssetChoice("USDC", "USDC", "usdc", "250 USDC", contract = usdcContract, verifiedAt = "block 9"),
        ),
        asset = AssetChoice("ETH", "Ether", "eth", "1.5 ETH", verifiedAt = "block 9"),
        to = "",
        accounts = listOf(AccountChoice("Savings", savings)),
        amount = "",
        canMax = true,
    )
    private val compose = SendUiState.Compose(form, FeeLevel.NORMAL)
    private val review = SendUiState.Review(
        network = "Sepolia",
        from = form.from,
        amount = "0.1 ETH",
        asset = form.assets.first(),
        recipient = RecipientItem(savings, label = "Savings"),
        maxFee = "0.000462 ETH",
        feeDetail = "Normal fee: 21,000 gas × at most 22 gwei",
        balances = listOf(BalanceChange("ETH balance", "1.5 ETH", "at least 1.399538 ETH", "verified at block 9")),
        simulation = "The transfer, run as a verified call from this account at block 100, goes through.",
        unlock = UnlockStep(),
    )
    private val events = mutableListOf<String>()

    private fun show(state: SendUiState) = rule.setContent {
        SerotineTheme {
            SendScreen(
                state = state,
                chrome = SendChrome.Strip(verifiedStripSample),
                onAssetChange = { events += "asset $it" },
                onToChange = { events += "to $it" },
                onPickAccount = { events += "pick $it" },
                onRetryLookup = { events += "lookup again" },
                onAmountChange = { events += "amount $it" },
                onMax = { events += "max" },
                onFeeChange = { events += "fee $it" },
                onReview = { events += "review" },
                onEdit = { events += "edit" },
                onRefresh = { events += "refresh" },
                onPasswordChange = { events += "password" },
                onSign = { events += "sign" },
                onSignWithBiometrics = { events += "biometrics" },
                onRetry = { events += "retry" },
                onOpenTransfer = { events += "open $it" },
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun anEarlierTransactionMaybeOnItsWayIsOpenedFromTheComposeStep() {
        val hash = "0x" + "5e".repeat(32)
        val refused = compose.form.copy(error = "An earlier transaction may still be on its way.")
        show(compose.copy(form = refused, earlier = hash))

        rule.onNodeWithText("An earlier transaction may still be on its way.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SEND_EARLIER_TAG).performScrollTo().performClick()
        assertEquals(listOf("open $hash"), events)
    }

    @Test
    fun composeTakesTheAssetTheRecipientTheAmountAndTheFee() {
        show(compose)

        rule.onNodeWithText("Daily").assertIsDisplayed()
        rule.onNodeWithText("Sepolia").assertIsDisplayed()
        rule.onNodeWithTag(SEND_ASSET_TAG).performClick()
        rule.onAllNodesWithTag(ASSET_OPTION_TAG)[1].performClick()
        rule.onNodeWithTag(SEND_TO_TAG).performTextInput("friend.eth")
        rule.onNodeWithText("Savings · 0xfB69…d359").performClick()
        rule.onNodeWithTag(SEND_AMOUNT_TAG).performTextInput("0.1")
        rule.onNodeWithText("Max").performClick()
        rule.onNodeWithText("Fast").performScrollTo().performClick()
        rule.onNodeWithTag(SEND_REVIEW_TAG).performScrollTo().assertIsNotEnabled()

        // A field whose state is not fed back may report its text more than once.
        assertEquals(
            listOf("asset $usdcContract", "to friend.eth", "pick $savings", "amount 0.1", "max", "fee FAST"),
            events.filterNot { it == "to " || it == "amount " },
        )
    }

    @Test
    fun theAssetIsChosenFromAMenuOfTheAccountsAssetsWithTheirBalances() {
        show(compose)

        // Closed, the line shows the chosen asset and no other.
        rule.onAllNodesWithTag(ASSET_OPTION_TAG).assertCountEquals(0)
        rule.onNodeWithText("250 USDC · verified at block 9").assertDoesNotExist()
        rule.onNodeWithTag(SEND_ASSET_TAG).performClick()
        rule.onAllNodesWithTag(ASSET_OPTION_TAG).assertCountEquals(2)
        rule.onNodeWithText("250 USDC · verified at block 9").assertIsDisplayed()
        rule.onNodeWithContentDescription("Chosen").assertIsDisplayed()
        rule.onAllNodesWithTag(ASSET_OPTION_TAG)[1].performClick()

        rule.onAllNodesWithTag(ASSET_OPTION_TAG).assertCountEquals(0)
        assertEquals(listOf("asset $usdcContract"), events)
    }

    @Test
    fun withNoAssetChosenTheMenuAsksForOneAndSaysWhy() {
        val note = "The token asked for is not among this account's tokens: choose another asset."
        show(compose.copy(form = form.copy(asset = null, assetNote = note)))

        rule.onNodeWithText("Choose the asset to send").assertIsDisplayed()
        rule.onNodeWithText(note).assertIsDisplayed()
        rule.onNodeWithTag(SEND_ASSET_TAG).assertIsEnabled()
    }

    @Test
    fun whileQuotingTheAssetIsHeldAndItsMenuDoesNotOpen() {
        show(compose.copy(form = form.copy(quoting = true)))

        rule.onNodeWithTag(SEND_ASSET_TAG).assertIsNotEnabled().performClick()
        rule.onAllNodesWithTag(ASSET_OPTION_TAG).assertCountEquals(0)
        assertEquals(emptyList(), events)
    }

    @Test
    fun aResolvedRecipientIsShownInFullAndReviewIsAskedFor() {
        show(
            compose.copy(
                form = form.copy(
                    to = "friend.eth",
                    recipient = RecipientItem(
                        savings,
                        name = "friend.eth",
                        resolvedAt = "Resolved at finalized block 7",
                    ),
                    amount = "0.1",
                    amountHint = "≈ $250.00 · testnet prices",
                    canReview = true,
                ),
            ),
        )

        rule.onNodeWithTag(SEND_RECIPIENT_TAG).assertTextEquals(savings)
        rule.onNodeWithText("Resolved at finalized block 7").assertIsDisplayed()
        rule.onNodeWithText("≈ $250.00 · testnet prices").assertIsDisplayed()
        rule.onNodeWithTag(SEND_REVIEW_TAG).performScrollTo().performClick()
        assertEquals(listOf("review"), events)
    }

    @Test
    fun whatIsWrongWithTheInputsAndWhyTheQuoteWasRefusedIsSaid() {
        show(
            compose.copy(
                form = form.copy(
                    to = "later.eth",
                    toNote = "The name could not be resolved with verified calls this time.",
                    toError = true,
                    canRetryLookup = true,
                    amount = "0,5",
                    amountError = "Use a dot for the decimals: 0.5, not 0,5.",
                    error = "The light client is not synced yet: try again in a moment.",
                ),
            ),
        )

        rule.onNodeWithText("The name could not be resolved with verified calls this time.").assertIsDisplayed()
        rule.onNodeWithText("Use a dot for the decimals: 0.5, not 0,5.").assertIsDisplayed()
        rule.onNodeWithText("The light client is not synced yet: try again in a moment.").performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(listOf("lookup again"), events)
    }

    @Test
    fun theReviewShowsTheQuoteAndSignsWithThePassword() {
        show(review.copy(unlock = UnlockStep(WalletPassword("made-up test password"))))

        // The network first, and in the button that signs (docs/networks.md, N14).
        rule.onNodeWithTag(SEND_NETWORK_TAG).assertTextEquals("On ${review.network}")
        rule.onNodeWithTag(SEND_SIGN_TAG).assertTextEquals("Sign and send on ${review.network}")
        rule.onNodeWithTag(SEND_AMOUNT_REVIEWED_TAG).assertTextEquals("0.1 ETH")
        rule.onNodeWithTag(SEND_RECIPIENT_TAG).assertTextEquals(savings)
        rule.onNodeWithText("At most 0.000462 ETH").assertIsDisplayed()
        rule.onNodeWithText("1.5 ETH → at least 1.399538 ETH").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Sign with fingerprint or face").assertDoesNotExist()
        rule.onNodeWithTag(SEND_PASSWORD_TAG).performScrollTo().performTextInput("x")
        rule.onNodeWithTag(SEND_SIGN_TAG).performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("Refresh the quote").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf("password", "sign", "refresh", "edit"), events.distinct())
    }

    @Test
    fun theFingerprintIsOfferedWithThePasswordAsTheWayOut() {
        show(review.copy(unlock = UnlockStep(biometrics = true, error = "That is not the wallet password.")))

        rule.onNodeWithText("Sign with fingerprint or face").performScrollTo().performClick()
        rule.onNodeWithText("That is not the wallet password.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SEND_SIGN_TAG).performScrollTo().assertIsNotEnabled()

        assertEquals(listOf("biometrics"), events)
    }

    @Test
    fun aTransferSignedButNotSentOffersOnlyToSendItAgain() {
        show(review.copy(notSent = NotSentItem("Signed, but not sent: no peer took it right now.", canRetry = true)))

        rule.onNodeWithText("Signed, but not sent: no peer took it right now.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SEND_PASSWORD_TAG).assertDoesNotExist()
        rule.onNodeWithText("Try again").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf("retry", "back"), events, "back leaves: nothing to edit once signed")
    }

    @Test
    fun whileSigningNothingElseCanBeAsked() {
        show(review.copy(signing = true))

        rule.onNodeWithContentDescription("Back").assertIsNotEnabled()
        rule.onNodeWithText("Signing and sending…").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Refresh the quote").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun anAccountThatCannotSendSaysWhy() {
        show(SendUiState.CannotSend("Sepolia", "This account is watch-only."))

        rule.onNodeWithText("This account is watch-only.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("back"), events)
    }

    @Test
    fun theKeyboardsDoneOnTheAmountReviewsOnlyWhenReviewCan() {
        show(compose)
        rule.onNodeWithTag(SEND_AMOUNT_TAG).performScrollTo().performImeAction()
        assertEquals(emptyList(), events)
    }

    @Test
    fun theKeyboardsDoneOnTheAmountReviews() {
        show(compose.copy(form = form.copy(to = savings, amount = "0.1", canReview = true)))
        rule.onNodeWithTag(SEND_AMOUNT_TAG).performScrollTo().performImeAction()
        assertEquals(listOf("review"), events)
    }

    @Test
    fun theKeyboardsNextOnTheRecipientGoesToTheAmount() {
        // A lookup that failed puts Try again and the account chips between the two fields.
        show(compose.copy(form = form.copy(to = "savings.eth", canRetryLookup = true)))
        rule.onNodeWithTag(SEND_TO_TAG).performScrollTo().performImeAction()
        rule.onNodeWithTag(SEND_AMOUNT_TAG).assertIsFocused()
    }

    // A lenient draft (docs/lenient-mode.md, *The draft's review*).

    private val draft = SendUiState.DraftReview(
        network = "Sepolia",
        from = form.from,
        amount = "0.1 ETH",
        asset = form.assets.first().copy(lastKnown = true, age = "as of 12 min ago"),
        recipient = RecipientItem(savings, label = "Savings"),
        preparedOn = "Prepared on data from 12 min ago. It's checked on chain again before it's sent.",
        maxFee = "0.0011 ETH",
        feeDetail = "Normal fee: 50,000 gas, the most this transfer is signed for, × at most 22 gwei, from the fee " +
            "history last verified up to block 8.",
        feeAge = "Fees as of 13 min ago",
        nonce = "Nonce 7, after this account's count of mined transactions last verified at block 8",
        nonceAge = "Nonce 7, counted as of 13 min ago",
        balances = listOf(
            BalanceChange(
                "ETH balance",
                "1.5 ETH",
                "at least 1.3989 ETH, if nothing changed",
                "last verified at block 9",
                age = "as of 12 min ago",
            ),
        ),
        unlock = UnlockStep(WalletPassword("made-up password")),
    )

    @Test
    fun aDraftsReviewSaysWhatItIsPreparedOnThatItIsNotCheckedYetAndSignsAndQueues() {
        show(draft)

        rule.onNodeWithTag(SEND_PREPARED_ON_TAG).assertTextEquals(draft.preparedOn)
        rule.onNodeWithText("last verified at block 9").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SEND_SIMULATION_TAG).performScrollTo().assertTextEquals(NOT_CHECKED_YET)
        rule.onNodeWithText("Refresh the quote").assertDoesNotExist()
        rule.onNodeWithTag(SEND_SIGN_TAG).performScrollTo().assertTextEquals("Sign and queue on Sepolia").performClick()
        assertEquals(listOf("sign"), events)
    }

    @Test
    fun backFromADraftsReviewReturnsToTheComposeStep() {
        show(draft)

        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("edit"), events)
    }

    @Test
    fun aDraftsReviewInSimpleModeSaysEachFiguresAgeAndKeepsTheBlocksUnderDetails() {
        rule.setContent {
            SerotineTheme {
                SimpleSendScreen(
                    state = draft,
                    onAssetChange = {},
                    onToChange = {},
                    onPickAccount = {},
                    onRetryLookup = {},
                    onAmountChange = {},
                    onMax = {},
                    onFeeChange = {},
                    onReview = {},
                    onEdit = {},
                    onRefresh = {},
                    onPasswordChange = {},
                    onSign = { events += "sign" },
                    onSignWithBiometrics = {},
                    onRetry = {},
                    onOpenTransfer = {},
                    onBack = {},
                )
            }
        }

        rule.onNodeWithText("Fees as of 13 min ago").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("as of 12 min ago").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("last verified at block 9", substring = true).assertDoesNotExist()
        rule.onNodeWithTag(SEND_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText("Nonce 7, counted as of 13 min ago").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SEND_SIGN_TAG).performScrollTo().assertTextEquals("Sign and queue on Sepolia")
    }

    @Test
    fun theTransactionAnAccountHasQueuedIsOpenedFromTheComposeStep() {
        val id = "0x" + "9a".repeat(32)
        val refused = compose.form.copy(error = "A transaction from this account is waiting to be sent.")
        show(compose.copy(form = refused, earlier = id, earlierLabel = "Open the transaction waiting to be sent"))

        rule.onNodeWithText("Open the transaction waiting to be sent").performScrollTo().performClick()
        assertEquals(listOf("open $id"), events)
    }
}
