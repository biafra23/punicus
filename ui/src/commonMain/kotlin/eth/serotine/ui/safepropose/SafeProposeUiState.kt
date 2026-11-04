package eth.serotine.ui.safepropose

import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.RecipientItem
import eth.serotine.ui.components.SafeBalanceChange
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.ShareItem
import eth.serotine.ui.components.TransferForm
import eth.serotine.ui.components.UnlockStep

/**
 * Everything the screen that proposes a transfer from a Safe renders (docs/wallet-ux.md, screen 7
 * for a Safe, and 16): the transfer composed in Send's form, then the proposal of it under review —
 * the Safe transaction it is, decoded here, its nonce, the Safe's balance before, queued and after,
 * and whether its call was simulated — with the owner held here who signs it. Every leaf is
 * display-ready, amounts in token units throughout. While the review is shown, [Review.unlock]
 * holds the wallet password being typed, under the screen protections of a screen that takes it,
 * and the ViewModel drops it as soon as the screen is not shown.
 */
sealed interface SafeProposeUiState {

    /** The network's name ("Sepolia"). */
    val network: String

    /** The Safe, its owners and its assets have not been read yet. */
    data class Loading(override val network: String) : SafeProposeUiState

    /**
     * Nothing can be proposed from this account, for the reason [message] gives: no owner of it is
     * held here, it is no Safe (any more), it is gone, or what it is cannot be verified right now.
     */
    data class CannotPropose(override val network: String, val message: String) : SafeProposeUiState

    /** Screen 7 for a Safe: the transfer [form], with no fee to choose (the account that executes it pays). */
    data class Compose(val form: TransferForm) : SafeProposeUiState {
        override val network: String
            get() = form.network
    }

    /**
     * The proposal under review: from the Safe ([from]), [amount] of [asset] to [recipient], the
     * Safe transaction it is ([action], decoded here) at the Safe's [nonce], after the ones queued
     * on this device before it ([queued], null when it is next); the Safe's [balance] before, what
     * those queued before it move, and after; and how its call ran ([simulation]). An owner held
     * here, [owner] (EIP-55) of [owners], signs it as [unlock] says; [refreshing] while it is
     * proposed again, [signing] while it is signed and queued, and [error] what the last attempt
     * came to. [share] is a signed request the queue could not keep, to be shared from here;
     * [askingToDrop] while Back asks whether to leave it behind. Simple mode says no block
     * (docs/simple-ui.md): [simpleSimulation] and [simpleError] are [simulation] and [error] in its
     * words, null where they are the same.
     */
    data class Review(
        override val network: String,
        val from: SendFrom,
        val action: SafeTxActionItem,
        val amount: String,
        val asset: AssetChoice,
        val recipient: RecipientItem,
        val nonce: String,
        val balance: SafeBalanceChange,
        val simulation: String,
        val owners: List<AccountChoice>,
        val owner: String,
        val unlock: UnlockStep,
        val queued: String? = null,
        val refreshing: Boolean = false,
        val signing: Boolean = false,
        /** While [signing], the Safe is still checked on chain: no key is asked for yet, and Back stops it. */
        val checking: Boolean = false,
        val error: String? = null,
        val share: ShareItem? = null,
        val askingToDrop: Boolean = false,
        val simpleSimulation: String? = null,
        val simpleError: String? = null,
    ) : SafeProposeUiState {

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
    data class Proposed(override val network: String, val hash: String) : SafeProposeUiState
}
