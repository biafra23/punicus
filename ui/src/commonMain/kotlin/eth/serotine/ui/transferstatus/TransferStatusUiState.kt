package eth.serotine.ui.transferstatus

import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.NotSentItem

/**
 * Everything the transfer status screen renders (docs/wallet-ux.md, screen 9): what was sent,
 * and the steps Signed → Broadcast → Included → Finalized, each with its block once it has one.
 * Display-ready; every step past Broadcast is what the verified receipt says.
 */
sealed interface TransferStatusUiState {

    /** The network's name ("Sepolia"). */
    val network: String

    /** Nothing read yet. */
    data class Loading(override val network: String) : TransferStatusUiState

    /**
     * The transaction [hash]: what was sent ([sent] for a transfer, [safeTx] for a Safe
     * transaction's approval or execution, [deployment] for a Safe's deployment, [approval] for a
     * token approval, [swap] for a swap, [cancellation] for a CoW order's cancellation on chain; all
     * null when this device has no record of it), its
     * [steps] in order, and [note], what to know about where it stands (a failure, or that it
     * cannot be verified right now), or null. [finished] once its
     * block is finalized. [notSent] while it is recorded as maybe sent (no peer answered its
     * broadcast) and no block has it, with Try again, which sends the same signed bytes
     * ([retrying] while it does); or what such a retry came to, once there is nothing to retry.
     */
    data class Shown(
        override val network: String,
        val hash: String,
        val sent: SentSummary?,
        val steps: List<StatusStep>,
        val note: String? = null,
        val finished: Boolean = false,
        val safeTx: SafeTxSummary? = null,
        val deployment: DeploymentSummary? = null,
        val approval: ApprovalSummary? = null,
        val swap: SwapSummary? = null,
        val cancellation: CancellationSummary? = null,
        val notSent: NotSentItem? = null,
        val retrying: Boolean = false,
    ) : TransferStatusUiState

    /**
     * A transfer signed in lenient mode and waiting in the outbox under [id] (docs/lenient-mode.md,
     * *The outbox*; owner rulings, 2026-11-06): what it sends ([sent]), and its [steps], a step
     * before Signed saying where it stands: queued, waiting for the network to catch up or being
     * checked, "Sending…", past its hour, or in conflict.
     *
     * - [question] past its hour without the user's tap (decision 4): "Still send this? You signed
     *   it 2 h ago.", answered by Send now ([confirming] while that is saved).
     * - [again] what Sign again (after a conflict) or Edit (otherwise) opens Send with, once it is
     *   discarded; [againLabel] which of the two it is and [againNote] what it does.
     * - [busy] why Discard and [again] wait, greyed out: its check and broadcast run ("It's being
     *   checked and sent."); null while they can be done. [discarding] while a discard runs, which
     *   waits for a check under way.
     * - [error] a change that could not be saved, null otherwise.
     */
    data class Queued(
        override val network: String,
        val id: String,
        val sent: SentSummary?,
        val steps: List<StatusStep>,
        val again: SendAgain?,
        val againLabel: String,
        val againNote: String,
        val question: String? = null,
        val confirming: Boolean = false,
        val busy: String? = null,
        val discarding: Boolean = false,
        val error: String? = null,
    ) : TransferStatusUiState

    /**
     * A queued transfer no longer queued: the user discarded it here ([message]: its signature was
     * deleted, and nothing was sent), or, when the discard came, it was gone already (sent meanwhile,
     * which its record shows once read, or discarded). [reopen], only for a transfer this discard
     * deleted after Sign again or Edit, is what Send opens with in its place.
     */
    data class Discarded(
        override val network: String,
        val message: String,
        val reopen: SendAgain? = null,
    ) : TransferStatusUiState
}

/**
 * What Send opens with for a queued transfer signed again (Sign again, Edit): the account it is from
 * on the network of [chainId] ([from], an address), the token's contract [asset] (null: the network's
 * currency), the recipient [to] (an address), the [amount] in the asset's units as text, and the
 * [fee] level. Identifiers in lower case, as a route carries them.
 */
data class SendAgain(
    val chainId: Long,
    val from: String,
    val asset: String?,
    val to: String,
    val amount: String,
    val fee: FeeLevel,
)

/**
 * What was sent, from this device's record: the [amount] with its symbol, the asset's [symbol] and
 * bundled [icon] (null: a monogram), and the EIP-55 [to] and [from] addresses.
 */
data class SentSummary(
    val amount: String,
    val symbol: String,
    val icon: String?,
    val to: String,
    val from: String,
)

/**
 * What an approval or an execution of a Safe transaction did, from this device's record: [title]
 * ("Approval of a Safe transaction"), the EIP-55 [safe] it is of and its [nonce] ("Safe nonce 4"),
 * and the EIP-55 [from], the account that sent it.
 */
data class SafeTxSummary(val title: String, val safe: String, val nonce: String, val from: String)

/**
 * What a Safe's deployment created, from this device's record: [title] ("Deployment of a Safe"),
 * the EIP-55 [safe] address with its [signers] badge ("2/3"), its [shape] ("2 of 3 owners · Safe
 * 1.4.1"), the EIP-55 [from], the account that paid, and [listing], where the Safe's listing in
 * the wallet stands — [listingFailed] when it was not, and will not be, listed from here.
 */
data class DeploymentSummary(
    val title: String,
    val safe: String,
    val signers: String,
    val shape: String,
    val from: String,
    val listing: String,
    val listingFailed: Boolean = false,
)

/**
 * What a token approval allowed, from this device's record: [title] ("Approval for a swap", or
 * "Allowance reset for a swap" for the zero one a token of USDT's kind needs first), the [amount]
 * with its symbol, the token's [symbol] and bundled [icon] (null: a monogram), the EIP-55
 * [spender] and what it may take ([spenderLabel]), and the EIP-55 [from], the account that sent it.
 */
data class ApprovalSummary(
    val title: String,
    val amount: String,
    val symbol: String,
    val icon: String?,
    val spenderLabel: String,
    val spender: String,
    val from: String,
)

/**
 * What a swap sold and the least it buys, from this device's record: [title] ("Swap of 100 USDC
 * for at least 4.28845 LINK on Uniswap"),
 * the exact [sold] amount with its symbol, the sold asset's [soldSymbol] and [soldIcon], what it
 * buys at least ([bought], "at least 4.28845 LINK") with [boughtSymbol] and [boughtIcon], the EIP-55
 * [router] it went through, named by [through] ("Through the router"), and the EIP-55 [from], the
 * account that sent it and is paid. Once a block has it and its verified receipt's logs say what
 * it paid, [bought] and [title] say that amount, and [received] where it comes from and the
 * minimum it was signed for; null until then, or when they do not say it. A wrap or an unwrap is
 * shown as one too: what it takes and what it pays, one for one, through WETH9.
 */
data class SwapSummary(
    val title: String,
    val sold: String,
    val soldSymbol: String,
    val soldIcon: String?,
    val bought: String,
    val boughtSymbol: String,
    val boughtIcon: String?,
    val router: String,
    val from: String,
    val received: String? = null,
    val through: String = "Through the router",
)

/**
 * Which CoW order a cancellation on chain invalidates, from this device's record: [title]
 * ("Cancellation of a CoW order on chain"), the [order]'s UID, the EIP-55 [settlement] contract it
 * is invalidated at, and the EIP-55 [from], the account that signed the order and sent this.
 */
data class CancellationSummary(val title: String, val order: String, val settlement: String, val from: String)

/**
 * One step of a transfer: its [label] ("Included"), where it stands ([state]) and its [detail] (its
 * block). Simple mode says it without a block (docs/simple-ui.md, S3): [simpleLabel] ("Confirmed",
 * "Final") and [simpleDetail], each null where it is the same as Detailed view's.
 */
data class StatusStep(
    val label: String,
    val state: StepState,
    val detail: String?,
    val simpleLabel: String? = null,
    val simpleDetail: String? = null,
)

/** Where a [StatusStep] stands. */
enum class StepState {
    /** Done: verified, or done by this device (signing, broadcasting). */
    DONE,

    /** Not yet. */
    WAITING,

    /** Done, and the transfer failed in it: included, but reverted. */
    FAILED,

    /** Where it stands cannot be verified right now. */
    UNKNOWN,

    /** Done by this device, but no peer confirmed it: a broadcast maybe sent, until a block has it. */
    UNCONFIRMED,
}
