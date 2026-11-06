package eth.serotine.ui.handoff

import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.RecipientItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.TransferForm

/**
 * Everything Send from another wallet renders (issue #255; docs/wallet-ux.md, screen 7H): the
 * transfer composed as on Send, what is handed to the other wallet, then what the chain shows of
 * it. Serotine signs nothing here: the other wallet signs and sends, and nothing comes back through
 * the link, so the outcome is read from verified blocks alone. Every leaf is display-ready.
 */
sealed interface HandoffUiState {

    /** The network's name ("Sepolia"), which the screen says the transfer is on. */
    val network: String

    /** The account and its assets have not been read yet. */
    data class Loading(override val network: String) : HandoffUiState

    /**
     * Nothing can be handed over from this account, for the reason [message] gives: it is a Safe,
     * its key is held here (Send signs for it), it is gone, no wallet app takes payment links, or
     * the account list cannot be read.
     */
    data class Cannot(override val network: String, val message: String) : HandoffUiState

    /** The transfer [form] (from, asset, to, amount), as Send's compose step has it. */
    data class Compose(val form: TransferForm) : HandoffUiState {
        override val network: String
            get() = form.network
    }

    /**
     * What is handed over: [amount] of [asset] from [from] to [recipient] on [network], with
     * [notes] the user reads before the other wallet opens (that it signs, what its provider
     * learns, that nothing comes back), and [limit] what a wallet is known not to open. [opening]
     * while the link is handed over, [error] why the last attempt handed nothing over.
     */
    data class Review(
        override val network: String,
        val from: SendFrom,
        val amount: String,
        val asset: AssetChoice,
        val recipient: RecipientItem,
        val notes: List<String>,
        val limit: String? = null,
        val opening: Boolean = false,
        val error: String? = null,
    ) : HandoffUiState

    /**
     * Handed over; the chain is being watched for it. [summary] says what was handed over and from
     * which account the other wallet must send; [progress] how far the blocks were checked, and
     * [problem] why the next one cannot be checked now, when that is so.
     */
    data class Waiting(
        override val network: String,
        val from: SendFrom,
        val summary: String,
        val progress: String,
        val problem: String? = null,
    ) : HandoffUiState

    /**
     * What the chain shows: [title] and [message] for the [kind] of outcome, the transaction's
     * [hash] where one was found, and the [block] it is in.
     */
    data class Outcome(
        override val network: String,
        val kind: OutcomeKind,
        val title: String,
        val message: String,
        val hash: String? = null,
        val block: String? = null,
    ) : HandoffUiState
}

/** How a handoff ended, as the outcome screen marks it. */
enum class OutcomeKind {
    /** The transfer handed over is on chain and succeeded. */
    SENT,

    /** The transfer is on chain, and reverted. */
    FAILED,

    /** A near transfer is on chain: another amount, or from another account. */
    NOT_THE_ONE,

    /** No such transfer was seen in the window. */
    NOT_SEEN,
}
