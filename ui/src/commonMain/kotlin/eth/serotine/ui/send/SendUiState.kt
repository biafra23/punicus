package eth.serotine.ui.send

import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.RecipientItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.TransferForm
import eth.serotine.ui.components.UnlockStep

/**
 * Everything the Send screen renders (docs/wallet-ux.md, screens 7 and 8): the transfer being
 * composed, then the verified quote of it under review, with the unlock that signs it. Every
 * leaf is display-ready. Amounts are token units throughout; a fiat value is only ever a hint
 * beside one (CLAUDE.md, *Data sources*). While the review is shown, [Review.unlock] holds the
 * wallet password being typed, under the screen protections of a screen that takes it, and the
 * ViewModel drops it as soon as the screen is not shown.
 */
sealed interface SendUiState {

    /** The network's name ("Sepolia"), which the screen says it sends on. */
    val network: String

    /** The account and its assets have not been read yet. */
    data class Loading(override val network: String) : SendUiState

    /**
     * Nothing can be sent from this account, for the reason [message] gives: it is watch-only,
     * a Safe (whose transfers are Safe transactions), gone from the wallet, or the account list
     * cannot be read.
     */
    data class CannotSend(override val network: String, val message: String) : SendUiState

    /**
     * Screen 7: the transfer [form] (from, asset, to, amount; docs/wallet-ux.md, screen 7) and the
     * [fee] level chosen, the one thing Send asks beyond the form. [earlier] is the hash of an
     * earlier transaction from the account that may still be on its way, or the id of the one it
     * has queued (lenient mode), when that is why the last review was refused: its status, which
     * tries it again or says where it stands, is offered by the button [earlierLabel].
     */
    data class Compose(
        val form: TransferForm,
        val fee: FeeLevel,
        val earlier: String? = null,
        val earlierLabel: String = "Open the earlier transaction",
    ) : SendUiState {
        override val network: String
            get() = form.network
    }

    /**
     * Screen 8: a verified quote of the transfer. [amount] is exact, in [asset]'s units with its
     * symbol; [recipient] in full with the name it was resolved from; [maxFee] the most the fee
     * can be, in the network's currency, and [feeDetail] what it is made of. [balances] are the
     * verified simulation's before → after, each with its block; [pending] what earlier
     * transfers not mined yet may still take, if any; [simulation] how the transfer ran as a
     * verified call. [unlock] is how it is signed. [refreshing] while the quote is made again,
     * [signing] while it is signed and broadcast, and [notSent] once it was signed but not
     * broadcast: then the same signed bytes are all that can be sent.
     */
    data class Review(
        override val network: String,
        val from: SendFrom,
        val amount: String,
        val asset: AssetChoice,
        val recipient: RecipientItem,
        val maxFee: String,
        val feeDetail: String,
        val balances: List<BalanceChange>,
        val simulation: String,
        val unlock: UnlockStep,
        val pending: String? = null,
        val refreshing: Boolean = false,
        val signing: Boolean = false,
        val notSent: NotSentItem? = null,
    ) : SendUiState {

        /** Something is typed, and nothing else runs or has run: the password may sign. */
        val canSign: Boolean
            get() = idle && unlock.password.text.isNotEmpty()

        /** The biometric prompt may be offered now. */
        val canSignWithBiometrics: Boolean
            get() = idle && unlock.biometrics

        /** Neither a refresh nor a signature runs, and nothing was signed yet. */
        val idle: Boolean
            get() = !refreshing && !signing && notSent == null
    }

    /**
     * Screen 8 for a lenient draft (docs/lenient-mode.md, *The draft's review*; owner rulings,
     * 2026-11-06): the transfer drafted on the state the light client verified last, while it
     * catches up, to be signed and queued, never sent from here. [preparedOn] is the line on top
     * ("Prepared on data from 12 min ago. It's checked on chain again before it's sent."). Each
     * figure says the block it was last verified at, which Detailed view shows, and its age, which
     * simple mode shows: the [balances] (before, and after "if nothing changed"; [BalanceChange.age]),
     * the fee ([feeDetail], [feeAge]) and the nonce ([nonce], [nonceAge]). [notChecked] stands in for
     * the verified simulation. No figure has a value in the display currency: a price is never
     * shown last known. [maxFee] is the most the fee can be, at the pinned gas ceiling. [unlock]
     * signs it with no deadline; [signing] while it is signed and queued.
     */
    data class DraftReview(
        override val network: String,
        val from: SendFrom,
        val amount: String,
        val asset: AssetChoice,
        val recipient: RecipientItem,
        val preparedOn: String,
        val maxFee: String,
        val feeDetail: String,
        val feeAge: String,
        val nonce: String,
        val nonceAge: String,
        val balances: List<BalanceChange>,
        val unlock: UnlockStep,
        val pending: String? = null,
        val signing: Boolean = false,
        val notChecked: String = NOT_CHECKED_YET,
    ) : SendUiState {

        /** Something is typed, and no signature runs: the password may sign. */
        val canSign: Boolean
            get() = !signing && unlock.password.text.isNotEmpty()

        /** The biometric prompt may be offered now. */
        val canSignWithBiometrics: Boolean
            get() = !signing && unlock.biometrics
    }

    /** Broadcast: [hash] is the transaction's, which the status screen follows. */
    data class Sent(override val network: String, val hash: String) : SendUiState

    /**
     * Signed and queued in the outbox (lenient mode): [id] names it, which the status screen
     * follows; nothing was broadcast.
     */
    data class Queued(override val network: String, val id: String) : SendUiState
}

/** What a draft's review says in place of the verified simulation (docs/lenient-mode.md, *The draft's review*). */
const val NOT_CHECKED_YET = "Not checked yet: it runs on chain before it's sent."
