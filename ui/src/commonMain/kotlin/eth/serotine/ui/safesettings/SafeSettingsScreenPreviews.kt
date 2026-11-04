package eth.serotine.ui.safesettings

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.ActionAddress
import eth.serotine.ui.components.GUARD_REMOVED_WARNING
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.MODULE_ENABLED_REFUSAL
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Safe settings in Android Studio's preview pane, one preview per state it draws differently.
// Built from display-ready values, as the screen tests are, never from the ViewModel.
// SafeSettingsScreenPreviewsTest renders each one in safeSettingsScreenPreviews, at the end. The
// addresses are EIP-55's own examples; the names and blocks are made up. A preview has no window to
// keep out of screen capture, so the review's password field shows the line saying so, which a
// device that blocks capture does not.

private const val TREASURY = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
private const val DAILY = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val SAVINGS = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val FRIEND = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"
private const val MODULE = "0x52908400098527886E0F7030069857D2E4169EE7"
private const val GUARD = "0x8617E340B3D01FA5F11F306F4090FD50E238070D"
private const val LAPTOP = "0x27b1fdb04752bbc536007a920d24acb045561c26"

private val safe = SendFrom("Treasury", TREASURY)

private val overview = SafeSettingsUiState.Overview(
    network = "Sepolia",
    safe = safe,
    title = "Safe 1.4.1 · nonce 12",
    signers = "2 of 3 owners must sign",
    owners = listOf(
        SettingsOwner(DAILY, "Daily", heldHere = true),
        SettingsOwner(SAVINGS, "Savings", heldHere = false),
        SettingsOwner(FRIEND, label = null, heldHere = false),
    ),
    modules = emptyList(),
    guard = null,
    checkedAt = "Verified at block 8,412,307",
    changeReason = null,
    enableModuleReason = MODULE_ENABLED_REFUSAL,
    updateReason = "Updating a Safe's version runs a migration contract's code as the Safe, a delegate call, which " +
        "Serotine does not sign. It comes once a migration contract is pinned here.",
)

private val addOwner = SettingsForm(
    kind = SettingsChangeKind.ADD_OWNER,
    title = "Add an owner",
    explanation = "The new owner signs with the others once the change runs. The threshold is how many of them " +
        "must sign each of the Safe's transactions.",
    address = OwnerField(
        text = FRIEND,
        note = "$FRIEND: not in this wallet.",
        isError = false,
        accounts = listOf(AccountChoice("Laptop", LAPTOP)),
    ),
    threshold = ThresholdField(2, 4, "2 of 4 owners must sign"),
    canReview = true,
)

private val review = SafeSettingsUiState.Review(
    network = "Sepolia",
    safe = safe,
    action = SafeTxActionItem(
        title = "Add an owner",
        addresses = listOf(ActionAddress("New owner", FRIEND)),
        notes = listOf("The threshold becomes 2"),
    ),
    nonce = "Safe nonce 12",
    simulation = "The change, run as a verified call from the Safe at block 8,412,307, goes through. The Safe's own " +
        "checks — its owners' signatures and its nonce — and its guard run when its execution is quoted.",
    owners = listOf(AccountChoice("Daily", DAILY)),
    owner = DAILY,
    unlock = UnlockStep(),
)

@Composable
private fun SafeSettingsPreview(
    state: SafeSettingsUiState,
    darkTheme: Boolean = false,
    lightClient: LightClientItem = verifiedStripSample,
) {
    SerotineTheme(darkTheme = darkTheme) {
        SafeSettingsScreen(state = state, lightClient = lightClient, actions = SafeSettingsActions(), onBack = {})
    }
}

@Preview
@Composable
private fun SafeSettingsOverviewPreview() = SafeSettingsPreview(overview)

@Preview
@Composable
private fun SafeSettingsOverviewDarkPreview() = SafeSettingsPreview(overview, darkTheme = true)

@Preview
@Composable
private fun SafeSettingsModulesAndGuardPreview() = SafeSettingsPreview(
    overview.copy(modules = listOf(MODULE), guard = GUARD),
)

@Preview
@Composable
private fun SafeSettingsWatchOnlyPreview() = SafeSettingsPreview(
    overview.copy(
        owners = overview.owners.map { it.copy(heldHere = false) },
        changeReason = "No owner of this Safe has its key on this device: it is watch-only here, so no change of " +
            "its settings can be proposed from it. An owner's recovery phrase, imported, makes it possible.",
    ),
)

@Preview
@Composable
private fun SafeSettingsAddOwnerPreview() = SafeSettingsPreview(SafeSettingsUiState.Edit("Sepolia", safe, addOwner))

@Preview
@Composable
private fun SafeSettingsAddOwnerProposingPreview() = SafeSettingsPreview(
    SafeSettingsUiState.Edit("Sepolia", safe, addOwner.copy(proposing = true, canReview = false)),
)

@Preview
@Composable
private fun SafeSettingsReplaceOwnerPreview() = SafeSettingsPreview(
    SafeSettingsUiState.Edit(
        "Sepolia",
        safe,
        SettingsForm(
            kind = SettingsChangeKind.REPLACE_OWNER,
            title = "Replace an owner",
            explanation = "The new owner takes this owner's place, and the threshold stays: how a Safe moves to a " +
                "new device, or away from a lost one.",
            subject = SettingsOwner(DAILY, "Daily", heldHere = true),
            address = OwnerField("0x1234", "Not an address: 0x and 40 hexadecimal digits.", isError = true, emptyList()),
            warning = "Its key is the only one of this Safe's owners on this device: once the change runs, this device " +
                "can no longer sign for the Safe, which becomes watch-only here.",
        ),
    ),
)

@Preview
@Composable
private fun SafeSettingsRemoveOwnerRefusedPreview() = SafeSettingsPreview(
    SafeSettingsUiState.Edit(
        "Sepolia",
        safe,
        SettingsForm(
            kind = SettingsChangeKind.REMOVE_OWNER,
            title = "Remove an owner",
            explanation = "The owner signs for the Safe no more once the change runs. The threshold can be no more " +
                "than the owners left.",
            subject = SettingsOwner(FRIEND, label = null, heldHere = false),
            threshold = ThresholdField(2, 2, "2 of 2 owners must sign"),
            problem = "It would leave all 2 owners needed to sign: with one of them lost, the rest could never " +
                "reach the threshold again. That needs a recovery module, and Serotine recognises none yet (#54), " +
                "so it does not sign, approve or execute it.",
        ),
    ),
)

@Preview
@Composable
private fun SafeSettingsRemoveGuardPreview() = SafeSettingsPreview(
    SafeSettingsUiState.Edit(
        "Sepolia",
        safe,
        SettingsForm(
            kind = SettingsChangeKind.REMOVE_GUARD,
            title = "Remove the guard",
            explanation = "The guard checks the Safe's transactions no more once the change runs. It checks this " +
                "one too: a guard that refuses it stays.",
            subject = SettingsOwner(GUARD, label = null, heldHere = false),
            warning = GUARD_REMOVED_WARNING,
            canReview = true,
        ),
    ),
)

@Preview
@Composable
private fun SafeSettingsReviewPreview() = SafeSettingsPreview(review)

@Preview
@Composable
private fun SafeSettingsReviewDarkPreview() = SafeSettingsPreview(review, darkTheme = true)

@Preview
@Composable
private fun SafeSettingsReviewQueuedPreview() = SafeSettingsPreview(
    review.copy(
        queued = "1 transaction queued on this device runs before it.",
        changesQueued = "A change of this Safe's settings queued before it runs first. This one is built against " +
            "the owners and modules as they are now: if that one changes them, this one may fail when it is " +
            "executed, which its execution's verified run says before anyone sends it.",
    ),
)

@Preview
@Composable
private fun SafeSettingsReviewNotSavedPreview() = SafeSettingsPreview(
    review.copy(
        error = "Signed, but this device's queue cannot be saved right now, so the proposal is not kept here: " +
            "share it below before you leave this screen, or sign it again in a moment.",
    ),
)

@Preview
@Composable
private fun SafeSettingsReviewAskingToDropPreview() = SafeSettingsPreview(
    review.copy(
        error = "Signed, but this device's queue cannot be saved right now, so the proposal is not kept here: " +
            "share it below before you leave this screen, or sign it again in a moment.",
        askingToDrop = true,
    ),
)

@Preview
@Composable
private fun SafeSettingsReviewCheckingPreview() = SafeSettingsPreview(review.copy(signing = true, checking = true))

@Preview
@Composable
private fun SafeSettingsLoadingPreview() = SafeSettingsPreview(SafeSettingsUiState.Loading("Sepolia"))

@Preview
@Composable
private fun SafeSettingsUnavailablePreview() = SafeSettingsPreview(
    SafeSettingsUiState.Unavailable(
        "Sepolia",
        "The Safe's storage could not be verified this time. Serotine keeps trying.",
    ),
)

/** Every preview above, which SafeSettingsScreenPreviewsTest renders: a new preview goes in here too. */
internal val safeSettingsScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SafeSettingsOverviewPreview() },
    { SafeSettingsOverviewDarkPreview() },
    { SafeSettingsModulesAndGuardPreview() },
    { SafeSettingsWatchOnlyPreview() },
    { SafeSettingsAddOwnerPreview() },
    { SafeSettingsAddOwnerProposingPreview() },
    { SafeSettingsReplaceOwnerPreview() },
    { SafeSettingsRemoveOwnerRefusedPreview() },
    { SafeSettingsRemoveGuardPreview() },
    { SafeSettingsReviewPreview() },
    { SafeSettingsReviewDarkPreview() },
    { SafeSettingsReviewQueuedPreview() },
    { SafeSettingsReviewNotSavedPreview() },
    { SafeSettingsReviewAskingToDropPreview() },
    { SafeSettingsReviewCheckingPreview() },
    { SafeSettingsLoadingPreview() },
    { SafeSettingsUnavailablePreview() },
)
