package eth.serotine.ui.accounts

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Network
import eth.serotine.ui.components.AMOUNT_MASK
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.HIDE_AMOUNTS
import eth.serotine.ui.components.HIDE_AMOUNTS_TAG
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.SHOW_AMOUNTS
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [SimpleAccountsScreen] headless. */
class SimpleAccountsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var refreshes = 0
    private val actions = mutableListOf<AccountAction>()

    private val ethereum = NetworkLabel.of(Network.ETHEREUM)
    private val main = AccountItem(
        "Main",
        "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        ethereum.chainId,
        AccountKind.EOA,
        Control.FULL_CONTROL,
        "3.2041 ETH",
        balanceVerified = true,
        network = ethereum,
        networkLine = "Ethereum",
        accountTotal = "€12,025.55",
    )
    private val strip = LightClientItem("Verified at block 23,614,077 · 8 s ago", null, false, VERIFIED)

    private var toggles = 0

    private fun show(state: AccountsUiState) = rule.setContent {
        SerotineTheme {
            SimpleAccountsScreen(
                state = state,
                onWatchAddress = {},
                onImportPhrase = {},
                onCreateAccount = {},
                onCreateSafe = {},
                onAccountAction = { actions += it },
                onRefresh = { refreshes++ },
                onToggleAmounts = { toggles++ },
            )
        }
    }

    private fun ready(vararg accounts: AccountItem) = AccountsUiState.Ready(
        listOf(NetworkSection(ethereum.chainId, "Ethereum", false, true, strip, accounts.toList())),
        total = "€12,025.55",
        freshness = "Updated 2 min ago · pull down to refresh",
    )

    @Test
    fun theEyeAtTheTopLeftSaysWhatItDoesAndAsksToToggle() {
        show(ready(main))
        rule.onNodeWithContentDescription(HIDE_AMOUNTS).assertIsDisplayed().performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun hiddenAmountsAreDrawnAsTheyComeAndTheEyeIsClosed() {
        val hidden = ready(main).withAmountsHidden(true) as AccountsUiState.Ready
        show(hidden)

        rule.onNodeWithContentDescription(SHOW_AMOUNTS).assertIsDisplayed()
        // The total and the account's total: two figures, both masked.
        rule.onAllNodesWithText("€$AMOUNT_MASK").assertCountEquals(2)
        rule.onNodeWithText("$AMOUNT_MASK ETH").assertIsDisplayed()
        rule.onNodeWithText("€12,025.55").assertDoesNotExist()
        rule.onNodeWithText("3.2041 ETH").assertDoesNotExist()
        // The name and the network stay.
        rule.onNodeWithText("Main").assertIsDisplayed()
    }

    @Test
    fun noEyeWhileThereIsNothingToHide() {
        show(AccountsUiState.Loading())
        rule.onNodeWithTag(HIDE_AMOUNTS_TAG).assertDoesNotExist()
    }

    @Test
    fun aRowsMenuAddsAnAccountFromTheSamePhrase() {
        show(ready(main))

        rule.onNodeWithText("Main").performTouchInput { longClick() }
        rule.onNodeWithText("New account from the same phrase…").performClick()

        assertEquals(listOf<AccountAction>(AccountAction.Derive(ethereum.chainId, main.address)), actions)
    }

    @Test
    fun theTotalSaysHowFreshItIsAndNoBlockNumberIsShown() {
        show(ready(main))

        rule.onNodeWithTag(ACCOUNTS_TOTAL_TAG).assertTextEquals("€12,025.55")
        rule.onNodeWithTag(ACCOUNTS_FRESHNESS_TAG).assertTextEquals("Updated 2 min ago · pull down to refresh")
        rule.onNodeWithText("Verified at block", substring = true).assertDoesNotExist()
        // The network named in text and on the blockie's icon.
        rule.onNodeWithText("Ethereum").assertIsDisplayed()
        rule.onNodeWithContentDescription("Ethereum").assertIsDisplayed()
    }

    @Test
    fun theRefreshButtonAsksForARefresh() {
        show(ready(main))

        rule.onNodeWithTag(ACCOUNTS_REFRESH_TAG).performClick()
        assertEquals(1, refreshes)
    }

    @Test
    fun aRowOpensItsAccount() {
        show(ready(main))

        rule.onNodeWithText("Main").performClick()
        assertEquals(listOf<AccountAction>(AccountAction.Open(main.chainId, main.address)), actions)
    }

    @Test
    fun aNetworkThatCannotBeCheckedIsSaidAndItsRowsAreDated() {
        show(
            ready(main.copy(balanceVerified = false, accountTotal = null, ageNote = "3 h ago · may have changed"))
                .copy(
                    unreachable = listOf(
                        UnreachableNetwork(ethereum.chainId, "Can't check Ethereum right now", "The app keeps trying."),
                    ),
                ),
        )

        rule.onNodeWithTag(ACCOUNTS_UNREACHABLE_TAG + ethereum.chainId).assertIsDisplayed()
        rule.onNodeWithText("Can't check Ethereum right now").assertIsDisplayed()
        rule.onNodeWithText("3 h ago · may have changed").assertIsDisplayed()
    }

    @Test
    fun aDamagedListSetAsideIsSaidAboveTheEmptyList() {
        show(AccountsUiState.Ready(emptyList(), notice = "Your saved account list was damaged and has been set aside."))

        rule.onNodeWithTag(ACCOUNTS_NOTICE_TAG).assertIsDisplayed()
        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
    }

    @Test
    fun whatANetworkHasQueuedIsSaidAndALostOneUntilDismissed() {
        var dismissed = 0
        rule.setContent {
            SerotineTheme {
                SimpleAccountsScreen(
                    state = ready(main).copy(
                        queued = listOf("Ethereum: 1 waiting to be sent · Keep Serotine open to send it sooner"),
                        lostNotice = "A transaction waiting to be sent was lost.",
                    ),
                    onWatchAddress = {},
                    onImportPhrase = {},
                    onCreateAccount = {},
                    onCreateSafe = {},
                    onAccountAction = {},
                    onRefresh = {},
                    onDismissLost = { dismissed++ },
                )
            }
        }

        rule.onNodeWithTag(ACCOUNTS_QUEUED_TAG)
            .assertTextEquals("Ethereum: 1 waiting to be sent · Keep Serotine open to send it sooner")
        rule.onNodeWithText("A transaction waiting to be sent was lost.").assertIsDisplayed()
        rule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, dismissed)
    }
}
