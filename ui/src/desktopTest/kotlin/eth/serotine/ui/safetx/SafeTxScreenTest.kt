package eth.serotine.ui.safetx

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.ActionAddress
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.SAFE_SWAP_PROPOSE_AGAIN_TAG
import eth.serotine.ui.components.SAFE_SWAP_TAG
import eth.serotine.ui.components.SAFE_TX_ACTION_TAG
import eth.serotine.ui.components.SAFE_TX_QR_TAG
import eth.serotine.ui.components.SAFE_TX_REQUEST_TAG
import eth.serotine.ui.components.SafeSwapItem
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.ShareItem
import eth.serotine.ui.components.UNAVAILABLE_REASON_TAG
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [SafeTxScreen] headless for its overview and its steps. */
class SafeTxScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val treasury = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
    private val daily = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val savings = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
    private val hash = "0x" + "3c".repeat(32)
    private val action = SafeTxActionItem("Send 40 USDC", listOf(ActionAddress("To", savings)), symbol = "USDC")
    private val overview = SafeTxUiState.Overview(
        network = "Sepolia",
        safe = SafeHeader("Treasury", treasury, "2/3"),
        action = action,
        nonce = "Safe nonce 12 · next to run",
        hash = hash,
        owners = OwnersItem.Shown(
            rows = listOf(
                OwnerStateRow(daily, "Daily", heldHere = true, OwnerState.MISSING, "Not yet"),
                OwnerStateRow(savings, "Savings", heldHere = false, OwnerState.SIGNED, "Signed"),
            ),
            threshold = "2 of 2 owners must confirm",
            confirmations = "1 of 2 confirmations",
            verifiedAt = "Owners verified at block 9",
        ),
        canSign = true,
        canApprove = true,
        executeNote = "1 of the 2 confirmations it needs to be executed.",
    )
    private val review = SafeTxUiState.Review(
        network = "Sepolia",
        safe = overview.safe,
        action = action,
        nonce = overview.nonce,
        kind = ReviewKind.APPROVAL,
        accounts = listOf(SignerChoice("Daily", daily)),
        account = daily,
        fee = FeeLevel.NORMAL,
        maxFee = "0.00105 ETH",
        feeDetail = "Normal fee: 47,500 gas × at most 22 gwei",
        balance = BalanceChange(
            "ETH balance of Daily, which pays the fee",
            "1.5 ETH",
            "at least 1.49895 ETH",
            "verified at block 9",
        ),
        simulation = "The Safe's approveHash of this transaction, run as a verified call from this account at " +
            "block 9, goes through.",
        unlock = UnlockStep(),
    )
    private val events = mutableListOf<String>()

    private fun show(state: SafeTxUiState, shareSheet: Boolean = true) = rule.setContent {
        SerotineTheme {
            SafeTxScreen(
                state = state,
                lightClient = verifiedStripSample,
                actions = SafeTxActions(
                    onShare = { events += "share" },
                    onShareClosed = { events += "share closed" },
                    onCheckAgain = { events += "check again" },
                    onSign = { events += "sign" },
                    onApprove = { events += "approve" },
                    onExecute = { events += "execute" },
                    onRemove = { events += "remove" },
                    onSignerChange = { events += "signer $it" },
                    onAccountChange = { events += "account $it" },
                    onFeeChange = { events += "fee $it" },
                    onRefresh = { events += "refresh" },
                    onPasswordChange = { events += "password" },
                    onConfirm = { events += "confirm" },
                    onConfirmWithBiometrics = { events += "biometrics" },
                    onRetry = { events += "retry" },
                    onStepBack = { events += "step back" },
                    onProposeAgain = { events += "propose again" },
                ),
                onBack = { events += "back" },
                onCopy = { events += "copy $it" },
                onShareText = if (shareSheet) ({ events += "share text $it" }) else null,
            )
        }
    }

    @Test
    fun theOverviewShowsTheTransactionDecodedHereAndEveryOwnersStand() {
        show(overview)

        rule.onNodeWithTag(SAFE_TX_ACTION_TAG).assertTextEquals("Send 40 USDC")
        rule.onNodeWithText(savings).assertIsDisplayed()
        rule.onNodeWithText("Safe nonce 12 · next to run").assertIsDisplayed()
        rule.onNodeWithTag(SAFE_TX_HASH_TAG).performScrollTo().assertTextEquals(hash)
        assertEquals(2, rule.onAllNodesWithTag(SAFE_TX_OWNER_TAG).fetchSemanticsNodes().size)
        rule.onNodeWithText("This device").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("1 of 2 confirmations").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("1 of the 2 confirmations it needs to be executed.").performScrollTo().assertIsDisplayed()
        // Owner ruling, 2026-10-18, #55: shown greyed out, and a tap says why.
        rule.onNodeWithText("Execute").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
            .performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG)
            .assertTextEquals("1 of the 2 confirmations it needs to be executed.")
        rule.onNodeWithText("Close").performClick()

        rule.onNodeWithText("Sign").performScrollTo().performClick()
        rule.onNodeWithText("Approve on chain").performScrollTo().performClick()
        rule.onNodeWithText("Check again").performScrollTo().performClick()
        assertEquals(listOf("sign", "approve", "check again"), events)
    }

    @Test
    fun aSwapsStandNowIsShownUnderItsActionAndOnTheExecutionsReview() {
        val swap = SafeSwapItem(
            listOf("Returns 0.05 ETH now, by a verified QuoterV2 quote at block 9: it meets its minimum."),
            warning = "Its minimum has fallen behind the market.",
        )
        show(overview.copy(swap = swap))
        rule.onNodeWithText("Returns 0.05 ETH now, by a verified QuoterV2 quote at block 9: it meets its minimum.")
            .assertIsDisplayed()
        rule.onNodeWithText("Its minimum has fallen behind the market.").assertIsDisplayed()
    }

    @Test
    fun theExecutionsReviewSaysTheSwapsMinimumHasFallenBehind() {
        show(review.copy(swap = SafeSwapItem(emptyList(), warning = "Its minimum has fallen behind the market.")))
        rule.onNodeWithTag(SAFE_SWAP_TAG).assertIsDisplayed()
        rule.onNodeWithText("Its minimum has fallen behind the market.").assertIsDisplayed()
        rule.onNodeWithTag(SAFE_SWAP_PROPOSE_AGAIN_TAG).assertDoesNotExist()
    }

    @Test
    fun aSwapThatCannotBeProposedAgainFromHereIsGreyedOutAndATapSaysWhy() {
        val swap = SafeSwapItem(emptyList(), proposeAgain = true, proposeAgainReason = "No owner of this Safe is here.")
        show(overview.copy(swap = swap))
        rule.onNodeWithTag(SAFE_SWAP_PROPOSE_AGAIN_TAG).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
            .performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals("No owner of this Safe is here.")
        assertEquals(emptyList(), events)
    }

    @Test
    fun aSwapWorthProposingAgainOffersIt() {
        val swap = SafeSwapItem(emptyList(), warning = "Its minimum has fallen behind the market.", proposeAgain = true)
        show(review.copy(swap = swap))
        rule.onNodeWithTag(SAFE_SWAP_PROPOSE_AGAIN_TAG).performScrollTo().assertTextEquals("Propose the swap again")
            .performClick()
        assertEquals(listOf("propose again"), events)
    }

    @Test
    fun shareRequestShowsItsQrCodeItsTextCopyAndShare() {
        show(overview.copy(share = ShareItem("request-text", qr = true, note = "The raw transaction and 1 signature.")))

        rule.onNodeWithTag(SAFE_TX_QR_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SAFE_TX_REQUEST_TAG).performScrollTo().assertTextEquals("request-text")
        rule.onNodeWithText("Share").performScrollTo().performClick()
        rule.onNodeWithText("Close").performScrollTo().performClick()
        // Last: the snackbar that says it was copied covers the bottom of the screen.
        rule.onNodeWithText("Copy text").performScrollTo().performClick()
        assertEquals(listOf("share text request-text", "share closed", "copy request-text"), events)
    }

    @Test
    fun aRequestTooLargeForOneQrCodeIsSharedAsTextWhereThereIsNoShareSheet() {
        show(
            overview.copy(share = ShareItem("long-text", qr = false, note = "Too large for one QR code.")),
            shareSheet = false,
        )

        rule.onNodeWithTag(SAFE_TX_QR_TAG).assertDoesNotExist()
        rule.onNodeWithText("Too large for one QR code.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Share").assertDoesNotExist()
        rule.onNodeWithText("Copy text").performScrollTo().performClick()
        assertEquals(listOf("copy long-text"), events)
    }

    @Test
    fun shareRequestOpensTheSharing() {
        show(overview)

        rule.onNodeWithTag(SAFE_TX_SHARE_TAG).performScrollTo().performClick()
        assertEquals(listOf("share"), events)
    }

    @Test
    fun aTransactionThisWalletWillNotSignSaysWhyAndOffersNothingToSign() {
        show(
            overview.copy(
                refusal = "A delegate call: Punicus does not sign, approve or execute one yet. " +
                    "Batches through the pinned MultiSendCallOnly come later.",
                canSign = false,
                canApprove = false,
                executeNote = null,
            ),
        )

        rule.onNodeWithText("Batches through the pinned MultiSendCallOnly come later.", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText("Sign").assertDoesNotExist()
        rule.onNodeWithText("Approve on chain").assertDoesNotExist()
        rule.onNodeWithTag(SAFE_TX_SHARE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun removingAsksFirst() {
        show(overview)

        rule.onNodeWithText("Remove from this device").performScrollTo().performClick()
        rule.onNodeWithText("Remove from this device?").assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()
        assertEquals(listOf("remove"), events)
    }

    @Test
    fun theSigningStepTakesTheOwnerAndThePassword() {
        show(
            SafeTxUiState.Signing(
                network = "Sepolia",
                safe = overview.safe,
                action = action,
                nonce = overview.nonce,
                hash = hash,
                owners = listOf(SignerChoice("Daily", daily), SignerChoice("Savings", savings)),
                owner = daily,
                unlock = UnlockStep(WalletPassword("made-up test password")),
            ),
        )

        rule.onNodeWithText("Savings").performScrollTo().performClick()
        rule.onNodeWithTag(SAFE_TX_PASSWORD_TAG).performScrollTo().performTextInput("x")
        rule.onNodeWithTag(SAFE_TX_SIGN_TAG).performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("signer $savings", "password", "confirm", "step back"), events.distinct())
    }

    @Test
    fun theReviewShowsTheQuoteAndSignsWithThePassword() {
        show(review.copy(unlock = UnlockStep(WalletPassword("made-up test password"))))

        rule.onNodeWithText("Approve on chain").assertIsDisplayed()
        rule.onNodeWithText("At most 0.00105 ETH").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("1.5 ETH → at least 1.49895 ETH").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Fast").performScrollTo().performClick()
        rule.onNodeWithText("Refresh the quote").performScrollTo().performClick()
        rule.onNodeWithTag(SAFE_TX_SIGN_TAG).performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("fee FAST", "refresh", "confirm", "step back"), events)
    }

    @Test
    fun anApprovalSignedButNotSentOffersOnlyToSendItAgainAndBackLeaves() {
        show(review.copy(notSent = NotSentItem("Signed, but not sent: no peer took it right now.", canRetry = true)))

        rule.onNodeWithTag(SAFE_TX_PASSWORD_TAG).assertDoesNotExist()
        rule.onNodeWithText("Try again").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("retry", "back"), events)
    }

    @Test
    fun whileSigningBackWaits() {
        show(review.copy(signing = true))

        rule.onNodeWithContentDescription("Back").assertIsNotEnabled()
        rule.onNodeWithText("Signing and sending…").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun whileTheSafeIsCheckedBeforeSigningItSaysSoAndBackStopsIt() {
        show(
            SafeTxUiState.Signing(
                network = "Sepolia",
                safe = overview.safe,
                action = action,
                nonce = overview.nonce,
                hash = hash,
                owners = listOf(SignerChoice("Daily", daily)),
                owner = daily,
                unlock = UnlockStep(),
                signing = true,
                checking = true,
            ),
        )

        rule.onNodeWithText("Checking the Safe on chain…").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").assertIsEnabled().performClick()
        assertEquals(listOf("step back"), events)
    }

    @Test
    fun whileApprovalOrExecutionIsQuotedTheSafesCheckIsSaid() {
        show(overview.copy(quoting = true))

        rule.onNodeWithText("Checking the Safe on chain…").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aTransactionNoLongerQueuedSaysSo() {
        show(SafeTxUiState.NotQueued("Sepolia", "This transaction is not in this device's queue."))

        rule.onNodeWithText("This transaction is not in this device's queue.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("back"), events)
    }
}
