package eth.serotine.ui.safetx

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.ApproveSafeTxOnChain
import eth.serotine.domain.usecase.CallQuote
import eth.serotine.domain.usecase.CheckSafeSwap
import eth.serotine.domain.usecase.DecodedSafeTx
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.EncodeSigningRequest
import eth.serotine.domain.usecase.ExecuteSafeTx
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveSafeTxQueue
import eth.serotine.domain.usecase.OwnerConfirmation
import eth.serotine.domain.usecase.QuoteSafeApproval
import eth.serotine.domain.usecase.QuoteSafeExecution
import eth.serotine.domain.usecase.ReadSafeTxOwners
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.RemoveQueuedSafeTx
import eth.serotine.domain.usecase.SafeApprovalQuote
import eth.serotine.domain.usecase.SafeApprovalQuoting
import eth.serotine.domain.usecase.SafeExecutionQuote
import eth.serotine.domain.usecase.SafeExecutionQuoting
import eth.serotine.domain.usecase.SafeSwapCheck
import eth.serotine.domain.usecase.SafeTxOwnersReading
import eth.serotine.domain.usecase.SafeTxQueueReading
import eth.serotine.domain.usecase.SafeTxRefusal
import eth.serotine.domain.usecase.SafeTxSignCheck
import eth.serotine.domain.usecase.SafeTxSigning
import eth.serotine.domain.usecase.SignSafeTx
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.domain.usecase.WarmFeeHistory
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.DecodingToken
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NATIVE_DECIMALS
import eth.serotine.ui.components.PROMPT_TIMED_OUT
import eth.serotine.ui.components.PROMPT_TOO_LATE
import eth.serotine.ui.components.QUOTE_MAX_AGE
import eth.serotine.ui.components.STALE_QUOTE
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.ShareItem
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.exactAmount
import eth.serotine.ui.components.feeDetail
import eth.serotine.ui.components.forDecoding
import eth.serotine.ui.components.gasTermsItem
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.notSentItem
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.promptDeadline
import eth.serotine.ui.components.safeSwapItem
import eth.serotine.ui.components.safeTxActionItem
import eth.serotine.ui.components.safeTxRefusalText
import eth.serotine.ui.components.shareItem
import eth.serotine.ui.components.simpleSafeTxRefusalText
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.components.verifiedAt
import eth.serotine.ui.platform.formatDateTime
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Safe transaction screen's ViewModel (docs/wallet-ux.md, screen 17): the transaction [hash]
 * of the Safe at [safe], as this device's queue holds it ([ObserveSafeTxQueue]: decoded here from
 * its raw fields, with only the signatures the Safe accepts now), where each current owner stands
 * on it ([ReadSafeTxOwners], read again whenever its signatures change, on Check again and when
 * the screen is shown again), on [network]. Its intents:
 * - Share request: the request as the text another device imports ([EncodeSigningRequest]), in
 *   one QR code where it fits.
 * - Sign: an owner held here that has not confirmed it signs its hash ([SignSafeTx]); the signed
 *   request is queued. One that could not be saved stays on this screen, to be shared before it
 *   is left.
 * - Approve on chain ([QuoteSafeApproval] → review → [ApproveSafeTxOnChain]) and Execute, once
 *   the confirmations reach the threshold ([QuoteSafeExecution] → review → [ExecuteSafeTx]), from
 *   any account the wallet holds, owner or not (owner ruling, 2026-10-18, #56), a held owner
 *   first, its sending its own confirmation. Their reviews follow Send's (screen 8): a
 *   quote made when the review is entered, again on Refresh, when the screen is shown again after
 *   it was not, and at the tap that would sign it when it is older than [QUOTE_MAX_AGE]
 *   ([timeSource] tells); one signed but not broadcast is retried with its own bytes
 *   ([RebroadcastTransfer]), never quoted or signed anew.
 * - Remove from this device ([RemoveQueuedSafeTx]).
 *
 * Nothing it signs is ever anything but the transaction decoded here. One of the ViewModels the
 * [WalletPassword] passes through (CLAUDE.md, *Trust*): it holds it in the signing step's state
 * while the field is shown, hands it to the signer in an [Unlock], and never logs, saves or caches
 * it; it is dropped once used, when the screen is not shown ([onHidden]) and when it is gone.
 */
class SafeTxViewModel(
    private val account: AccountId,
    private val hash: Digest,
    observeAccounts: ObserveAccounts,
    observeSafeTxQueue: ObserveSafeTxQueue,
    observeKnownTokens: ObserveKnownTokens,
    private val readSafeTxOwners: ReadSafeTxOwners,
    private val encodeSigningRequest: EncodeSigningRequest,
    private val describeKeyStore: DescribeKeyStore,
    private val signSafeTx: SignSafeTx,
    private val quoteSafeApproval: QuoteSafeApproval,
    private val approveSafeTxOnChain: ApproveSafeTxOnChain,
    private val quoteSafeExecution: QuoteSafeExecution,
    private val executeSafeTx: ExecuteSafeTx,
    private val rebroadcastTransfer: RebroadcastTransfer,
    private val removeQueuedSafeTx: RemoveQueuedSafeTx,
    private val warmFeeHistory: WarmFeeHistory,
    private val checkSafeSwap: CheckSafeSwap,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** Whether the screen was opened by the import that just queued the transaction: its overview says so first. */
    imported: Boolean = false,
    /** Dates a swap's deadline in the user's own time zone. */
    private val formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {
    /** The account this screen is about: its address, on its network. */
    private val safe: Address = account.address

    /** The account's network: everything here is read and sent there. */
    private val network: Network = account.network


    init {
        // Approve and Execute quote a transaction: the fee history is asked for now, ready by then.
        warmFees()
    }

    /** What the screen is drawn from: a transaction to act on ([Context]), or why there is none ([Queued]). */
    private sealed interface Source

    /** The transaction as the queue holds it, or why there is none. */
    private sealed interface Queued : Source {
        data object Loading : Queued
        data object Unreadable : Queued

        /** [tx], with the Safe's verified nonce ([safeNonce], null: not verified now). */
        data class Found(val tx: DecodedSafeTx, val safeNonce: Long?) : Queued

        /**
         * Not in the queue; [lastNonce] the nonce it had when it last was (null: never seen here), and
         * [passed] when the Safe's nonce has passed it since.
         */
        data class Missing(val lastNonce: Long?, val passed: Boolean) : Queued
    }

    /**
     * Everything the screen and its intents work from: the transaction [found] in the queue, the
     * [request] shown and shared, where its owners stand ([owners], null while read), what can be
     * done with it ([options]), the [tokens] a transfer is shown in, and the wallet's accounts by
     * address ([labels]).
     */
    private class Context(
        val found: Queued.Found,
        val request: SigningRequest,
        val owners: SafeTxOwnersReading?,
        val options: Options,
        val tokens: List<DecodingToken>,
        val labels: Map<Address, String>,
    ) : Source {
        fun labelOf(address: Address): String = labels[address] ?: shortAddress(Eip55.format(address))
    }

    /** Who can do what with the transaction now. */
    private class Options(
        /** Held owners that have not confirmed it: who may sign it. */
        val signers: List<Address>,
        /** Held owners that have not confirmed it, while the confirmations fall short: who may approve it. */
        val approvers: List<Address>,
        /** Accounts held here whose sending reaches the threshold now, held owners first: who may execute it. */
        val executors: List<Address>,
        /** Why it cannot be executed now, or null. */
        val executeNote: String?,
    )

    /** An approval or an execution under review. */
    private sealed interface TxQuote {
        val call: CallQuote

        data class Approval(val quote: SafeApprovalQuote) : TxQuote {
            override val call: CallQuote
                get() = quote.call
        }

        data class Execution(val quote: SafeExecutionQuote) : TxQuote {
            override val call: CallQuote
                get() = quote.call
        }
    }

    /**
     * A transaction signed but not broadcast: why ([broadcast]), and the [transfer] a retry sends;
     * [unverified] when a retry's rejection could not be checked against the receipt.
     */
    private data class NotSent(val broadcast: Broadcast, val transfer: SentTransfer, val unverified: Boolean = false) {
        /** Whether sending the same bytes again may change the verdict. */
        val canRetry: Boolean
            get() = unverified || broadcast is Broadcast.Unavailable || transfer.maybeSent
    }

    /** Where the screen stands. */
    private sealed interface Step {
        data object Overview : Step

        /** An approval or execution from [account] at [level] being quoted for review. */
        data class Quoting(val kind: ReviewKind, val account: Address, val level: FeeLevel) : Step

        /** Signing the hash as [owner]. */
        data class Signing(
            val owner: Address,
            val unlock: UnlockStep,
            val signing: Boolean = false,
            /** While [signing], the Safe is still being checked on chain: nothing is signed yet. */
            val checking: Boolean = false,
        ) : Step

        /** [quote], made at [quotedAt], under review; [hidden] once the screen stopped being shown since. */
        data class Reviewing(
            val kind: ReviewKind,
            val quote: TxQuote,
            val quotedAt: TimeMark,
            val unlock: UnlockStep,
            val refreshing: Boolean = false,
            val signing: Boolean = false,
            val notSent: NotSent? = null,
            val hidden: Boolean = false,
        ) : Step {
            val account: Address
                get() = quote.call.from

            val level: FeeLevel
                get() = quote.call.level

            val idle: Boolean
                get() = !refreshing && !signing && notSent == null
        }

        data class Sent(val hash: String) : Step
        data object Removed : Step
    }

    /** What the overview shows beside the transaction: the share sheet opened, and what the last action came to. */
    private data class View(
        val sharing: Boolean = false,
        val message: String? = null,
        /** The request signed here whose signature the queue could not save: shown and shared until left. */
        val unsaved: SigningRequest? = null,
        /** Just imported: where it went is said ([addedText]) until another message or a step replaces it. */
        val imported: Boolean = false,
        /** [message] in simple mode's words, with no block, while it is the message it is of. */
        val simple: SimpleText? = null,
    )

    /** [text], simple mode's words for the message [of], which it stands for only while that is the message. */
    private data class SimpleText(val of: String, val text: String)

    private val view = MutableStateFlow(View(imported = imported))
    private val step = MutableStateFlow<Step>(Step.Overview)

    /** Asks for the owners to be read again. */
    private val readAgain = MutableStateFlow(0)

    /** The quote, signature or broadcast in flight. */
    private var job: Job? = null

    /** Whether the screen is shown now ([onShown], [onHidden]). */
    private var shown = false

    /** Whether the screen was hidden since it was last shown: shown again, the owners are read again. */
    private var hiddenSince = false

    /** Whether the screen was hidden while an approval or an execution was first quoted for review. */
    private var hiddenWhileQuoting = false

    /**
     * The transaction as last shown: what a step signing or under review is drawn from while the
     * queue's reading is not there (a restart of its collection), so that it never vanishes with a
     * signature under way.
     */
    private var lastContext: Context? = null

    /** The nonce the transaction had when it was last in the queue, across restarts of its reading. */
    private var lastNonce: Long? = null

    /** The transaction as the queue holds it, each reading judged against the one before. */
    private val queued: Flow<Queued> = observeSafeTxQueue(safe)
        .scan<SafeTxQueueReading, Queued>(Queued.Loading, ::queuedOf)
        .shareIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            replay = 1,
        )

    /** The request shown and shared: the queue's, with a signature made here that it could not save. */
    private val request: Flow<SigningRequest?> = combine(queued, view) { queued, view ->
        val request = (queued as? Queued.Found)?.tx?.request ?: return@combine null
        view.unsaved?.takeIf { it.hash == request.hash }?.let { request.withSignatures(it.signatures) } ?: request
    }.distinctUntilChanged()

    /**
     * Where the owners stand on [request], read again when asked; null while it is read for a
     * request it has not been read for — a reading of the request before (one signature fewer) is
     * no reading of this one, and nothing is offered on it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val owners: Flow<SafeTxOwnersReading?> = request.transformLatest { request ->
        emit(null)
        if (request != null) emitAll(readAgain.mapLatest { readOwners(request) })
    }

    private val accounts: Flow<List<Account>> = observeAccounts()
        // The Safe's network's accounts: what pays for an approval or an execution there, and what names
        // its owners; an owner whose key is held on another network still signs ([ObserveSafe]).
        .map { list -> (list as? AccountList.Loaded)?.accounts.orEmpty().filter { it.network == network } }

    /** What the screen shows and acts on, or why there is no transaction to act on ([Queued]). */
    private val context: StateFlow<Source?> =
        combine(
            queued,
            request,
            owners,
            accounts,
            observeKnownTokens(network),
        ) { queued, request, owners, accounts, tokens ->
            if (queued !is Queued.Found || request == null) return@combine queued
            val held = accounts.filter { it.kind == AccountKind.EOA && it.custody == AccountCustody.InKeyStore }
            Context(
                found = queued,
                request = request,
                owners = owners,
                options = options(queued.tx, owners as? SafeTxOwnersReading.Read, held),
                tokens = tokens.forDecoding(),
                labels = accounts.associate { it.address to it.label },
            )
        }.stateIn(
            viewModelScope,
            // Back after the timeout, no old reading stands as current.
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            null,
        )

    /**
     * Where the transaction stands as a swap or its approval (docs/swaps.md, *A Safe's swap*):
     * checked against the verified venue, and for a swap quoted now, whenever the owners are read
     * again; null while checked, and for anything else.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val swapCheck: StateFlow<SafeSwapCheck?> = queued
        .map { (it as? Queued.Found)?.tx?.action }
        .distinctUntilChanged()
        .transformLatest { action ->
            emit(null)
            if (action is SafeTxAction.TokenApproval || action is SafeTxAction.Swap) {
                emitAll(readAgain.mapLatest { checkSwap(action) })
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            null,
        )

    /** Whether the transaction is a swap whose deadline the verified head has passed: it can never run. */
    private val swapExpired: Boolean
        get() = (swapCheck.value as? SafeSwapCheck.SwapOnUniswap)?.expired == true

    private suspend fun checkSwap(action: SafeTxAction): SafeSwapCheck? = try {
        checkSafeSwap(action)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SafeSwapCheck.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
    }

    val uiState: StateFlow<SafeTxUiState> = combine(context.onEach(::follow), view, step, swapCheck, ::render).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        SafeTxUiState.Loading(network.displayName),
    )

    /** The transaction to act on now, or null while there is none. */
    private val now: Context?
        get() = context.value as? Context

    /** Opens the request for sharing: its text, in one QR code where it fits. */
    fun onShare() {
        // Only what the Safe's verified owners signed is shared: unverified, nothing is.
        if (now?.found?.safeNonce == null) return
        view.update { it.copy(sharing = true) }
    }

    /** Closes the request's sharing. */
    fun onShareClosed() = view.update { it.copy(sharing = false) }

    /** Reads where the owners stand again: an approval mined since, a signature imported elsewhere. */
    fun checkAgain() = readAgain.update { it + 1 }

    /** Starts signing the hash with the first owner held here that has not confirmed it. */
    fun startSigning() {
        if (step.value != Step.Overview || job?.isActive == true) return
        val owner = now?.options?.signers?.firstOrNull() ?: return
        view.update { it.copy(message = null, sharing = false, imported = false) }
        step.value = Step.Signing(owner, UnlockStep())
        viewModelScope.launch {
            val biometrics = biometricsOffered()
            step.update { current ->
                if (current is Step.Signing && !current.signing) {
                    current.copy(unlock = current.unlock.copy(biometrics = biometrics))
                } else {
                    current
                }
            }
        }
    }

    /** Signs as the owner at [address] (EIP-55) instead. */
    fun onSignerChange(address: String) {
        val owner = Address.parse(address) ?: return
        step.update { current ->
            if (current is Step.Signing && !current.signing && owner in now?.options?.signers.orEmpty()) {
                current.copy(owner = owner, unlock = current.unlock.copy(error = null))
            } else {
                current
            }
        }
    }

    /** Quotes the approval on chain from the first owner held here that has not confirmed it, for review. */
    fun startApproval() {
        val owner = now?.options?.approvers?.firstOrNull() ?: return
        quoteForReview(ReviewKind.APPROVAL, owner, FeeLevel.NORMAL)
    }

    /** Quotes the execution from the first account held here that can execute it, a held owner first, for review. */
    fun startExecution() {
        if (swapExpired) return
        val executor = now?.options?.executors?.firstOrNull() ?: return
        quoteForReview(ReviewKind.EXECUTION, executor, FeeLevel.NORMAL)
    }

    /** Sends the reviewed approval or execution from the account at [address] (EIP-55) instead: quoted anew. */
    fun onAccountChange(address: String) {
        val account = Address.parse(address) ?: return
        val review = step.value as? Step.Reviewing ?: return
        if (!review.idle || account == review.account) return
        // The account under review is offered as long as the review is.
        val options = now?.options ?: return
        val allowed = if (review.kind == ReviewKind.APPROVAL) options.approvers else options.executors
        if (account !in allowed) return
        requote(review, account, review.level, note = null)
    }

    /** Sends the reviewed approval or execution at the fee [level] instead: quoted anew. */
    fun onFeeChange(level: FeeLevel) {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle || level == current.level) return
        requote(current, current.account, level, note = null)
    }

    /** Quotes the reviewed approval or execution again: fresh fees, nonce and balance. */
    fun refresh() {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle) return
        requote(current, current.account, current.level, note = null)
    }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = step.update { current ->
        when {
            current is Step.Signing && !current.signing ->
                current.copy(unlock = current.unlock.copy(password = WalletPassword(text), error = null))
            current is Step.Reviewing && !current.signing && current.notSent == null ->
                current.copy(unlock = current.unlock.copy(password = WalletPassword(text), error = null))
            else -> current
        }
    }

    /** Signs with the typed wallet password: the hash in the signing step, the transaction in a review. */
    fun sign() {
        when (val current = step.value) {
            is Step.Signing -> {
                val password = current.unlock.password
                if (current.signing || password.text.isEmpty()) return
                signHash(current, Unlock.WithPassword(password))
            }
            is Step.Reviewing -> {
                val password = current.unlock.password
                if (!current.idle || password.text.isEmpty() || notNeeded(current) != null || requoted(current)) return
                send(current, Unlock.WithPassword(password))
            }
            else -> Unit
        }
    }

    /** Signs with a fingerprint or face: the hash in the signing step, the transaction in a review. */
    fun signWithBiometrics() {
        // The reading may have been let go while hidden: the transaction as last shown, as requote does.
        val action = (now ?: lastContext)?.let(::promptText) ?: return
        when (val current = step.value) {
            is Step.Signing -> {
                if (current.signing || !current.unlock.biometrics) return
                signHash(current, Unlock.WithBiometrics("Sign Safe transaction $action"))
            }
            is Step.Reviewing -> {
                if (!current.idle || !current.unlock.biometrics || notNeeded(current) != null || requoted(current)) {
                    return
                }
                // The prompt gets what is left of the quote's age: past it, nothing is signed, and it is made again.
                val notAfter = promptDeadline(current.quotedAt)
                    ?: return requote(current, current.account, current.level, note = PROMPT_TOO_LATE)
                val what = if (current.kind == ReviewKind.APPROVAL) "Approve on chain" else "Execute"
                send(current, Unlock.WithBiometrics("$what Safe transaction $action", notAfter))
            }
            else -> Unit
        }
    }

    /** Broadcasts the approval or execution signed but not sent again, byte for byte. */
    fun retry() {
        val current = step.value as? Step.Reviewing ?: return
        val notSent = current.notSent ?: return
        if (current.signing || !notSent.canRetry) return
        step.value = current.copy(signing = true)
        job = viewModelScope.launch {
            val sending = safely { rebroadcastTransfer(notSent.transfer) }
            step.update { latest ->
                when {
                    latest !is Step.Reviewing -> latest
                    sending is TransferSending.Sent -> Step.Sent(sending.hash)
                    sending is TransferSending.NotSent ->
                        latest.copy(signing = false, notSent = NotSent(sending.broadcast, sending.transfer))
                    sending is TransferSending.RejectionUnverified -> latest.copy(
                        signing = false,
                        notSent = NotSent(sending.rejection, sending.transfer, unverified = true),
                    )
                    else -> latest.copy(signing = false)
                }
            }
        }
    }

    /**
     * Back from the signing step or a review to the overview: the password and the quote go. Not
     * while signing (checking the Safe before it is not signing yet, and stops), nor after a
     * broadcast no peer answered: those bytes may be out there, and only a retry of them may send
     * them again.
     */
    fun back() {
        when (val current = step.value) {
            // While the Safe is checked nothing is signed yet: Back stops the check.
            is Step.Signing -> if (current.signing && !current.checking) return
            is Step.Reviewing -> if (current.signing || current.notSent?.canRetry == true) return
            is Step.Quoting -> Unit
            else -> return
        }
        job?.cancel()
        step.value = Step.Overview
    }

    /** Takes the transaction out of this device's queue; the screen then leaves. */
    fun remove() {
        if (step.value != Step.Overview || job?.isActive == true) return
        job = viewModelScope.launch {
            val removed = try {
                removeQueuedSafeTx(hash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false // the port promises not to throw; the backstop
            }
            if (step.value != Step.Overview) return@launch
            if (removed) {
                step.value = Step.Removed
            } else {
                view.update { it.copy(message = "This device's queue cannot be saved right now: nothing was removed.") }
            }
        }
    }

    /**
     * The screen stopped being shown: the password goes (CLAUDE.md, *Trust*; rule 8), and a review
     * is quoted again when it is shown again ([onShown]). A signature already running finishes; a
     * signing still checking the Safe stops, its unlock (the password, or the prompt not yet shown)
     * with it, so that no prompt comes up over another screen.
     */
    fun onHidden() {
        shown = false
        hiddenSince = true
        if (step.value is Step.Quoting) hiddenWhileQuoting = true
        // Only ever the Safe's check before a signature.
        if ((step.value as? Step.Signing)?.let { it.signing && it.checking } == true) job?.cancel()
        step.update { current ->
            when (current) {
                is Step.Signing -> current.copy(
                    unlock = current.unlock.copy(password = WalletPassword.EMPTY),
                    signing = current.signing && !current.checking,
                    checking = false,
                )
                is Step.Reviewing ->
                    current.copy(unlock = current.unlock.copy(password = WalletPassword.EMPTY), hidden = true)
                else -> current
            }
        }
    }

    /** The screen is shown (again): the owners are read again, and a review left while hidden is quoted anew. */
    fun onShown() {
        shown = true
        if (hiddenSince) checkAgain()
        hiddenSince = false
        requoteIfHidden()
    }

    override fun onCleared() {
        // The screen is gone, and the password with it.
        onHidden()
    }

    private fun requoteIfHidden() {
        val current = step.value as? Step.Reviewing ?: return
        if (shown && current.hidden && current.idle) requote(current, current.account, current.level, note = null)
    }

    /** Whether [review]'s quote is too old to sign, and so is being made again instead. */
    private fun requoted(review: Step.Reviewing): Boolean {
        // Quoted before the screen was last hidden: never signed as it was.
        if (review.hidden) {
            requote(review, review.account, review.level, note = null)
            return true
        }
        if (review.quotedAt.elapsedNow() <= QUOTE_MAX_AGE) return false
        requote(review, review.account, review.level, note = STALE_QUOTE)
        return true
    }

    private fun quoteForReview(kind: ReviewKind, account: Address, level: FeeLevel) {
        // Not while a removal runs: its outcome lands on the overview, and would be dropped.
        if (step.value != Step.Overview || job?.isActive == true) return
        val request = now?.request ?: return
        view.update { it.copy(message = null, sharing = false, imported = false) }
        val quoting = Step.Quoting(kind, account, level)
        step.value = quoting
        hiddenWhileQuoting = false
        job = viewModelScope.launch {
            // Its age runs from its first read, not from its answer: the figures are as old as that.
            // The first is the one after the Safe's check, which may wait a while for a verified reading.
            var readFrom = timeSource.markNow()
            val next = quoted(kind, request, account, level) { readFrom = timeSource.markNow() }
            val biometrics = biometricsOffered()
            if (step.value != quoting) return@launch
            when (next) {
                // Made while hidden: it is from before the screen came back, so it is made again.
                is Quoted.Made -> step.value = Step.Reviewing(
                    kind,
                    next.quote,
                    readFrom,
                    UnlockStep(biometrics = biometrics),
                    hidden = hiddenWhileQuoting,
                )
                is Quoted.Refused -> {
                    val simple = next.simple?.let { SimpleText(next.message, it) }
                    view.update { it.copy(message = next.message, simple = simple) }
                    step.value = Step.Overview
                }
            }
            requoteIfHidden()
        }
    }

    /** [review] quoted again from [account] at [level], saying [note] under the unlock once shown. */
    private fun requote(review: Step.Reviewing, account: Address, level: FeeLevel, note: String?) {
        // The reading may have been let go while the screen was not shown (a longer hide): the
        // transaction as last shown is what is quoted again, and the quote checks the chain anew.
        val request = (now ?: lastContext)?.request ?: return
        step.value = review.copy(refreshing = true, hidden = false, unlock = review.unlock.copy(error = null))
        job = viewModelScope.launch {
            var readFrom = timeSource.markNow()
            val next = quoted(review.kind, request, account, level) { readFrom = timeSource.markNow() }
            val latest = step.value
            if (latest !is Step.Reviewing || !latest.refreshing) return@launch
            when (next) {
                // The password typed meanwhile stays: it is the same review.
                is Quoted.Made -> step.value = latest.copy(
                    quote = next.quote,
                    quotedAt = readFrom,
                    refreshing = false,
                    unlock = latest.unlock.copy(error = note),
                )
                // Nothing to sign any more: the overview says why.
                is Quoted.Refused -> {
                    val simple = next.simple?.let { SimpleText(next.message, it) }
                    view.update { it.copy(message = next.message, simple = simple) }
                    step.value = Step.Overview
                }
            }
            // Hidden and shown again while it was made: it is from before, so it is made again.
            requoteIfHidden()
        }
    }

    /** What a quote came to. */
    private sealed interface Quoted {
        data class Made(val quote: TxQuote) : Quoted
        data class Refused(val message: String, val simple: String? = null) : Quoted
    }

    /**
     * The [kind] of [request] quoted from [account] at [level]; [safeChecked] is called once the
     * Safe is verified, before the figures the quote's age is measured from are read.
     */
    private suspend fun quoted(
        kind: ReviewKind,
        request: SigningRequest,
        account: Address,
        level: FeeLevel,
        safeChecked: () -> Unit,
    ): Quoted =
        try {
            when (kind) {
                ReviewKind.APPROVAL -> when (val quoting = quoteSafeApproval(request, account, level, safeChecked)) {
                    is SafeApprovalQuoting.Quoted -> Quoted.Made(TxQuote.Approval(quoting.quote))
                    is SafeApprovalQuoting.Refused -> refused(quoting.refusal)
                }
                ReviewKind.EXECUTION -> when (val quoting = quoteSafeExecution(request, account, level, safeChecked)) {
                    is SafeExecutionQuoting.Quoted -> Quoted.Made(TxQuote.Execution(quoting.quote))
                    is SafeExecutionQuoting.Refused -> refused(quoting.refusal)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The use cases promise not to throw; the backstop.
            Quoted.Refused(safeTxRefusalText(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN), network))
        }

    private fun signHash(signing: Step.Signing, unlock: Unlock) {
        // As requote: after a long hide, the transaction as last shown; SignSafeTx checks it on chain.
        val context = now ?: lastContext ?: return
        val request = context.request
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        val unlocked = signing.unlock.copy(password = WalletPassword.EMPTY, error = null)
        step.value = signing.copy(signing = true, checking = true, unlock = unlocked)
        job = viewModelScope.launch {
            // The Safe is checked first, which leaving the screen cancels ([onHidden]): no key is
            // asked for, and no prompt shown, until it has passed.
            val checked = try {
                signSafeTx.check(request, signing.owner)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // the use case promises not to throw; the backstop
            }
            // Left while the check came back: nothing is signed. Otherwise the check is over,
            // whatever it came to.
            currentCoroutineContext().ensureActive()
            step.update { if (it is Step.Signing && it.signing) it.copy(checking = false) else it }
            val result = when (checked) {
                is SafeTxSignCheck.Ready -> {
                    // Signed and queued as one step, against the state just checked, which leaving
                    // cannot cut in half.
                    withContext(NonCancellable) {
                        try {
                            signSafeTx.sign(checked, unlock)
                        } catch (e: Exception) {
                            null // the use case promises not to throw; the backstop
                        }
                    }
                }
                is SafeTxSignCheck.Refused -> SafeTxSigning.Refused(checked.refusal)
                null -> null
            }
            val latest = step.value as? Step.Signing ?: return@launch
            val label = context.labelOf(signing.owner)
            when (result) {
                is SafeTxSigning.Signed -> {
                    view.update {
                        it.copy(
                            unsaved = if (result.saved) null else result.request,
                            message = if (result.saved) {
                                "Signed as $label. Share the request with the other owners, or execute it once " +
                                    "the threshold is met."
                            } else {
                                "Signed as $label, but this device's queue cannot be saved right now: the signature " +
                                    "is only on this screen. Share the request before you leave it."
                            },
                        )
                    }
                    step.value = Step.Overview
                    checkAgain()
                }
                is SafeTxSigning.NotSigned -> step.value = latest.copy(
                    signing = false,
                    unlock = latest.unlock.copy(
                        error = notSignedText(result.signing, SIGN_FAILED),
                        biometrics = latest.unlock.biometrics && biometricsStillOffered(result.signing),
                    ),
                )
                is SafeTxSigning.Refused -> {
                    val message = safeTxRefusalText(result.refusal, network)
                    val simple = simpleSafeTxRefusalText(result.refusal)?.let { SimpleText(message, it) }
                    view.update { it.copy(message = message, simple = simple) }
                    step.value = Step.Overview
                }
                null -> step.value = latest.copy(signing = false, unlock = latest.unlock.copy(error = SIGN_FAILED))
            }
        }
    }

    private fun send(review: Step.Reviewing, unlock: Unlock) {
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        val unlocked = review.unlock.copy(password = WalletPassword.EMPTY, error = null)
        step.value = review.copy(signing = true, unlock = unlocked)
        job = viewModelScope.launch {
            val sending = safely {
                when (val quote = review.quote) {
                    is TxQuote.Approval -> approveSafeTxOnChain(quote.quote, unlock)
                    is TxQuote.Execution -> executeSafeTx(quote.quote, unlock)
                }
            }
            step.update { latest ->
                if (latest !is Step.Reviewing) return@update latest
                when (sending) {
                    is TransferSending.Sent -> Step.Sent(sending.hash)
                    is TransferSending.NotSigned -> latest.copy(
                        signing = false,
                        unlock = latest.unlock.copy(
                            error = notSignedText(sending.signing, SEND_FAILED),
                            biometrics = latest.unlock.biometrics && biometricsStillOffered(sending.signing),
                        ),
                    )
                    is TransferSending.NotSent ->
                        latest.copy(signing = false, notSent = NotSent(sending.broadcast, sending.transfer))
                    is TransferSending.RejectionUnverified -> latest.copy(
                        signing = false,
                        notSent = NotSent(sending.rejection, sending.transfer, unverified = true),
                    )
                    null -> latest.copy(signing = false, unlock = latest.unlock.copy(error = SEND_FAILED))
                }
            }
            // The prompt outlived the quote's age: nothing was signed, and the quote is made again.
            val timedOut = (sending as? TransferSending.NotSigned)?.signing == Signing.TimedOut
            val latest = step.value as? Step.Reviewing
            if (timedOut && latest != null && latest.idle) {
                requote(latest, latest.account, latest.level, note = PROMPT_TIMED_OUT)
            }
            // Hidden while it ran and nothing was sent: the quote is from before, so it is made again.
            requoteIfHidden()
        }
    }

    /**
     * [send], to its end even when the screen is left meanwhile: a transaction signed is recorded
     * and broadcast as one step, which leaving cannot cut in half (its outcome is then not shown).
     */
    private suspend fun safely(send: suspend () -> TransferSending): TransferSending? =
        withContext(NonCancellable) {
            try {
                send()
            } catch (e: Exception) {
                // The ports promise not to throw; the backstop.
                null
            }
        }

    private suspend fun readOwners(request: SigningRequest): SafeTxOwnersReading = try {
        readSafeTxOwners(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SafeTxOwnersReading.Refused(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN)) // the backstop
    }

    /**
     * [reading], after [previous]: a transaction gone from the queue is said to have run when the
     * Safe's nonce passed it.
     */
    private fun queuedOf(previous: Queued, reading: SafeTxQueueReading): Queued = when (reading) {
        SafeTxQueueReading.Unreadable -> Queued.Unreadable
        is SafeTxQueueReading.Loaded -> {
            val tx = reading.transactions.firstOrNull { it.request.hash == hash }
            if (tx != null) {
                lastNonce = tx.request.nonce
                Queued.Found(tx, reading.safeNonce)
            } else {
                val nonce = when (previous) {
                    is Queued.Found -> previous.tx.request.nonce
                    is Queued.Missing -> previous.lastNonce
                    else -> null
                } ?: lastNonce
                val safeNonce = reading.safeNonce
                Queued.Missing(nonce, passed = nonce != null && safeNonce != null && nonce < safeNonce)
            }
        }
    }

    /** What the biometric prompt says is signed: the transaction's nonce, its Safe and what it does. */
    private fun promptText(context: Context): String {
        val request = context.request
        val action = safeTxActionItem(context.found.tx.action, request.nonce, context.tokens, network)
        // The address it names in full: the prompt is the last thing read before the key signs.
        val named = action.addresses.firstOrNull()?.let { ", ${it.what.lowercase()} ${it.address}" }.orEmpty()
        // A swap or an approval says whose contract it is, as far as it was checked.
        val unchecked = when (swapCheck.value) {
            SafeSwapCheck.ApprovalOfRouter, is SafeSwapCheck.SwapOnUniswap -> null
            is SafeSwapCheck.NotUniswap -> "not Uniswap's"
            null, is SafeSwapCheck.Unavailable -> "not verified as Uniswap's".takeIf {
                context.found.tx.action.let { it is SafeTxAction.Swap || it is SafeTxAction.TokenApproval }
            }
        }
        val warning = listOfNotNull(action.warning, unchecked).takeIf { it.isNotEmpty() }
            ?.joinToString("; ", " (", ")").orEmpty()
        return "${request.nonce} of ${context.labelOf(safe)}: ${action.title}$warning$named"
    }

    /**
     * Follows what [source] says of the transaction: the last one shown is kept, and once it is gone
     * from the queue, or the queue cannot be read, a step that does not sign right now ends, its
     * password with it. One signing goes on, and lands on the transaction as last shown; so does a
     * review whose broadcast no peer took, until the Safe's nonce has passed the transaction.
     */
    private fun follow(source: Source?) {
        when (source) {
            is Context -> lastContext = source
            Queued.Unreadable, is Queued.Missing -> step.update { current ->
                // Bytes a peer may have taken stay to be sent again (as back() keeps them) until the
                // Safe's nonce has passed the transaction: a queue that cannot be read for a moment
                // proves nothing about them.
                val bytesOut = current is Step.Reviewing && current.notSent?.canRetry == true
                val passed = source is Queued.Missing && source.passed
                // A step left for the overview takes its quote in flight with it (a refresh, a first
                // quote): left running, it would keep Approve and Execute shut until it ended.
                when {
                    current is Step.Signing && !current.signing -> Step.Overview
                    bytesOut && !passed -> current
                    current is Step.Reviewing && !current.signing -> {
                        job?.cancel()
                        Step.Overview
                    }
                    current is Step.Quoting -> {
                        job?.cancel()
                        Step.Overview
                    }
                    else -> current
                }
            }
            else -> Unit
        }
    }

    private fun render(context: Source?, view: View, step: Step, swapCheck: SafeSwapCheck?): SafeTxUiState {
        val network = network.displayName
        when (step) {
            is Step.Sent -> return SafeTxUiState.Sent(network, step.hash)
            Step.Removed -> return SafeTxUiState.Removed(network)
            else -> Unit
        }
        val inStep = step is Step.Signing || step is Step.Reviewing || step is Step.Quoting
        val shown = when {
            context is Context -> context
            inStep && lastContext != null -> lastContext!!
            else -> when (context) {
                Queued.Unreadable -> return SafeTxUiState.NotQueued(network, QUEUE_UNREADABLE)
                is Queued.Missing -> return SafeTxUiState.NotQueued(network, if (context.passed) PASSED else NOT_QUEUED)
                else -> return SafeTxUiState.Loading(network)
            }
        }
        val tx = shown.found.tx
        val request = shown.request
        val action = safeTxActionItem(tx.action, request.nonce, shown.tokens, this.network)
        val read = shown.owners as? SafeTxOwnersReading.Read
        // Proposed again only by an owner held here: with none, the offer is greyed out, saying why.
        val swap = safeSwapItem(tx.action, swapCheck, shown.tokens, this.network, formatTime)?.let { item ->
            if (item.proposeAgain && read != null && read.owners.none { it.held }) {
                item.copy(proposeAgainReason = NO_OWNER_TO_PROPOSE)
            } else {
                item
            }
        }
        val expired = (swapCheck as? SafeSwapCheck.SwapOnUniswap)?.expired == true
        val header = SafeHeader(
            label = shown.labels[safe],
            address = Eip55.format(safe),
            threshold = read?.let { "${it.threshold}/${it.owners.size}" },
        )
        val nonce = nonceText(request.nonce, read?.safeNonce ?: shown.found.safeNonce)
        val hashText = "0x" + request.hash.hex
        val options = shown.options
        return when (step) {
            is Step.Signing -> SafeTxUiState.Signing(
                network = network,
                safe = header,
                action = action,
                nonce = nonce,
                hash = hashText,
                // The owner chosen stays offered while the step is, whatever the owners read since.
                owners = (listOf(step.owner) + options.signers).distinct().map { choice(shown, it) },
                owner = Eip55.format(step.owner),
                unlock = step.unlock,
                signing = step.signing,
                checking = step.checking,
                swap = swap,
            )
            is Step.Reviewing -> review(shown, header, action, nonce, step).copy(swap = swap)
            else -> SafeTxUiState.Overview(
                network = network,
                safe = header,
                action = action,
                nonce = nonce,
                hash = hashText,
                owners = ownersItem(shown),
                swap = swap,
                gasTerms = tx.gasTerms?.let { gasTermsItem(it, this.network) },
                // What the transaction alone says, or what it would do to the Safe as it stands now.
                refusal = (tx.refusal ?: read?.refusal)
                    ?.let { safeTxRefusalText(it, this.network) },
                canSign = options.signers.isNotEmpty(),
                canApprove = options.approvers.isNotEmpty(),
                // A swap past its deadline never runs: Execute is shown greyed out, saying so.
                canExecute = options.executors.isNotEmpty() && !expired,
                executeNote = if (expired) SWAP_EXPIRED else options.executeNote,
                quoting = step is Step.Quoting,
                canShare = shown.found.safeNonce != null,
                share = if (view.sharing && shown.found.safeNonce != null) share(request) else null,
                message = view.message ?: if (view.imported) addedText(header) else null,
                simpleMessage = view.simple?.takeIf { it.of == view.message }?.text,
                simpleRefusal = (tx.refusal ?: read?.refusal)?.let(::simpleSafeTxRefusalText),
            )
        }
    }

    /** Where an import just put the transaction: the queue of the Safe [header] names. */
    private fun addedText(header: SafeHeader): String =
        "Added to ${header.label?.let { "$it's" } ?: "this Safe's"} queue on this device."

    private fun options(tx: DecodedSafeTx, read: SafeTxOwnersReading.Read?, held: List<Account>): Options {
        if (tx.refusal != null || read == null || read.refusal != null) {
            return Options(emptyList(), emptyList(), emptyList(), null)
        }
        val unconfirmed = read.owners.filter { it.held && !it.confirms }.map { it.owner }
        val short = read.confirmations < read.threshold
        // Any account held here may execute (owner ruling, 2026-10-18, #56); a held owner first: its
        // sending counts as its own confirmation.
        val heldOwners = read.owners.filter { it.held }.map { it.owner }
        val candidates = heldOwners + held.map { it.address }.filter { it !in heldOwners }
        val nonce = tx.request.nonce
        val executors = if (nonce == read.safeNonce) {
            candidates.filter { read.confirmationsExecutedBy(it) >= read.threshold }
        } else {
            emptyList()
        }
        val best = candidates.maxOfOrNull { read.confirmationsExecutedBy(it) } ?: read.confirmations
        val executeNote = when {
            executors.isNotEmpty() -> null
            nonce > read.safeNonce -> "Executed only once ${before(nonce - read.safeNonce)}: only the Safe's next " +
                "transaction can be executed."
            nonce < read.safeNonce -> "The Safe is at nonce ${read.safeNonce} already: this one ran, or another " +
                "transaction used its nonce."
            candidates.isEmpty() -> "No account of this wallet holds a key to send its execution."
            else -> "$best of the ${read.threshold} confirmations it needs to be executed."
        }
        return Options(
            signers = unconfirmed,
            approvers = if (short) unconfirmed else emptyList(),
            executors = executors,
            executeNote = executeNote,
        )
    }

    /** What runs first, in agreement with its number: "the transaction before it has run". */
    private fun before(n: Long) =
        if (n == 1L) "the transaction before it has run" else "the $n transactions before it have run"

    private fun nonceText(nonce: Long, safeNonce: Long?): String = "Safe nonce $nonce" + when {
        safeNonce == null -> ""
        nonce == safeNonce -> " · next to run"
        nonce < safeNonce -> " · passed: the Safe is at nonce $safeNonce"
        nonce - safeNonce == 1L -> " · 1 transaction runs before it"
        else -> " · ${nonce - safeNonce} transactions run before it"
    }

    private fun choice(context: Context, address: Address) =
        SignerChoice(context.labelOf(address), Eip55.format(address))

    /** [refusal] as a quote refused: its text, and simple mode's words for it where they differ. */
    private fun refused(refusal: SafeTxRefusal) =
        Quoted.Refused(safeTxRefusalText(refusal, network), simpleSafeTxRefusalText(refusal))

    private fun ownersItem(context: Context): OwnersItem = when (val owners = context.owners) {
        null -> OwnersItem.Checking
        is SafeTxOwnersReading.Refused ->
            OwnersItem.Unavailable(safeTxRefusalText(owners.refusal, network), simpleSafeTxRefusalText(owners.refusal))
        is SafeTxOwnersReading.Read -> OwnersItem.Shown(
            rows = owners.owners.map { owner ->
                val (state, detail) = when (val confirmation = owner.confirmation) {
                    OwnerConfirmation.Signed -> OwnerState.SIGNED to "Signed"
                    is OwnerConfirmation.ApprovedOnChain -> OwnerState.APPROVED to "Approved on chain" +
                        confirmation.blockNumber?.let { ", verified at block ${grouped(it)}" }.orEmpty()
                    OwnerConfirmation.Missing -> OwnerState.MISSING to "Not yet"
                    is OwnerConfirmation.Unknown -> OwnerState.UNKNOWN to
                        "No signature; whether it approved on chain cannot be verified right now"
                }
                val approved = owner.confirmation is OwnerConfirmation.ApprovedOnChain
                val simpleDetail = "Approved on chain".takeIf { approved }
                OwnerStateRow(
                    Eip55.format(owner.owner),
                    context.labels[owner.owner],
                    owner.held,
                    state,
                    detail,
                    simpleDetail = simpleDetail?.takeIf { it != detail },
                )
            },
            threshold = "${owners.threshold} of ${owners.owners.size} owners must confirm",
            confirmations = "${owners.confirmations} of ${owners.threshold} confirmations",
            verifiedAt = "Owners verified at block ${grouped(owners.blockNumber)}",
            simpleVerifiedAt = "Owners checked on chain",
        )
    }

    private fun share(request: SigningRequest): ShareItem =
        shareItem(encodeSigningRequest(request), request.signatures.size)

    private fun review(
        context: Context,
        header: SafeHeader,
        action: SafeTxActionItem,
        nonce: String,
        step: Step.Reviewing,
    ): SafeTxUiState.Review {
        val options = context.options
        val call = step.quote.call
        val native = network.nativeSymbol
        val ran = call.simulationBlock?.let { "at block ${grouped(it)}" } ?: "at the head"
        val allowed = if (step.kind == ReviewKind.APPROVAL) options.approvers else options.executors
        // The account under review stays offered while it is quoted, whatever the owners say since.
        val accounts = (listOf(step.account) + allowed).distinct()
        return SafeTxUiState.Review(
            network = network.displayName,
            safe = header,
            action = action,
            nonce = nonce,
            kind = step.kind,
            accounts = accounts.map { choice(context, it) },
            account = Eip55.format(step.account),
            fee = call.level,
            maxFee = exactAmount(call.maxFee, NATIVE_DECIMALS, native),
            feeDetail = feeDetail(call.level, call.transaction, call.feeBlock),
            balance = BalanceChange(
                what = "$native balance of ${context.labelOf(step.account)}, which pays the fee",
                before = exactAmount(call.nativeBefore, NATIVE_DECIMALS, native),
                after = "at least " + exactAmount(call.nativeAfterAtLeast, NATIVE_DECIMALS, native),
                verifiedAt = verifiedAt(call.balanceBlock),
            ),
            simulation = when (step.kind) {
                ReviewKind.APPROVAL ->
                    "The Safe's approveHash of this transaction, run as a verified call from this account $ran, " +
                        "goes through."
                ReviewKind.EXECUTION ->
                    "The whole execution — the Safe's checks of the confirmations and the nonce, its guard if it " +
                        "has one, and the call itself — run as a verified call from this account $ran, goes through."
            },
            simpleSimulation = when (step.kind) {
                ReviewKind.APPROVAL ->
                    "Checked on chain: the Safe's approveHash of this transaction should go through."
                ReviewKind.EXECUTION -> "Checked on chain: the whole execution — the Safe's checks of the " +
                    "confirmations and the nonce, its guard if it has one, and the call itself — should go through."
            },
            unlock = step.unlock,
            confirmations = (step.quote as? TxQuote.Execution)?.quote?.let { confirmationsText(context, it) },
            notNeeded = notNeeded(step, context),
            pending = call.pendingCost.takeUnless { it.isZero }?.let {
                "Earlier transactions from this account, not mined yet, may still take up to " +
                    exactAmount(it, NATIVE_DECIMALS, native) + ": the balance after counts it."
            },
            refreshing = step.refreshing,
            signing = step.signing,
            notSent = step.notSent?.let {
                val what = if (step.kind == ReviewKind.APPROVAL) "approval" else "execution"
                notSentItem(it.broadcast, it.unverified, what, maybeSent = it.transfer.maybeSent)
            },
        )
    }

    /**
     * Why the approval under [review] is no longer needed, as the owners read now say (the
     * confirmations reach the threshold, or its owner confirmed it meanwhile), or null.
     */
    private fun notNeeded(review: Step.Reviewing, context: Context? = now ?: lastContext): String? {
        if (review.kind != ReviewKind.APPROVAL) return null
        val read = context?.owners as? SafeTxOwnersReading.Read ?: return null
        return when {
            read.confirmations >= read.threshold ->
                "Its confirmations reach the threshold now: approving it is no longer needed. Go back to execute it."
            read.owners.any { it.owner == review.account && it.confirms } ->
                "This owner has confirmed it meanwhile: approving it is no longer needed."
            else -> null
        }
    }

    private fun confirmationsText(context: Context, quote: SafeExecutionQuote): String =
        "Executed with: " + quote.signatures.joinToString("; ") { signature ->
            val who = context.labelOf(signature.owner)
            when (signature) {
                is SafeSignature.Ecdsa -> "$who's signature"
                is SafeSignature.ApprovedHash -> if (signature.owner == quote.executor) {
                    "$who, by sending the execution"
                } else {
                    "$who's approval on chain"
                }
            }
        }

    /**
     * Asks for the fee history once, now, and lets the answer go ([WarmFeeHistory]): the light
     * client builds it on a first request in seconds, so by the review it is ready. Never throws.
     */
    private fun warmFees() {
        viewModelScope.launch {
            try {
                warmFeeHistory(network)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing rests on it: the quote asks for the fees itself.
            }
        }
    }

    /** Whether the biometric prompt is on for this wallet ([DescribeKeyStore]); never throws. */
    private suspend fun biometricsOffered(): Boolean = try {
        biometricsOn(describeKeyStore())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false // the use case promises not to throw; the backstop: the password still works
    }

    private companion object {
        const val NO_OWNER_TO_PROPOSE = "No owner of this Safe has its key on this device, so the swap cannot be " +
            "proposed again from here. Import an owner's recovery phrase to propose and sign its transactions."
        const val SWAP_EXPIRED = "Its deadline has passed: the router refuses it, so it can never run as signed."
        const val SIGN_FAILED = "Serotine could not finish this signature. Nothing was queued: try again."
        const val SEND_FAILED =
            "Serotine could not finish this transaction. Check the account's balance before you send again."
        const val QUEUE_UNREADABLE =
            "This device's queue of Safe transactions cannot be read right now. Serotine keeps trying."
        const val NOT_QUEUED = "This transaction is not in this device's queue."
        const val PASSED =
            "No longer pending: the Safe's nonce has passed it, so it ran, or another transaction used its nonce."
    }
}
