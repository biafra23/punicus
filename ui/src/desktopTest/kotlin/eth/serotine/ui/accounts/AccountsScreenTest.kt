package eth.serotine.ui.accounts

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.LightClientHealth.NOT_VERIFIED
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.STALE_ANCHOR_CATCH_UP_TAG
import eth.serotine.ui.components.STALE_ANCHOR_CONFIRM_TAG
import eth.serotine.ui.components.StaleAnchorWarning
import eth.serotine.ui.components.UNAVAILABLE_REASON_TAG
import eth.serotine.ui.theme.SerotineTheme
import eth.serotine.ui.update.UPGRADE_BANNER_TAG
import eth.serotine.ui.update.UpgradeBannerItem
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [AccountsScreen] headless (skiko software rendering) for each state. */
class AccountsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val syncing = LightClientItem("Verifying sync committees · 1 of 4 periods", 0.25f, true, NOT_VERIFIED)
    private val daily = AccountItem(
        "Daily",
        "0x8F3a2c71E5b9D04A6f1e3C2bA7d90E5f4C6b91c2",
        Network.SEPOLIA.chainId,
        AccountKind.EOA,
        Control.FULL_CONTROL,
        "0.5 ETH",
        true,
    )
    private val savings = AccountItem(
        "Savings",
        "0x3C9e1a7b5d2f8e4c6b0a9d3e7f1c5a2b8e4d6F09",
        Network.SEPOLIA.chainId,
        AccountKind.EOA,
        Control.WATCH_ONLY,
        "Syncing…",
        false,
    )
    private val actions = mutableListOf<AccountAction>()

    private val adds = mutableListOf<String>()

    private val turnedOn = mutableListOf<Long>()

    private val caughtUp = mutableListOf<Long>()

    /** [accounts] in Sepolia's section, under [syncing]: the only network until parts 4 and 5. */
    private fun ready(
        accounts: List<AccountItem>,
        notice: String? = null,
        total: String? = null,
        totalNote: String? = null,
        upgrades: List<UpgradeBannerItem> = emptyList(),
        on: Boolean = true,
    ) = AccountsUiState.Ready(
        listOfNotNull(
            accounts.takeIf { it.isNotEmpty() }
                ?.let { NetworkSection(Network.SEPOLIA.chainId, "Sepolia", testnet = true, on, syncing, it) },
        ),
        notice,
        total,
        totalNote = totalNote,
        upgrades = upgrades,
    )

    private fun show(state: AccountsUiState) =
        rule.setContent {
            SerotineTheme {
                AccountsScreen(
                    state,
                    onWatchAddress = { adds += "watch" },
                    onImportPhrase = { adds += "import" },
                    onCreateAccount = { adds += "create" },
                    onCreateSafe = { adds += "safe" },
                    onAccountAction = { actions += it },
                    onTurnOnNetwork = { turnedOn += it },
                    onSyncPastStaleAnchor = { caughtUp += it },
                )
            }
        }

    @Test
    fun emptyStateInvitesTheFirstAccount() {
        show(ready(emptyList()))

        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
        rule.onNodeWithTag(ACCOUNTS_LIST_TAG).assertDoesNotExist()
    }

    @Test
    fun readyStateListsEveryAccountWithItsControlAndBalance() {
        show(ready(listOf(daily, savings)))

        rule.onNodeWithTag(ACCOUNTS_LIST_TAG).assertIsDisplayed()
        rule.onNodeWithText("Daily").assertIsDisplayed()
        rule.onNodeWithText("0x8F3a…91c2").assertIsDisplayed()
        rule.onNodeWithText("Full control").assertIsDisplayed()
        rule.onNodeWithText("Watch-only").assertIsDisplayed()
        rule.onNodeWithText("0.5 ETH").assertIsDisplayed()
        rule.onNodeWithText("Syncing…").assertIsDisplayed()
    }

    @Test
    fun aBalanceLastVerifiedSaysSinceWhenItMayHaveChanged() {
        val note = "Last verified Oct 19, 2026, 2:32 PM · may have changed since"
        show(
            ready(listOf(daily.copy(balanceVerified = false, balanceNote = note), savings)),
        )

        rule.onNodeWithText("0.5 ETH").assertIsDisplayed()
        rule.onNodeWithText(note).assertIsDisplayed()
    }

    @Test
    fun anEnsNameThatNoLongerPointsToTheAccountIsSaidUnderItsRow() {
        val note = "ENS name “savings.eth” no longer points to this address"
        show(ready(listOf(daily, savings.copy(nameNote = note))))

        rule.onNodeWithText(note).assertIsDisplayed()
    }

    @Test
    fun aTapOpensTheAccount() {
        show(ready(listOf(daily)))

        rule.onNodeWithText("Daily").performClick()

        assertEquals(listOf<AccountAction>(AccountAction.Open(Network.SEPOLIA.chainId, daily.address)), actions)
    }

    @Test
    fun aLongPressOpensTheAccountMenu() {
        show(ready(listOf(daily)))

        rule.onNodeWithText("Daily").performTouchInput { longClick() }
        rule.onNodeWithText("Receive").assertIsDisplayed()
        rule.onNodeWithText("Copy address").assertIsDisplayed()
        rule.onNodeWithText("Rename").assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()

        assertEquals(listOf<AccountAction>(AccountAction.Remove(Network.SEPOLIA.chainId, daily.address)), actions)
        rule.onNodeWithText("Rename").assertDoesNotExist()
    }

    @Test
    fun sendWorksInTheMenuOfAnAccountWhoseKeyIsHeldHereAndElseSaysWhy() {
        show(ready(listOf(daily, savings)))

        rule.onNodeWithText("Savings").performTouchInput { longClick() }
        rule.onNodeWithText("Receive").assertIsDisplayed()
        // Owner ruling, 2026-10-18, #55: greyed out, and a tap says what is missing.
        rule.onNodeWithText("Send")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
            .performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals(
            "This account is watch-only: its key is not on this device, so nothing can be sent from it. Import its " +
                "recovery phrase to send from it.",
        )
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Savings").performTouchInput { longClick() }
        rule.onNodeWithText("Receive").performClick()
        rule.onNodeWithText("Daily").performTouchInput { longClick() }
        rule.onNodeWithText("Send").performClick()

        assertEquals(
            listOf(
                AccountAction.Receive(Network.SEPOLIA.chainId, savings.address),
                AccountAction.Send(Network.SEPOLIA.chainId, daily.address),
            ),
            actions,
        )
    }

    @Test
    fun aNewAccountFromTheSamePhraseComesFromAKeyHeldHereAndElseSaysWhy() {
        show(ready(listOf(daily, savings)))

        rule.onNodeWithText("Savings").performTouchInput { longClick() }
        // Owner ruling, 2026-10-18, #55: greyed out, and a tap says what is missing.
        rule.onNodeWithText("New account from the same phrase…")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
            .performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals(
            "This account is watch-only: its key is not on this device, so no account of its recovery phrase can " +
                "be added from it. Import its recovery phrase to add its accounts.",
        )
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Daily").performTouchInput { longClick() }
        rule.onNodeWithText("New account from the same phrase…").performClick()

        assertEquals(listOf<AccountAction>(AccountAction.Derive(Network.SEPOLIA.chainId, daily.address)), actions)
    }

    @Test
    fun createSafeIsAlwaysOfferedAndGreyedOutUntilAKeyIsHeldHere() {
        val calls = mutableListOf<String>()
        rule.setContent {
            SerotineTheme {
                AccountsScreen(
                    state = ready(listOf(savings)),
                    onWatchAddress = {},
                    onImportPhrase = { calls += "import" },
                    onCreateAccount = { calls += "create account" },
                    onCreateSafe = { calls += "create safe" },
                    onAccountAction = {},
                )
            }
        }

        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Create Safe")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
            .performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertIsDisplayed()
        rule.onNodeWithText("Import recovery phrase").performClick()

        assertEquals(listOf("import"), calls, "the way to a key, never the Safe")
    }

    @Test
    fun createSafeIsGreyedOutWhileTheAccountsAreStillRead() {
        val calls = mutableListOf<String>()
        rule.setContent {
            SerotineTheme {
                AccountsScreen(
                    state = AccountsUiState.Loading(),
                    onWatchAddress = {},
                    onImportPhrase = { calls += "import" },
                    onCreateAccount = { calls += "create account" },
                    onCreateSafe = { calls += "create safe" },
                    onAccountAction = {},
                )
            }
        }

        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Create Safe")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
            .performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals("Checking which accounts can pay…")
        rule.onNodeWithText("Close").performClick()

        assertEquals(emptyList(), calls, "nothing is started before the list is read")
    }

    @Test
    fun sendFromASafeWithAnOwnerHeldHereProposesASafeTransaction() {
        val treasury = savings.copy(label = "Treasury", kind = AccountKind.SAFE, control = Control.CO_SIGNER)
        val watched = daily.copy(label = "Watched Safe", kind = AccountKind.SAFE, control = Control.WATCH_ONLY)
        show(ready(listOf(watched, treasury)))

        rule.onNodeWithText("Watched Safe").performTouchInput { longClick() }
        rule.onNodeWithText("Send")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
        rule.onNodeWithText("Receive").performClick()
        rule.onNodeWithText("Treasury").performTouchInput { longClick() }
        rule.onNodeWithText("Send").performClick()

        assertEquals(
            listOf(
                AccountAction.Receive(Network.SEPOLIA.chainId, watched.address),
                AccountAction.Propose(Network.SEPOLIA.chainId, treasury.address),
            ),
            actions,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aRightClickOpensTheSameMenu() {
        show(ready(listOf(daily)))

        rule.onNodeWithText("Daily").performMouseInput { rightClick() }
        rule.onNodeWithText("Receive").performClick()

        assertEquals(listOf<AccountAction>(AccountAction.Receive(Network.SEPOLIA.chainId, daily.address)), actions)
    }

    @Test
    fun copyingSaysSo() {
        show(ready(listOf(daily)))

        rule.onNodeWithText("Daily").performTouchInput { longClick() }
        rule.onNodeWithText("Copy address").performClick()

        assertEquals(listOf<AccountAction>(AccountAction.Copy(Network.SEPOLIA.chainId, daily.address)), actions)
        rule.onNodeWithText("Address copied").assertIsDisplayed()
    }

    @Test
    fun eachNetworkIsASectionHeadedByItsNameAndLightClient() {
        show(ready(listOf(daily)))

        rule.onNodeWithTag(ACCOUNTS_SECTION_TAG + Network.SEPOLIA.chainId).assertIsDisplayed()
        rule.onNodeWithText("Sepolia · Testnet", useUnmergedTree = true).assertExists()
        rule.onNodeWithText(syncing.text, useUnmergedTree = true).assertExists()
    }

    @Test
    fun onANetworkThatIsOffSendSaysWhyAndTurnsItOn() {
        val off = daily.copy(networkOff = "Sepolia", balanceVerified = false, balanceNote = "Sepolia is off")
        show(ready(listOf(off), on = false))

        rule.onNodeWithText("Sepolia is off").assertIsDisplayed()
        rule.onNodeWithText("Daily").performTouchInput { longClick() }
        rule.onNodeWithText("Send").performClick()
        rule.onNodeWithText("Send needs Sepolia").assertIsDisplayed()
        rule.onNodeWithText("Turn on Sepolia").performClick()

        assertEquals(listOf(Network.SEPOLIA.chainId), turnedOn)
        assertEquals(emptyList(), actions, "nothing is sent")
    }

    @Test
    fun onANetworkThatIsOffAddOnAnotherNetworkWaitsForItToo() {
        show(ready(listOf(daily.copy(networkOff = "Sepolia")), on = false))

        rule.onNodeWithText("Daily").performTouchInput { longClick() }
        rule.onNodeWithText("Add on another network…").performClick()
        rule.onNodeWithText("Turn on Sepolia").performClick()

        assertEquals(listOf(Network.SEPOLIA.chainId), turnedOn)
        assertEquals(emptyList(), actions)
    }

    @Test
    fun anUpgradeBannerIsShownUnderTheStripWithTheWayToTheUpdate() {
        val banner = UpgradeBannerItem(
            "Network upgrade ahead on Sepolia: update Serotine",
            "Peers announce a Sepolia network upgrade on Nov 6, 2026 that this version does not support.",
            "Reported by peers in 5 networks, not verified · fork id 0x6c1d9423",
            urgent = false,
        )
        show(ready(emptyList(), upgrades = listOf(banner)))

        rule.onNodeWithTag(UPGRADE_BANNER_TAG).assertIsDisplayed()
        rule.onNodeWithText(banner.text).assertIsDisplayed()
        rule.onNodeWithText(banner.details).assertIsDisplayed()
        rule.onNodeWithText("Get the update").assertIsDisplayed()
        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
    }

    @Test
    fun aNetworkBlockedAloneSaysSoInItsSectionAndGreysOutItsAccountsSend() {
        val reason = "This version of Serotine can no longer follow Sepolia: nothing can be sent from its accounts " +
            "until you update the app."
        val state = ready(listOf(daily.copy(networkBlocked = reason))).let { ready ->
            ready.copy(sections = ready.sections.map { it.copy(blocked = reason) })
        }
        show(state)

        rule.onNodeWithText(reason).assertIsDisplayed()
        assertEquals(false, state.sections.single().accounts.single().canSend)
        assertEquals(reason, state.sections.single().accounts.single().sendReason)
    }

    @Test
    fun noUpgradeNoBanner() {
        show(ready(emptyList()))

        rule.onNodeWithTag(UPGRADE_BANNER_TAG).assertDoesNotExist()
    }

    @Test
    fun aSetAsideNoticeIsShownAboveTheList() {
        val notice = "Your saved account list was damaged and has been set aside. Add your accounts again."
        show(ready(emptyList(), notice))

        rule.onNodeWithTag(ACCOUNTS_NOTICE_TAG).assertTextEquals(notice)
        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
        rule.onNodeWithContentDescription("Add account").assertExists()
    }

    @Test
    fun anUnavailableListIsExplainedWithoutInvitingAnAdd() {
        val message = "Your saved account list cannot be read right now."
        show(AccountsUiState.ListUnavailable(message))

        rule.onNodeWithTag(ACCOUNTS_NOTICE_TAG).assertTextEquals(message)
        rule.onNodeWithText("No accounts yet").assertDoesNotExist()
        rule.onNodeWithContentDescription("Add account").assertDoesNotExist()
    }

    @Test
    fun aSectionSaysWhatItsLightClientIsDoing() {
        show(ready(listOf(daily)))

        rule.onNodeWithText("Verifying sync committees · 1 of 4 periods", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theFloatingActionButtonFansOutIntoWatchAndImport() {
        show(ready(emptyList()))

        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Watch address").performClick()
        rule.onNodeWithText("Import recovery phrase").assertDoesNotExist()
        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Import recovery phrase").performClick()

        assertEquals(listOf("watch", "import"), adds)
    }

    @Test
    fun theHeaderTotalsThePortfolioAndEachRowHasItsValue() {
        show(
            ready(listOf(daily.copy(value = "$1,000.00")), total = "$1,000.00", totalNote = "Testnet prices"),
        )

        rule.onNodeWithTag(ACCOUNTS_TOTAL_TAG).assertTextEquals("$1,000.00")
        rule.onNodeWithText("Testnet prices").assertIsDisplayed()
        // The header's total and the row's value.
        rule.onAllNodesWithText("$1,000.00", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun aSafesBlockieCarriesItsThresholdAndItsRowItsKind() {
        show(
            ready(listOf(daily.copy(kind = AccountKind.SAFE, signers = "2/3", kindNote = "Safe"))),
        )

        rule.onNodeWithText("2/3", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("0x8F3a…91c2 · Safe", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aStaleCheckpointIsCaughtUpWithOnlyAfterTheDialogIsConfirmed() {
        val warning = StaleAnchorWarning.of(Network.SEPOLIA, LightClientStatus.StaleAnchor(6, 3))
        val state = ready(listOf(daily))
        show(state.copy(sections = state.sections.map { it.copy(staleAnchor = warning) }))

        rule.onNodeWithText(warning.banner).assertExists()
        rule.onNodeWithTag(STALE_ANCHOR_CATCH_UP_TAG + Network.SEPOLIA.chainId).performClick()
        rule.onNodeWithText(warning.title).assertExists()
        assertEquals(emptyList(), caughtUp, "asked first")

        rule.onNodeWithTag(STALE_ANCHOR_CONFIRM_TAG).performClick()
        assertEquals(listOf(Network.SEPOLIA.chainId), caughtUp)
        rule.onNodeWithText(warning.title).assertDoesNotExist()
    }
}
