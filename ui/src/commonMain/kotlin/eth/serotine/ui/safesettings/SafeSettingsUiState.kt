package eth.serotine.ui.safesettings

import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.ShareItem
import eth.serotine.ui.components.UnlockStep

/**
 * Everything the Safe settings screen renders (docs/wallet-ux.md, screen 28): the Safe's settings
 * as its verified storage holds them, with what can be changed; the change chosen, in its form;
 * then the proposal of it under review — the Safe transaction it is, decoded here, its nonce, and
 * whether its call was simulated — with the owner held here who signs it. Every leaf is
 * display-ready. While the review is shown, [Review.unlock] holds the wallet password being typed,
 * under the screen protections of a screen that takes it, and the ViewModel drops it as soon as
 * the screen is not shown.
 */
sealed interface SafeSettingsUiState {

    /** The network's name ("Sepolia"). */
    val network: String

    /** The Safe has not been read yet. */
    data class Loading(override val network: String) : SafeSettingsUiState

    /**
     * The settings cannot be shown, for the reason [message] gives: the account is gone or no Safe
     * (any more), or the Safe cannot be verified right now.
     */
    data class Unavailable(override val network: String, val message: String) : SafeSettingsUiState

    /**
     * The Safe ([safe]) as verified at [checkedAt]: its [title] ("Safe 1.4.1 · nonce 12"), who must
     * sign ([signers], "2 of 3 owners must sign"), its [owners], [modules], [guard] and
     * [moduleGuard] (null: none). Each change is shown whether it can be proposed or not: while
     * [changeReason] is set (no owner held here) every one is greyed out, and a tap on it says
     * that reason (owner ruling, 2026-10-18, #55); so are Remove while [removeReason] is set and
     * Change the threshold while [thresholdReason] is (a Safe of one owner). [enableModuleReason]
     * and [updateReason] say why enabling a module and updating the Safe's version are never
     * offered here. [simpleCheckedAt] is [checkedAt] with no block, for simple mode, or null.
     */
    data class Overview(
        override val network: String,
        val safe: SendFrom,
        val title: String,
        val signers: String,
        val owners: List<SettingsOwner>,
        val modules: List<String>,
        val guard: String?,
        val checkedAt: String,
        val changeReason: String?,
        val enableModuleReason: String,
        val updateReason: String,
        val moduleGuard: String? = null,
        val removeReason: String? = null,
        val thresholdReason: String? = null,
        val simpleCheckedAt: String? = null,
    ) : SafeSettingsUiState {

        /** Whether a change can be proposed: an owner held here signs it. */
        val canChange: Boolean
            get() = changeReason == null

        /** Why an owner cannot be removed, or null when one can. */
        val removeBlocked: String?
            get() = changeReason ?: removeReason

        /** Why the threshold cannot be changed, or null when it can. */
        val thresholdBlocked: String?
            get() = changeReason ?: thresholdReason
    }

    /** The change [form] fills in, of the Safe [safe]. */
    data class Edit(override val network: String, val safe: SendFrom, val form: SettingsForm) : SafeSettingsUiState

    /**
     * The proposal under review: of the Safe ([safe]), the Safe transaction it is ([action],
     * decoded here) at the Safe's [nonce], after the ones queued on this device before it
     * ([queued], null when it is next; [changesQueued] when one of those changes the settings
     * too), and how its call ran ([simulation]). An owner held here, [owner] (EIP-55) of [owners],
     * signs it as [unlock] says; [warning] when that owner is the one the change removes or
     * replaces. [refreshing] while it is proposed again, [signing] while it is signed and queued,
     * and [error] what the last attempt came to. [share] is a signed request the queue could not
     * keep, to be shared from here; [askingToDrop] while Back asks whether to leave it behind.
     * Simple mode says no block (docs/simple-ui.md): [simpleSimulation] and [simpleError] are
     * [simulation] and [error] in its words, null where Detailed view's own text serves. A run is
     * said in its words even when it names no block: it "should go through". A run kept from before a
     * re-proposal that failed says it is from then, since the blocks that order the two are under *Details*.
     */
    data class Review(
        override val network: String,
        val safe: SendFrom,
        val action: SafeTxActionItem,
        val nonce: String,
        val simulation: String,
        val owners: List<AccountChoice>,
        val owner: String,
        val unlock: UnlockStep,
        val queued: String? = null,
        val changesQueued: String? = null,
        val warning: String? = null,
        val refreshing: Boolean = false,
        val signing: Boolean = false,
        /** While [signing], the Safe is still checked on chain: no key is asked for yet, and Back stops it. */
        val checking: Boolean = false,
        val error: String? = null,
        val share: ShareItem? = null,
        val askingToDrop: Boolean = false,
        val simpleSimulation: String? = null,
        val simpleError: String? = null,
    ) : SafeSettingsUiState {

        /** Something is typed and nothing runs: the password may sign. */
        val canSign: Boolean
            get() = idle && unlock.password.text.isNotEmpty()

        /** The biometric prompt may be offered now. */
        val canSignWithBiometrics: Boolean
            get() = idle && unlock.biometrics

        /** Neither a refresh nor a signature runs. */
        val idle: Boolean
            get() = !refreshing && !signing
    }

    /** Signed and queued: [hash] is the Safe transaction's, which its screen opens by. */
    data class Proposed(override val network: String, val hash: String) : SafeSettingsUiState
}

/**
 * One owner of the Safe: its EIP-55 [address], its name in the wallet ([label], null: not in it),
 * and whether its key is held here ([heldHere]).
 */
data class SettingsOwner(val address: String, val label: String?, val heldHere: Boolean)

/** The changes the settings screen proposes. */
enum class SettingsChangeKind {
    ADD_OWNER,
    REPLACE_OWNER,
    REMOVE_OWNER,
    CHANGE_THRESHOLD,
    DISABLE_MODULE,
    REMOVE_GUARD,
}

/**
 * The form of one change ([kind]), display-ready: its [title] and what it does ([explanation]);
 * the owner or module it is about ([subject], null for a new owner or the threshold); the new
 * owner's [address] field, and the [threshold] it leaves, where the change takes them; [warning]
 * what to know before it is proposed; [problem], why the change as filled in cannot be made to
 * the Safe as verified now; [proposing] while it is proposed, and [error] what that came to;
 * [simpleError] that with no block, for simple mode, null where it is [error] itself.
 */
data class SettingsForm(
    val kind: SettingsChangeKind,
    val title: String,
    val explanation: String,
    val subject: SettingsOwner? = null,
    val address: OwnerField? = null,
    val threshold: ThresholdField? = null,
    val warning: String? = null,
    val problem: String? = null,
    val proposing: Boolean = false,
    val error: String? = null,
    val canReview: Boolean = false,
    val simpleError: String? = null,
)

/**
 * The new owner's address as typed ([text]), what it is ([note]; an error when [isError]), and the
 * wallet's accounts on the Safe's network that are not owners yet, to pick from ([accounts]).
 */
data class OwnerField(
    val text: String,
    val note: String?,
    val isError: Boolean,
    val accounts: List<AccountChoice>,
)

/**
 * The threshold the change leaves: [value] of [owners] owners, from 1 to [owners], and what that
 * shape means ([shape]: "2 of 3 owners must sign").
 */
data class ThresholdField(val value: Int, val owners: Int, val shape: String) {
    val canDecrease: Boolean
        get() = value > 1

    val canIncrease: Boolean
        get() = value < owners
}
