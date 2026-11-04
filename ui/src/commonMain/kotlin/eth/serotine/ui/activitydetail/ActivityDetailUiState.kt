package eth.serotine.ui.activitydetail

import eth.serotine.ui.activity.ActivityState

/**
 * Everything the detail of one row of an account's Activity renders (docs/wallet-ux.md, screen 26):
 * a transaction this device sent, a token's transfer or a Safe's event, with every address and
 * hash it concerns in full, to copy. Display-ready.
 */
sealed interface ActivityDetailUiState {

    /** The account's name, or its short address while it is not in the wallet. */
    val title: String

    /** Nothing read yet, or the part of the history it is in not checked yet. */
    data class Loading(override val title: String) : ActivityDetailUiState

    /**
     * What it did ([headline], as its row says it, with the blockie of [about], an EIP-55 address)
     * and how it ended ([status] and its [state]); then its [fields], each address and hash in full.
     * [note] when something about it needs saying beside its status. [addToken] is the EIP-55
     * contract of an ERC-20 transfer the wallet does not list, which Add token opens Add token by
     * address with (screen 20); null when there is none to add. [opensStatus] is the hash of a
     * transaction sent from this device, whose status (screen 9) Status opens; null for an event.
     * [simpleStatus] is [status] in simple mode's words, with no block ("Confirmed"); null where that
     * is [status] itself. [amountsHidden] while the user hides amounts (docs/wallet-ux.md, *Hiding
     * amounts*): the eye in the top bar is closed, and every amount here already reads "••••".
     */
    data class Shown(
        override val title: String,
        val headline: String,
        val about: String,
        val status: String,
        val state: ActivityState,
        val fields: List<DetailField>,
        val note: String? = null,
        val addToken: String? = null,
        val opensStatus: String? = null,
        val simpleStatus: String? = null,
        val amountsHidden: Boolean = false,
    ) : ActivityDetailUiState

    /** It cannot be shown, for the reason [message] gives. */
    data class Unavailable(override val title: String, val message: String) : ActivityDetailUiState
}

/**
 * One line of a detail: what it is ([label]: "To", "Transaction hash") and its [value], in full.
 * An [DetailKind.ADDRESS] is an EIP-55 address, drawn with its blockie and the wallet's [name] for
 * it if it has one; a [DetailKind.HASH] is `0x` and 64 hex digits. Both are copied as [value].
 */
data class DetailField(
    val label: String,
    val value: String,
    val kind: DetailKind = DetailKind.TEXT,
    val name: String? = null,
)

/** What a [DetailField]'s value is, which says how it is drawn and whether it can be copied. */
enum class DetailKind {
    /** Words or a number, not copied. */
    TEXT,

    /** An EIP-55 address, with its blockie and Copy. */
    ADDRESS,

    /** A transaction's or a message's hash, with Copy. */
    HASH,

    /** A block number: drawn as [TEXT], and kept behind *Details* in simple mode (docs/simple-ui.md). */
    BLOCK,
}
