package eth.serotine.ui.swap

import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.RouteStep
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.SwapForm
import eth.serotine.ui.components.UnlockStep

/**
 * Everything the Swap screen renders (docs/swaps.md; screens 22 and 23): the swap being composed,
 * on the venue the user chose, then the review of the approval it needs first, if any, and of the
 * swap on Uniswap or the order on CoW, each with the unlock that signs it. Every leaf is
 * display-ready. Amounts are token units throughout; a fiat value is only ever a hint beside one,
 * and no amount comes from a price (CLAUDE.md, *Data sources*). While a review is shown it holds
 * the wallet password being typed, which the ViewModel drops as soon as the screen is not shown.
 */
sealed interface SwapUiState {

    /** The network's name ("Sepolia"), which the screen says it swaps on. */
    val network: String

    /** The account and its assets have not been read yet. */
    data class Loading(override val network: String) : SwapUiState

    /**
     * Nothing can be swapped from this account, for the reason [message] gives: it is watch-only,
     * a Safe (whose swaps are Safe transactions, proposed from its own Swap), gone from the wallet, or the
     * account list cannot be read.
     */
    data class CannotSwap(override val network: String, val message: String) : SwapUiState

    /**
     * Screen 22: the swap [form], the [fee] level and the [venue]. [earlier] is the hash of a
     * transaction of the account's that holds the swap back, with its status offered: one that may
     * still be on its way, an approval or an earlier swap of the token not mined yet.
     * [uniswapInstead]: CoW could not quote the swap ([SwapForm.error] says why), and the same
     * swap on Uniswap is offered, one tap away. [liveOrder]: the UID of an earlier CoW order of the
     * token that may still fill, whose status is offered.
     */
    data class Compose(
        val form: SwapForm,
        val fee: FeeLevel,
        val earlier: EarlierItem? = null,
        val venue: VenueItem = VenueItem(SwapVenue.UNISWAP),
        val uniswapInstead: Boolean = false,
        val liveOrder: String? = null,
    ) : SwapUiState {
        override val network: String
            get() = form.network
    }

    /**
     * The approval the swap needs before it (docs/swaps.md, *What both venues share*): the router
     * may take only [allowance] of the token, and this transaction lets it take [amount] (zero
     * first when [resetNote] says the token needs that), its own transaction, quoted, signed and
     * sent as any is. The swap is quoted once a block has it.
     */
    data class ApprovalReview(
        override val network: String,
        val from: SendFrom,
        val token: AssetChoice,
        val amount: String,
        val allowance: String,
        val spender: SpenderItem,
        val resetNote: String?,
        val maxFee: String,
        val feeDetail: String,
        val balance: BalanceChange,
        val simulation: String,
        override val unlock: UnlockStep,
        override val refreshing: Boolean = false,
        override val signing: Boolean = false,
        override val notSent: NotSentItem? = null,
    ) : SwapUiState, Signable

    /**
     * Screen 23: a verified quote of the swap. [sold] exact in [sell]'s units; [receive] what the
     * exact transaction returned, run as a verified call ([ranAt]); [minimum] the least it may
     * return, at the [tolerance]; the [route] through the factory's pools; the router it goes
     * through ([spender]), and for a token sold, [allowanceNote] saying the allowance it already
     * has covers the amount; [deadline] when the router refuses it; the most the fee can be and what
     * it is made of; the verified [balances] before → after. [pending] what earlier transactions
     * not mined yet may still take. [netNote] when the route was chosen without its fee.
     * [priceImpact] says how far the quote falls below the pools' spot prices, or that it is not
     * known; [priceHint] what the prices the wallet shows say of the quote (display only).
     */
    data class SwapReview(
        override val network: String,
        val from: SendFrom,
        val sell: AssetChoice,
        val sold: String,
        val buy: AssetChoice,
        val receive: String,
        val ranAt: String,
        val minimum: String,
        val tolerance: String,
        val route: List<RouteStep>,
        val routeNote: String,
        val priceImpact: String,
        val priceHint: String? = null,
        val spender: SpenderItem,
        val allowanceNote: String? = null,
        val deadline: String,
        val maxFee: String,
        val feeDetail: String,
        val balances: List<BalanceChange>,
        val pending: String? = null,
        val netNote: String? = null,
        override val unlock: UnlockStep,
        override val refreshing: Boolean = false,
        override val signing: Boolean = false,
        override val notSent: NotSentItem? = null,
    ) : SwapUiState, Signable

    /**
     * ETH for WETH, or back (docs/swaps.md, *Wrapping ETH*): no swap but WETH9's own `deposit` or
     * `withdraw`, one for one, on neither venue. [title] ("Review wrap") and [signLabel] ("Sign
     * and wrap") name it; [sold] exact in [sell]'s units, [receive] as much in [buy]'s;
     * [explanation] says why it is no swap, [ranAt] that the exact transaction went through, run
     * as a verified call; the WETH9 it goes through ([contract]); the most the fee can be and what
     * it is made of; the verified [balances] before → after, and [pending] what earlier
     * transactions not mined yet, or CoW orders, may still take.
     */
    data class WrapReview(
        override val network: String,
        val title: String,
        val signLabel: String,
        val from: SendFrom,
        val sell: AssetChoice,
        val sold: String,
        val buy: AssetChoice,
        val receive: String,
        val explanation: String,
        val ranAt: String,
        val contract: SpenderItem,
        val maxFee: String,
        val feeDetail: String,
        val balances: List<BalanceChange>,
        val pending: String? = null,
        override val unlock: UnlockStep,
        override val refreshing: Boolean = false,
        override val signing: Boolean = false,
        override val notSent: NotSentItem? = null,
    ) : SwapUiState, Signable

    /**
     * Screen 23 for an order on CoW (docs/swaps.md, *The user's choice of venue*): [sold] exact in
     * [sell]'s units; CoW's [estimate] of what it buys, not verified ([estimateNote]), beside the
     * verified Uniswap net ([uniswapNet], [uniswapNote]), and which gives more ([comparison];
     * [uniswapGivesMore] by CoW's own estimate, when the same swap on Uniswap is offered, one tap
     * away); the [minimum] the order signs and how it is set
     * ([minimumNote]); until when it may fill ([validUntil]); the contract that settles it
     * ([settlement]); the verified [balances]; [pending] what earlier transactions not mined yet may
     * still take; and what CoW learns of it ([disclosure]). An order costs no network fee here.
     */
    data class CowReview(
        override val network: String,
        val from: SendFrom,
        val sell: AssetChoice,
        val sold: String,
        val buy: AssetChoice,
        val estimate: String,
        val estimateNote: String,
        val uniswapNet: String,
        val uniswapNote: String,
        val comparison: String,
        val uniswapGivesMore: Boolean,
        val minimum: String,
        val minimumNote: String,
        val validUntil: String,
        val settlement: SpenderItem,
        val balances: List<BalanceChange>,
        val pending: String? = null,
        val disclosure: String,
        override val unlock: UnlockStep,
        override val refreshing: Boolean = false,
        override val signing: Boolean = false,
    ) : SwapUiState, Signable {
        // Nothing is broadcast here: CoW's answer, whatever it is, is the order placed.
        override val notSent: NotSentItem?
            get() = null
    }

    /** Broadcast: [hash] is the swap's, which the status screen follows. */
    data class Sent(override val network: String, val hash: String) : SwapUiState

    /**
     * A CoW order signed and handed to CoW: [title] and [message] say how CoW answered and what
     * follows; [uid] is the order's UID.
     */
    data class OrderPlaced(override val network: String, val title: String, val message: String, val uid: String) :
        SwapUiState
}

/**
 * The venue a swap goes to on screen 22 ([chosen]): Uniswap, verified on chain, or CoW Protocol,
 * through CoW's servers. [cowUnavailable] says why CoW is not offered for this swap, shown greyed
 * out and said on a tap; null while it is offered.
 */
data class VenueItem(val chosen: SwapVenue, val cowUnavailable: String? = null)

/** What a review that signs has: its unlock and where its signature stands. */
sealed interface Signable {
    val unlock: UnlockStep
    val refreshing: Boolean
    val signing: Boolean
    val notSent: NotSentItem?

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
 * A transaction of the account's that holds the swap back: [hash], what to open it as
 * ([label], "Open the approval"), and whether it is only waiting for a block ([waiting]) or may
 * still be on its way, to be tried again from its status.
 */
data class EarlierItem(val hash: String, val label: String, val waiting: Boolean)
