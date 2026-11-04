package eth.serotine.ui.safeswap

import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.RouteStep
import eth.serotine.ui.components.SafeBalanceChange
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.SwapForm
import eth.serotine.ui.components.UnlockStep

/**
 * Everything the screen that proposes a Safe's swap renders (docs/swaps.md, *A Safe's swap*):
 * the swap composed in Swap's form, with no fee to choose (whoever executes it pays), then the
 * proposal under review — the Safe transactions it takes, decoded here, at their nonces; the
 * minimum and the deadline they fix; the route; the Safe's balance before and after — with the
 * owner held here who signs them. Every leaf is display-ready, amounts in token units
 * throughout. While the review is shown, [Review.unlock] holds the wallet password being typed,
 * which the ViewModel drops as soon as the screen is not shown.
 */
sealed interface SafeSwapUiState {

    /** The network's name ("Sepolia"). */
    val network: String

    /** The Safe, its owners and its assets have not been read yet. */
    data class Loading(override val network: String) : SafeSwapUiState

    /**
     * Nothing can be swapped from this Safe, for the reason [message] gives: no owner of it is held
     * here, it is no Safe (any more), it is gone, what it is cannot be verified right now, or the
     * swap to propose again is not in the queue.
     */
    data class CannotSwap(override val network: String, val message: String) : SafeSwapUiState

    /**
     * The swap [form]. [replacing] says which queued swap it is proposed in place of, when it is one
     * proposed again ([SwapForm.fixed]).
     */
    data class Compose(val form: SwapForm, val replacing: String? = null) : SafeSwapUiState {
        override val network: String
            get() = form.network
    }

    /**
     * The proposal under review: from the Safe ([from]), exactly [sold] of [sell] for at least
     * [minimum] of [buy] ([quoted] by QuoterV2, [quotedAt], less the [tolerance]), along [route]
     * ([routeNote], [priceImpact], [priceHint]) through the router ([spender]), refused by it after
     * [deadline]. [transactions] are the Safe transactions it takes, decoded here, in nonce order:
     * the approval ([approvalNote] why), then the swap; [queued] what runs before them, [replaces]
     * the swap this one takes the place of. The Safe's [balance] of the asset sold before and
     * after, and how the swap ran from the Safe ([simulation]). An owner held here, [owner] (EIP-55)
     * of [owners], signs every one as [unlock] says; [refreshing] while it is proposed again,
     * [signing] while it is signed and queued ([progress] which of them), and [error] what the last
     * attempt came to. Simple mode says no block (docs/simple-ui.md): [simpleQuotedAt],
     * [simpleSimulation] and [simpleError] are [quotedAt], [simulation] and [error] in its words, null
     * where they are the same.
     */
    data class Review(
        override val network: String,
        val from: SendFrom,
        val sell: AssetChoice,
        val sold: String,
        val buy: AssetChoice,
        val quoted: String,
        val quotedAt: String,
        val minimum: String,
        val tolerance: String,
        val route: List<RouteStep>,
        val routeNote: String,
        val priceImpact: String,
        val priceHint: String? = null,
        val spender: SpenderItem,
        val deadline: String,
        val transactions: List<SafeSwapTransaction>,
        val approvalNote: String?,
        val queued: String? = null,
        val replaces: String? = null,
        val balance: SafeBalanceChange,
        val simulation: String,
        val owners: List<AccountChoice>,
        val owner: String,
        val unlock: UnlockStep,
        val refreshing: Boolean = false,
        val signing: Boolean = false,
        /** While [signing], the Safe is still checked on chain: no key is asked for yet, and Back stops it. */
        val checking: Boolean = false,
        val progress: String? = null,
        val error: String? = null,
        val simpleQuotedAt: String? = null,
        val simpleSimulation: String? = null,
        val simpleError: String? = null,
    ) : SafeSwapUiState {

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

    /** Signed and queued: [hash] is the swap's Safe transaction's, which its screen opens by. */
    data class Proposed(override val network: String, val hash: String) : SafeSwapUiState
}

/** One Safe transaction a swap takes: its [nonce] ("Safe nonce 12") and its [action], decoded here. */
data class SafeSwapTransaction(val nonce: String, val action: SafeTxActionItem)
