package eth.serotine.ui.coworder

import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.UnlockStep

/**
 * Everything a CoW order's status renders (docs/swaps.md, *Proving an order's end*; screen 9 for an
 * order): the order, its end as the chain proves it, CoW's own word on it, labelled as CoW's, and
 * what can be done: cancel it on chain while it may fill, swap the same on Uniswap once it is
 * proven not to have. Every leaf is display-ready. While the cancellation is reviewed it holds the
 * wallet password being typed, which the ViewModel drops as soon as the screen is not shown.
 */
sealed interface CowOrderUiState {

    /** The network's name ("Sepolia"), which the screen says the order is on. */
    val network: String

    /** The order has not been read yet. */
    data class Loading(override val network: String) : CowOrderUiState

    /** No order can be shown, for the reason [message] gives. */
    data class Missing(override val network: String, val message: String) : CowOrderUiState

    /**
     * The order: [from] the account that signed it, [sold] what it sells and [bought] what it buys
     * (at least its minimum; what it bought once its fill is proven), each with its token's symbol
     * and icon.
     *
     * - [state] and [end]: where it stands as the chain proves it, from the account's verified logs;
     *   [held] what it holds back meanwhile.
     * - [cow]: CoW's word on its off-chain life, labelled as CoW's and never a fill; null when not
     *   asked (an order whose end is proven); [placement] how CoW answered when it was sent.
     * - [validUntil], the [settlement] contract that settles it, and its [uid].
     * - [canCancel]: "Cancel on chain" applies; [cancelReason] why it cannot be done from here (the
     *   account's key is not on this device), drawn greyed out with this said on a tap, else null;
     *   [earlier] a transaction that holds it back (its
     *   cancellation sent, or one that may still be on its way), with its status;
     *   [uniswapAgain]: proven not to have swapped, the same swap on Uniswap, which Swap opens with.
     * - [error]: why the last cancellation could not be quoted; [quoting] while one is.
     * - Simple mode says no block (docs/simple-ui.md): [simpleEnd] and [simpleError] are [end] and
     *   [error] in its words, null where they are the same.
     */
    data class Shown(
        override val network: String,
        val from: SendFrom,
        val sold: String,
        val soldSymbol: String,
        val soldIcon: String?,
        val bought: String,
        val boughtSymbol: String,
        val boughtIcon: String?,
        val state: OrderState,
        val end: String,
        val held: String? = null,
        val cow: String? = null,
        val placement: String,
        val validUntil: String,
        val settlement: SpenderItem,
        val uid: String,
        val canCancel: Boolean = false,
        val cancelReason: String? = null,
        val earlier: EarlierTransaction? = null,
        val uniswapAgain: SwapAgain? = null,
        val error: String? = null,
        val quoting: Boolean = false,
        val simpleEnd: String? = null,
        val simpleError: String? = null,
    ) : CowOrderUiState

    /**
     * The cancellation on chain, under review: `invalidateOrder` of the order's [uid] on the
     * [settlement] contract, sent from [from]; the most the fee can be and what it is made of, the
     * verified [balance] before → after, and the verified run ([simulation]); [simpleSimulation] that
     * run with no block, for simple mode, which keeps the fee's make-up and the blocks behind *Details*.
     */
    data class CancelReview(
        override val network: String,
        val from: SendFrom,
        val uid: String,
        val settlement: SpenderItem,
        val maxFee: String,
        val feeDetail: String,
        val balance: BalanceChange,
        val simulation: String,
        val unlock: UnlockStep,
        val refreshing: Boolean = false,
        val signing: Boolean = false,
        val notSent: NotSentItem? = null,
        val simpleSimulation: String = simulation,
    ) : CowOrderUiState {
        /** Neither a refresh nor a signature runs, and nothing was signed yet. */
        val idle: Boolean
            get() = !refreshing && !signing && notSent == null
    }

    /** The cancellation is broadcast: [hash] is its transaction's, whose status follows it. */
    data class CancelSent(override val network: String, val hash: String) : CowOrderUiState
}

/**
 * The same swap again, as Swap opens with it, on Uniswap: from the account at [from], selling the
 * token at [sell] for the one at [buy] (null: the network's currency), [amount] as text in the sold
 * token's units. Addresses in lower case: route identifiers, never display text.
 */
data class SwapAgain(val from: String, val sell: String, val buy: String?, val amount: String)

/**
 * A transaction of the account's whose status the order's screen offers: [hash], what to say of it
 * ([note], when anything beyond the error above), and what to open it as ([label]).
 */
data class EarlierTransaction(val hash: String, val note: String?, val label: String)

/** Where an order stands, as the chain proves it, as its status marks it. */
enum class OrderState {
    /** It may still fill: its end is not proven yet. */
    LIVE,

    /** Its fill is proven: its `Trade` is in the account's verified logs. */
    FILLED,

    /** Proven never to have filled, by a finalized block past its validity. */
    NOT_FILLED,

    /** Cancelled on chain, proven never to have filled. */
    CANCELLED,

    /** Its end can never be proven here; the balance shows what happened. */
    NOT_PROVABLE,
}
