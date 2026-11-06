package eth.serotine.ui.safetx

import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.GasTermsItem
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.SafeSwapItem
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.ShareItem
import eth.serotine.ui.components.UnlockStep

/**
 * Everything the Safe transaction screen renders (docs/wallet-ux.md, screen 17): one transaction
 * of this device's queue, decoded here from its raw fields, where each of the Safe's owners stands
 * on it, and what can be done with it — share it, sign it with an owner key held here, approve it
 * on chain, execute it, remove it from this device — with the steps that sign. Every leaf is
 * display-ready. While a step takes the wallet password, its [UnlockStep] holds it, under the
 * screen protections of a screen that takes it; the ViewModel drops it once used and whenever
 * the screen is not shown.
 */
sealed interface SafeTxUiState {

    /** The network's name ("Sepolia"). */
    val network: String

    /** Nothing read yet. */
    data class Loading(override val network: String) : SafeTxUiState

    /**
     * The transaction is not in this device's queue (any more), or the queue cannot be read, for
     * the reason [message] gives.
     */
    data class NotQueued(override val network: String, val message: String) : SafeTxUiState

    /**
     * The transaction: the [safe] it is of; what it does ([action]); where it stands in the Safe's
     * order ([nonce], "Safe nonce 12 · next"); its [hash], computed here; its [gasTerms] when it
     * carries any; [refusal], why this wallet will not sign, approve or execute it (a delegate call,
     * gas terms), or null; and each owner's stand ([owners]). For a swap or its approval, [swap]
     * says where it stands as the chain is now (docs/swaps.md, *A Safe's swap*). What can be done: [canSign] with an
     * owner key held here, [canApprove] on chain, [canExecute] once its confirmations reach the
     * threshold, [executeNote] saying why it cannot be executed yet; [quoting] while an approval
     * or an execution is quoted for review. [canShare] once the Safe is verified, so that what is
     * shared carries only signatures its current owners made; [share] is the request opened for
     * sharing, null while closed; [message] what the last action came to, or null. Simple mode says
     * no block (docs/simple-ui.md): [simpleRefusal] and [simpleMessage] are [refusal] and [message]
     * in its words, null where they are the same. [draftNote] in lenient mode while the light client
     * catches up (docs/lenient-mode.md, *Safe transactions in lenient mode*): what Sign, Approve on
     * chain and Execute are prepared on (the Safe as last verified, its age), and that each is checked
     * on chain before it is shown or sent; [simpleDraftNote] that with no block.
     */
    data class Overview(
        override val network: String,
        val safe: SafeHeader,
        val action: SafeTxActionItem,
        val nonce: String,
        val hash: String,
        val owners: OwnersItem,
        val swap: SafeSwapItem? = null,
        val gasTerms: GasTermsItem? = null,
        val refusal: String? = null,
        val canSign: Boolean = false,
        val canApprove: Boolean = false,
        val canExecute: Boolean = false,
        val executeNote: String? = null,
        val quoting: Boolean = false,
        val canShare: Boolean = true,
        val share: ShareItem? = null,
        val message: String? = null,
        val simpleRefusal: String? = null,
        val simpleMessage: String? = null,
        val draftNote: String? = null,
        val simpleDraftNote: String? = null,
    ) : SafeTxUiState

    /**
     * Signing the transaction's hash with the key of [owner] (EIP-55), one of [owners], the owners
     * held here that have not confirmed it; [unlock] is how, [signing] while it runs. In lenient mode
     * ([preparedOn] set: the Safe as last verified, its age), the signature is held until its check
     * at a fresh head, never queued or shown before it ([signLabel] says so).
     */
    data class Signing(
        override val network: String,
        val safe: SafeHeader,
        val action: SafeTxActionItem,
        val nonce: String,
        val hash: String,
        val owners: List<SignerChoice>,
        val owner: String,
        val unlock: UnlockStep,
        val signing: Boolean = false,
        /** While [signing], the Safe is still checked on chain: no key is asked for yet, and Back stops it. */
        val checking: Boolean = false,
        /** For a swap or its approval, where it stands now: the last thing shown before the key signs. */
        val swap: SafeSwapItem? = null,
        val preparedOn: String? = null,
        val signLabel: String = "Sign",
    ) : SafeTxUiState {

        /** Something is typed and nothing runs: the password may sign. */
        val canSign: Boolean
            get() = !signing && unlock.password.text.isNotEmpty()

        /** The biometric prompt may be offered now. */
        val canSignWithBiometrics: Boolean
            get() = !signing && unlock.biometrics
    }

    /**
     * An approval on chain or an execution ([kind]), quoted from verified state as Send's review is
     * (docs/wallet-ux.md, screen 8): sent from [account] (EIP-55), one of [accounts], at the [fee]
     * level; [maxFee] the most its fee can be and [feeDetail] what it is made of; [balance] the
     * account's native balance before → after; [simulation] how it ran as a verified call; for an
     * execution, the [confirmations] it is executed with; [pending] what earlier transactions not
     * mined yet may take; for an approval, [notNeeded] once the owners read since make it so, and
     * then it is not signed. For a swap or its approval, [swap] says where it stands now, a
     * minimum fallen behind the market included. [unlock] is how it is signed; [refreshing]
     * while it is quoted again,
     * [signing] while it is signed and broadcast, and [notSent] once it was signed but not
     * broadcast: then the same signed bytes are all that can be sent. [simpleSimulation] is
     * [simulation] with no block, for simple mode (null: the same), which keeps the fee's make-up and
     * the blocks behind *Details*.
     */
    data class Review(
        override val network: String,
        val safe: SafeHeader,
        val action: SafeTxActionItem,
        val nonce: String,
        val kind: ReviewKind,
        val accounts: List<SignerChoice>,
        val account: String,
        val fee: FeeLevel,
        val maxFee: String,
        val feeDetail: String,
        val balance: BalanceChange,
        val simulation: String,
        val unlock: UnlockStep,
        val confirmations: String? = null,
        val pending: String? = null,
        val notNeeded: String? = null,
        val swap: SafeSwapItem? = null,
        val refreshing: Boolean = false,
        val signing: Boolean = false,
        val notSent: NotSentItem? = null,
        val simpleSimulation: String? = null,
    ) : SafeTxUiState {

        /** Something is typed, and nothing else runs or has run: the password may sign. */
        val canSign: Boolean
            get() = idle && notNeeded == null && unlock.password.text.isNotEmpty()

        /** The biometric prompt may be offered now. */
        val canSignWithBiometrics: Boolean
            get() = idle && notNeeded == null && unlock.biometrics

        /** Neither a refresh nor a signature runs, and nothing was signed yet. */
        val idle: Boolean
            get() = !refreshing && !signing && notSent == null
    }

    /**
     * An approval on chain or an execution ([kind]) drafted in lenient mode (docs/lenient-mode.md,
     * *Safe transactions in lenient mode*; owner rulings, 2026-11-06), to be signed and queued, never
     * sent from here: from [account] (EIP-55), one of [accounts], at the [fee] level, on what was
     * verified last ([preparedOn], "Prepared on data from 12 min ago…"). [maxFee] the most its fee
     * can be at the pinned gas ceiling, [feeDetail] what it is made of with its block and [feeAge]
     * its age; [txNonce] the account's nonce with its block and [txNonceAge] its age; [balance] the
     * account's native balance before → after "if nothing changed", its block and age; for an
     * execution, the [confirmations] it is executed with. [notChecked] stands in for the verified
     * simulation. No fiat value, no Refresh and no deadline: a draft is old by definition, and the
     * check before sending reads fresh state. [unlock] signs it; [signing] while it is signed and
     * queued; [error] what the last attempt came to.
     */
    data class DraftReview(
        override val network: String,
        val safe: SafeHeader,
        val action: SafeTxActionItem,
        val nonce: String,
        val kind: ReviewKind,
        val accounts: List<SignerChoice>,
        val account: String,
        val fee: FeeLevel,
        val preparedOn: String,
        val maxFee: String,
        val feeDetail: String,
        val feeAge: String,
        val txNonce: String,
        val txNonceAge: String,
        val balance: BalanceChange,
        val notChecked: String,
        val unlock: UnlockStep,
        val confirmations: String? = null,
        val pending: String? = null,
        val signing: Boolean = false,
        val error: String? = null,
    ) : SafeTxUiState {

        /** Something is typed, and no signature runs: the password may sign. */
        val canSign: Boolean
            get() = !signing && unlock.password.text.isNotEmpty()

        /** The biometric prompt may be offered now. */
        val canSignWithBiometrics: Boolean
            get() = !signing && unlock.biometrics
    }

    /**
     * An owner's signature of this transaction, made in lenient mode and held on this device until its
     * check at a fresh head (docs/lenient-mode.md, *Safe transactions in lenient mode*): what it does
     * ([action], decoded here), its [nonce] and [hash], what it was signed on ([preparedOn]), and its
     * [steps]: Signed, Held (waiting for the network, checked once it has caught up, the network off,
     * or a conflict that deleted it), then In the queue and shared, which waits for the check. Its QR
     * code and text are not offered ([shareNote] says when they are). [discardLabel] removes it
     * (Discard, or Dismiss once a conflict deleted it) and [discardNote] says what that does;
     * [discarding] while it runs. [again], for a transfer this device proposed whose nonce was used
     * meanwhile, is what Propose again opens Send for a Safe with, once it is discarded. [error] a
     * change that could not be saved.
     */
    data class Held(
        override val network: String,
        val safe: SafeHeader,
        val action: SafeTxActionItem,
        val nonce: String,
        val hash: String,
        val preparedOn: String,
        val steps: List<HeldStep>,
        val shareNote: String,
        /** The block the Safe it was signed on was verified at, which simple mode keeps behind *Details*. */
        val safeVerifiedAt: String,
        val discardLabel: String,
        val discardNote: String,
        val again: SafeProposeAgain? = null,
        val discarding: Boolean = false,
        val error: String? = null,
    ) : SafeTxUiState

    /** Broadcast: [hash] is the approval's or the execution's, which the status screen follows. */
    data class Sent(override val network: String, val hash: String) : SafeTxUiState

    /**
     * An approval or an execution signed in lenient mode and queued in the outbox under [id]: nothing
     * was broadcast, and the status screen follows it.
     */
    data class Queued(override val network: String, val id: String) : SafeTxUiState

    /**
     * A held signature discarded here, for a transaction no longer in this device's queue: [message]
     * says so; [reopen], after Propose again, is what Send for a Safe opens with in its place.
     */
    data class Discarded(
        override val network: String,
        val message: String,
        val reopen: SafeProposeAgain? = null,
    ) : SafeTxUiState

    /** Removed from this device's queue: the screen leaves. */
    data class Removed(override val network: String) : SafeTxUiState
}

/** One step of a held signature's way: its [label] ("Held"), where it stands ([state]), and its [detail]. */
data class HeldStep(val label: String, val state: HeldStepState, val detail: String?)

/** Where a [HeldStep] stands. */
enum class HeldStepState {
    /** Done, on this device. */
    DONE,

    /** Not yet: it waits. */
    WAITING,

    /** It no longer stands: its signature was deleted. */
    FAILED,

    /** It cannot happen while something else is missing (the network off, a signature deleted). */
    UNKNOWN,
}

/**
 * What Send for a Safe opens with for a proposal signed again (Propose again, after its nonce was
 * used): the Safe on the network of [chainId] ([safe], an address), the token's contract [asset]
 * (null: the network's currency), the recipient [to] (an address) and the [amount] in the asset's
 * units as text. Identifiers in lower case, as a route carries them.
 */
data class SafeProposeAgain(
    val chainId: Long,
    val safe: String,
    val asset: String?,
    val to: String,
    val amount: String,
)

/**
 * The Safe a transaction is of: its [label] in the wallet (null: not in it), its EIP-55 [address]
 * and its verified [threshold] over its owner count ("2/3"; null until read).
 */
data class SafeHeader(val label: String?, val address: String, val threshold: String? = null)

/** Where the Safe's current owners stand on the transaction. */
sealed interface OwnersItem {

    /** Being read. */
    data object Checking : OwnersItem

    /**
     * Every owner's stand ([rows], in the Safe's order), how many must confirm ([threshold], "2 of
     * 3 owners must confirm"), how many do ([confirmations], "1 of 2 confirmations"), and the block
     * the owners were verified at ([verifiedAt]); [simpleVerifiedAt] that with no block, for simple mode
     * (null: the same).
     */
    data class Shown(
        val rows: List<OwnerStateRow>,
        val threshold: String,
        val confirmations: String,
        val verifiedAt: String,
        val simpleVerifiedAt: String? = null,
    ) : OwnersItem

    /** Not verifiable right now, for the reason [message] gives; [simpleMessage] that with no block, or null. */
    data class Unavailable(val message: String, val simpleMessage: String? = null) : OwnersItem
}

/**
 * One owner: its EIP-55 [address], its name in the wallet ([label], null: not in it), whether its
 * key is held on this device ([heldHere]), its [state], and [detail] ("Approved on chain at block
 * 9,412,118"); [simpleDetail] that with no block, for simple mode, null where it is [detail] itself.
 */
data class OwnerStateRow(
    val address: String,
    val label: String?,
    val heldHere: Boolean,
    val state: OwnerState,
    val detail: String,
    val simpleDetail: String? = null,
)

/** Where one owner stands on a Safe transaction. */
enum class OwnerState {
    /** Its signature is in the request and recovers to it over the hash computed here. */
    SIGNED,

    /** It approved the hash on chain. */
    APPROVED,

    /** Neither. */
    MISSING,

    /** No signature, and whether it approved on chain cannot be verified right now. */
    UNKNOWN,
}

/** An account that can sign or send for the transaction: its [label] and EIP-55 [address], and [note] about it. */
data class SignerChoice(val label: String, val address: String, val note: String? = null)

/** What a [SafeTxUiState.Review] sends. */
enum class ReviewKind {
    /** An owner's `approveHash` of the transaction's hash. */
    APPROVAL,

    /** The transaction's `execTransaction`. */
    EXECUTION,
}
