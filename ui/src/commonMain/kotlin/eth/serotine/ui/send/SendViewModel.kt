package eth.serotine.ui.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SyncPolicy
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.AccountValue
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.DraftInput
import eth.serotine.domain.usecase.DraftTransfer
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.ObserveSyncPolicy
import eth.serotine.domain.usecase.QueueTransfer
import eth.serotine.domain.usecase.QuoteTransfer
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.RecipientLookup
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.SendTransfer
import eth.serotine.domain.usecase.TransferDraft
import eth.serotine.domain.usecase.TransferDrafting
import eth.serotine.domain.usecase.TransferQueuing
import eth.serotine.domain.usecase.TransferQuote
import eth.serotine.domain.usecase.TransferQuoting
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.domain.usecase.WarmFeeHistory
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.AssetOption
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NATIVE_DECIMALS
import eth.serotine.ui.components.PROMPT_TIMED_OUT
import eth.serotine.ui.components.PROMPT_TOO_LATE
import eth.serotine.ui.components.QUOTE_MAX_AGE
import eth.serotine.ui.components.RecipientState
import eth.serotine.ui.components.ResolvedAgain
import eth.serotine.ui.components.STALE_QUOTE
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.TransferForm
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.ZERO_ADDRESS
import eth.serotine.ui.components.addressFieldLabel
import eth.serotine.ui.components.agoText
import eth.serotine.ui.components.amountHint
import eth.serotine.ui.components.assetOptions
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.components.exactAmount
import eth.serotine.ui.components.feeDetail
import eth.serotine.ui.components.figureText
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.levelName
import eth.serotine.ui.components.notSentItem
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.promptDeadline
import eth.serotine.ui.components.recipientHint
import eth.serotine.ui.components.recipientItem
import eth.serotine.ui.components.recipientNote
import eth.serotine.ui.components.redatedWhile
import eth.serotine.ui.components.recipientStates
import eth.serotine.ui.components.recipientText
import eth.serotine.ui.components.resolveAgain
import eth.serotine.ui.components.retryLookupWaits
import eth.serotine.ui.components.typedAmount
import eth.serotine.ui.components.verifiedAt
import eth.serotine.ui.outbox.OPEN_QUEUED
import eth.serotine.ui.outbox.QUEUED_FIRST
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Send screen's ViewModel (docs/wallet-ux.md, screens 7 and 8): a transfer from [from] on
 * [network], composed — the asset ([asset] the token asked for, null for the network's currency),
 * the recipient typed or picked and resolved ([ResolveRecipient]), the amount in token units and
 * the fee level — then quoted from verified state ([QuoteTransfer]) for review, and signed and
 * broadcast ([SendTransfer]); one signed but not broadcast is retried with its own bytes
 * ([RebroadcastTransfer]), never signed anew.
 *
 * What is signed is the quote under review, and a quote is never signed stale: it is made when
 * the review is entered, again on Refresh, when the screen is shown again after it was not, and
 * when it is older than [QUOTE_MAX_AGE] at the tap that would sign it ([timeSource] tells), and
 * it is dropped by any change of what it was made for. The biometric prompt gets what is left of
 * that age as its deadline ([promptDeadline]): one answered later signs nothing, and the quote is
 * made again. Its recipient's name is resolved again
 * with every quote. A price never reaches it: the amount is what was typed, in token units.
 *
 * The compose step dates each balance against [now] (unix seconds), anew every second: simple mode
 * shows how long ago it was proved in place of its block (docs/simple-ui.md).
 *
 * In lenient mode (*Sign while syncing*, [ObserveSyncPolicy]; docs/lenient-mode.md, owner rulings,
 * 2026-11-06), on a network that is on ([ObserveEnabledNetworks]; one that is off is not syncing):
 * the compose step offers a balance last verified too, labelled, with no fiat value; Max on the
 * network's currency leaves room for the most the fee can be ([DraftTransfer.mostNative]); and a
 * review the strict quote refuses as still syncing is drafted on the state last verified instead
 * ([DraftTransfer]), reviewed with each figure's age, and signed with no deadline into the outbox
 * ([QueueTransfer]), never broadcast from here: the outbox's check at a fresh head sends it. Strict
 * mode is as above, unchanged. [fill], when given, is what the compose step opens with: a queued
 * transfer signed again.
 *
 * One of the ViewModels the [WalletPassword] passes through (CLAUDE.md, *Trust*): it holds it in
 * the review's state while the field is shown, hands it to the signer in an [Unlock], and never
 * logs, saves or caches it; it is dropped once used, when the screen is not shown ([onHidden])
 * and when the screen is gone ([onCleared]).
 */
class SendViewModel(
    private val account: AccountId,
    asset: Address?,
    observeAccounts: ObserveAccounts,
    observeAccountValue: ObserveAccountValue,
    private val describeKeyStore: DescribeKeyStore,
    private val resolveRecipient: ResolveRecipient,
    private val quoteTransfer: QuoteTransfer,
    private val sendTransfer: SendTransfer,
    private val rebroadcastTransfer: RebroadcastTransfer,
    private val warmFeeHistory: WarmFeeHistory,
    /** Whether the user turned on *Sign while syncing* (lenient mode); strict by default. */
    observeSyncPolicy: ObserveSyncPolicy,
    /** Which networks are on: a network that is off is not syncing, and nothing is drafted on it. */
    observeEnabledNetworks: ObserveEnabledNetworks,
    /** A lenient draft of the transfer, on the state last verified, and lenient Max. */
    private val draftTransfer: DraftTransfer,
    /** Signs a reviewed draft and queues it in the outbox. */
    private val queueTransfer: QueueTransfer,
    fill: SendFill? = null,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val now: () -> Long = ::epochSecondsNow,
) : ViewModel() {
    /** The account this screen is about: its address, on its network. */
    private val from: Address = account.address

    /** The account's network: everything here is read and sent there. */
    private val network: Network = account.network


    init {
        // The compose step is open: the fee history is asked for now, ready by the review.
        warmFees()
    }

    /**
     * What the compose step has: the token chosen ([asset], null for the network's currency), the
     * recipient and the amount as typed, the fee level, and how often the user asked to resolve the
     * recipient again ([lookupAttempt]).
     */
    private data class Inputs(
        val asset: Address?,
        val to: String = "",
        val amount: String = "",
        val fee: FeeLevel = FeeLevel.NORMAL,
        val lookupAttempt: Int = 0,
    )

    /** The account sent from, or why nothing can be sent from it. */
    private sealed interface Sender {
        data class From(val account: Account) : Sender

        data class Cannot(val message: String) : Sender
    }

    /**
     * Everything the compose step is derived from; [lenient] while *Sign while syncing* is on and
     * the network is on: a balance last verified is offered, and a review may be a draft.
     */
    private class Model(
        val sender: Sender,
        val accounts: List<Account>,
        val currency: DisplayCurrency,
        val options: List<AssetOption>,
        val selected: AssetOption?,
        val tokensRead: Boolean,
        val inputs: Inputs,
        val lookup: RecipientState,
        val lenient: Boolean,
    )

    /** A transfer the compose step can have quoted, exactly as it has it. */
    private data class Request(
        val inputs: Inputs,
        val to: RecipientLookup.Found,
        val option: AssetOption,
        val amount: UInt256,
    )

    /**
     * A transfer signed but not broadcast: why ([broadcast]), and the [transfer] a retry sends;
     * [unverified] when a retry's rejection could not be checked against the receipt.
     */
    private data class NotSent(val broadcast: Broadcast, val transfer: SentTransfer, val unverified: Boolean = false) {
        /**
         * Whether sending the same bytes again may change the verdict: no peer answered, or a
         * rejection could not yet be told from the first broadcast having been mined.
         */
        val canRetry: Boolean
            get() = unverified || broadcast is Broadcast.Unavailable || transfer.maybeSent
    }

    /** Where the flow stands. */
    private sealed interface Step {
        /**
         * Composing; [quoting] while a review is quoted, [error] why the last one was not, and
         * [earlier] the hash of an earlier transaction that may still be on its way, which held it
         * back: its status is where it is tried again; [queued] when it is the id of what the
         * account has queued instead, whose status says where it stands.
         */
        data class Composing(
            val quoting: Boolean = false,
            val error: String? = null,
            val earlier: String? = null,
            /** [error] in simple mode's words, null where it is the same: no block named. */
            val simpleError: String? = null,
            val queued: Boolean = false,
        ) : Step

        /**
         * [quote] of [request], made at [quotedAt], under review; [hidden] once the screen stopped
         * being shown since it was made. [sender] and [accounts] are the wallet's as they were when
         * it was quoted: the review stands on them while the account list is not there (a restart
         * of its reading), rather than vanish with a password in it or a signature under way.
         */
        data class Reviewing(
            val request: Request,
            val quote: TransferQuote,
            val quotedAt: TimeMark,
            val unlock: UnlockStep,
            val sender: SendFrom,
            val accounts: List<Account>,
            val refreshing: Boolean = false,
            val signing: Boolean = false,
            val notSent: NotSent? = null,
            val hidden: Boolean = false,
        ) : Step {
            val idle: Boolean
                get() = !refreshing && !signing && notSent == null
        }

        /**
         * Lenient mode: [draft] of [request], drafted on the state last verified, under review;
         * [sender] and [accounts] as when it was drafted, as for [Reviewing]. Never quoted again
         * while shown: a draft is old by definition, and the check before sending reads fresh state.
         */
        data class Drafting(
            val request: Request,
            val draft: TransferDraft,
            val unlock: UnlockStep,
            val sender: SendFrom,
            val accounts: List<Account>,
            val signing: Boolean = false,
        ) : Step

        data class Sent(val hash: String) : Step

        /** Signed and queued under [id]: nothing was broadcast. */
        data class Queued(val id: String) : Step
    }

    // The checksum once, not on every balance reading.
    private val display = Eip55.format(from)

    private val inputs = MutableStateFlow(
        fill?.let { Inputs(asset, to = it.to, amount = it.amount, fee = it.fee) } ?: Inputs(asset),
    )
    private val step = MutableStateFlow<Step>(Step.Composing())

    /** Whether a review may be a lenient draft: *Sign while syncing* on, and the network on. */
    private val lenient: Flow<Boolean> =
        combine(observeSyncPolicy(), observeEnabledNetworks()) { policy, on ->
            policy == SyncPolicy.LENIENT && network in on
        }.distinctUntilChanged()

    /** The quote, signature or broadcast in flight. */
    private var job: Job? = null

    /** Whether the screen is shown now ([onShown], [onHidden]). */
    private var shown = false

    /** Whether the screen was hidden while the review's first quote was being made. */
    private var hiddenWhileQuoting = false

    /** The typed recipient, resolved: an address at once, a name once the typing paused. */
    private val lookup: Flow<RecipientState> =
        recipientStates(inputs.map { it.to.trim() to it.lookupAttempt }, ::lookUp)

    private val model: StateFlow<Model?> =
        combine(
            observeAccounts(),
            observeAccountValue(account),
            inputs,
            lookup,
            lenient,
        ) { list, value, inputs, lookup, lenient ->
            modelOf(list, value, inputs, lookup, lenient)
        }.stateIn(
            viewModelScope,
            // As on the account screens: back after the timeout, no old balance stands as current.
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            null,
        )

    val uiState: StateFlow<SendUiState> = combine(model, step) { model, step -> model to step }
        // A balance's age, which simple mode shows, is dated anew every second on the compose step;
        // a draft's figures likewise on its review.
        .redatedWhile { (model, step) ->
            step is Step.Drafting || step is Step.Composing && model?.options.orEmpty().any { it.provenAt != null }
        }
        .map { (model, step) -> render(model, step) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            SendUiState.Loading(network.displayName),
        )

    /** Chooses the asset: the token at [contract], or the network's currency (null). */
    fun onAssetChange(contract: String?) {
        val asset = contract?.let { Address.parse(it) ?: return }
        change { it.copy(asset = asset) }
    }

    /** The recipient field now reads [text]. */
    fun onToChange(text: String) = change { it.copy(to = text) }

    /** Sends to the wallet's account at [address], its EIP-55 form. */
    fun onPickAccount(address: String) = change { it.copy(to = address) }

    /** Resolves the recipient again, after a lookup that could not be verified. */
    fun onRetryLookup() = change { it.copy(lookupAttempt = it.lookupAttempt + 1) }

    /** The amount field now reads [text], in the asset's units. */
    fun onAmountChange(text: String) = change { it.copy(amount = text) }

    /**
     * Fills in the chosen asset's whole verified balance. For the network's currency that leaves
     * nothing for the fee, which the quote then says. In lenient mode, the network's currency is
     * filled in less the most the fee can be and what the account's transactions not mined yet may
     * still take ([DraftTransfer.mostNative]): a draft has no quote to say the fee does not fit. A
     * token's is its balance, verified or last verified.
     */
    fun onMax() {
        val model = model.value ?: return
        val option = model.selected ?: return
        // Composing, and no review being quoted: Max changes what a quote would be made for.
        if ((step.value as? Step.Composing)?.quoting != false) return
        // Lenient Max only on a balance last verified: once it is verified, a quote says whether the fee fits.
        if (!model.lenient || option.asset != TransferAsset.Native || !option.lastKnown) {
            val balance = option.balance ?: return
            change { it.copy(amount = balance.formatUnits(option.decimals)) }
            return
        }
        val level = model.inputs.fee
        job?.cancel()
        job = viewModelScope.launch {
            val most = try {
                draftTransfer.mostNative(from, level)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // the use case promises not to throw; the backstop
            }
            // Not through change(), which would cancel this very job; a review asked for meanwhile
            // cancelled it, and a change of the inputs too.
            if ((step.value as? Step.Composing)?.quoting != false) return@launch
            when {
                most == null -> step.value = Step.Composing(error = maxUnknown())
                most.isZero -> step.value = Step.Composing(error = maxNothingLeft())
                else -> {
                    inputs.update { it.copy(amount = most.formatUnits(NATIVE_DECIMALS)) }
                    step.value = Step.Composing()
                }
            }
        }
    }

    /** Chooses the fee [level]. */
    fun onFeeChange(level: FeeLevel) = change { it.copy(fee = level) }

    /** Quotes the transfer as composed and, when the quote stands, shows it for review. */
    fun review() {
        val current = step.value as? Step.Composing ?: return
        if (current.quoting) return
        val model = model.value ?: return
        val request = requestOf(model) ?: return
        val account = (model.sender as? Sender.From)?.account ?: return
        // Whatever ran before (a lenient Max being worked out) is from before this review.
        job?.cancel()
        step.value = Step.Composing(quoting = true)
        hiddenWhileQuoting = false
        job = viewModelScope.launch {
            val next = quoted(request, unlock = null, SendFrom(account.label, display), model.accounts)
            if (step.value != Step.Composing(quoting = true)) return@launch
            // Made while hidden: it is from before the screen came back, so it is made again.
            step.value = if (next is Step.Reviewing) next.copy(hidden = hiddenWhileQuoting) else next
            requoteIfHidden()
        }
    }

    /**
     * Back from the review to the compose step: the quote and the password go. Not while signing,
     * nor after a broadcast no peer answered: those bytes may be out there, and only a retry of
     * them, never a new quote, may send them again.
     */
    fun edit() {
        when (val current = step.value) {
            is Step.Reviewing -> if (current.signing || current.notSent?.canRetry == true) return
            // A draft's: not while it is signed and queued, which leaving would only hide.
            is Step.Drafting -> if (current.signing) return
            else -> return
        }
        job?.cancel()
        step.value = Step.Composing()
    }

    /** Quotes the reviewed transfer again: fresh fees, nonce and balances. */
    fun refresh() = refresh(note = null)

    /** [refresh], saying [note] under the unlock once the new quote is shown. */
    private fun refresh(note: String?) {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle) return
        step.value = current.copy(refreshing = true, hidden = false, unlock = current.unlock.copy(error = null))
        job = viewModelScope.launch {
            val model = model.value
            val sender = (model?.sender as? Sender.From)?.let { SendFrom(it.account.label, display) } ?: current.sender
            val next = quoted(current.request, unlock = current.unlock, sender, model?.accounts ?: current.accounts)
            step.update { latest ->
                when {
                    latest !is Step.Reviewing || !latest.refreshing -> latest
                    // The password typed meanwhile stays: it is the same review, hidden since or not.
                    next is Step.Reviewing -> latest.copy(
                        request = next.request,
                        quote = next.quote,
                        quotedAt = next.quotedAt,
                        sender = next.sender,
                        accounts = next.accounts,
                        refreshing = false,
                        unlock = latest.unlock.copy(error = note),
                    )
                    else -> next
                }
            }
            // Hidden and shown again while it was made: it is from before, so it is made again.
            requoteIfHidden()
        }
    }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = step.update { current ->
        when {
            current is Step.Reviewing && !current.signing && current.notSent == null ->
                current.copy(unlock = current.unlock.copy(password = WalletPassword(text), error = null))
            current is Step.Drafting && !current.signing ->
                current.copy(unlock = current.unlock.copy(password = WalletPassword(text), error = null))
            else -> current
        }
    }

    /**
     * Signs the reviewed transfer with the typed wallet password, and broadcasts it; a lenient draft
     * is signed and queued instead, never broadcast from here.
     */
    fun sign() {
        (step.value as? Step.Drafting)?.let { draft ->
            val password = draft.unlock.password
            if (draft.signing || password.text.isEmpty()) return
            return queue(draft, Unlock.WithPassword(password))
        }
        val current = step.value as? Step.Reviewing ?: return
        val password = current.unlock.password
        if (!current.idle || password.text.isEmpty() || requoted(current)) return
        sign(current, Unlock.WithPassword(password))
    }

    /**
     * Signs the reviewed transfer with a fingerprint or face, and broadcasts it; a lenient draft is
     * signed and queued instead, with no deadline on the prompt: a draft is old by definition, and
     * the minute applies to the check before sending (docs/lenient-mode.md, *The draft's review*).
     */
    fun signWithBiometrics() {
        (step.value as? Step.Drafting)?.let { draft ->
            if (draft.signing || !draft.unlock.biometrics) return
            val amount = exactAmount(draft.draft.amount, draft.request.option.decimals, draft.request.option.symbol)
            // The recipient in full: the prompt is the last thing read before the key signs.
            val reason = "Sign and queue $amount to ${Eip55.format(draft.draft.to)}"
            return queue(draft, Unlock.WithBiometrics(reason, notAfter = null))
        }
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle || !current.unlock.biometrics || requoted(current)) return
        // The prompt gets what is left of the quote's age: past it, nothing is signed, and it is made again.
        val notAfter = promptDeadline(current.quotedAt) ?: return refresh(note = PROMPT_TOO_LATE)
        val amount = exactAmount(current.quote.amount, current.request.option.decimals, current.request.option.symbol)
        // The recipient in full: the prompt is the last thing read before the key signs.
        sign(current, Unlock.WithBiometrics("Send $amount to ${Eip55.format(current.quote.to)}", notAfter))
    }

    /** Whether [review]'s quote is too old to sign, and so is being made again instead. */
    private fun requoted(review: Step.Reviewing): Boolean {
        // Quoted before the screen was last hidden: never signed as it was.
        if (review.hidden) {
            refresh(note = null)
            return true
        }
        if (review.quotedAt.elapsedNow() <= QUOTE_MAX_AGE) return false
        refresh(note = STALE_QUOTE)
        return true
    }

    /** Broadcasts the transfer signed but not sent again, byte for byte. */
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
     * The screen stopped being shown: the password goes (CLAUDE.md, *Trust*; rule 8), and the
     * review is quoted again when it is shown again ([onShown]). A signature already running
     * finishes, and its outcome is shown when it lands.
     */
    fun onHidden() {
        shown = false
        if ((step.value as? Step.Composing)?.quoting == true) hiddenWhileQuoting = true
        step.update { current ->
            when (current) {
                is Step.Reviewing ->
                    current.copy(unlock = current.unlock.copy(password = WalletPassword.EMPTY), hidden = true)
                // A draft is not quoted again when shown: only the password goes.
                is Step.Drafting -> current.copy(unlock = current.unlock.copy(password = WalletPassword.EMPTY))
                else -> current
            }
        }
    }

    /**
     * The screen is shown (again): a review left while hidden is quoted anew, now or, when a
     * signature is running, once it lands without having sent anything.
     */
    fun onShown() {
        shown = true
        requoteIfHidden()
    }

    private fun requoteIfHidden() {
        val current = step.value as? Step.Reviewing ?: return
        if (shown && current.hidden && current.idle) refresh()
    }

    override fun onCleared() {
        // The screen is gone, and the password with it.
        onHidden()
    }

    private fun sign(review: Step.Reviewing, unlock: Unlock) {
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        val unlocked = review.unlock.copy(password = WalletPassword.EMPTY, error = null)
        step.value = review.copy(signing = true, unlock = unlocked)
        job = viewModelScope.launch {
            val sending = safely { sendTransfer(review.quote, unlock) }
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
            if ((sending as? TransferSending.NotSigned)?.signing == Signing.TimedOut) refresh(note = PROMPT_TIMED_OUT)
            // Hidden while it ran and nothing was sent: the quote is from before, so it is made again.
            requoteIfHidden()
        }
    }

    /**
     * Signs [draft] and queues it ([QueueTransfer]): nothing is broadcast. Once queued, the status
     * of its id follows it; another transaction queued from the account meanwhile returns to the
     * compose step, which says so, with its status. A signature the outbox could not keep is
     * dropped, and said.
     */
    private fun queue(draft: Step.Drafting, unlock: Unlock) {
        // Sign while syncing turned off, or the network, since the draft was made: it follows strict
        // mode now, and is not signed (decision 10 keeps only what is queued already).
        if (model.value?.lenient != true) {
            step.value = Step.Composing(error = NO_LONGER_LENIENT)
            return
        }
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        val unlocked = draft.unlock.copy(password = WalletPassword.EMPTY, error = null)
        step.value = draft.copy(signing = true, unlock = unlocked)
        job = viewModelScope.launch {
            // To its end even when the screen is left meanwhile: a signature is kept or dropped, never half.
            val queuing = withContext(NonCancellable) {
                try {
                    queueTransfer(draft.draft, unlock)
                } catch (e: Exception) {
                    null // the use case promises not to throw; the backstop
                }
            }
            step.update { latest ->
                if (latest !is Step.Drafting) return@update latest
                when (queuing) {
                    is TransferQueuing.Queued -> Step.Queued(queuing.queued.id)
                    is TransferQueuing.NotSigned -> latest.copy(
                        signing = false,
                        unlock = latest.unlock.copy(
                            error = notSignedText(queuing.signing, QUEUE_FAILED),
                            biometrics = latest.unlock.biometrics && biometricsStillOffered(queuing.signing),
                        ),
                    )
                    is TransferQueuing.AccountBusy -> Step.Composing(
                        error = QUEUED_FIRST,
                        earlier = queuing.queued,
                        queued = true,
                    )
                    TransferQueuing.NotSaved ->
                        latest.copy(signing = false, unlock = latest.unlock.copy(error = NOT_KEPT))
                    null -> latest.copy(signing = false, unlock = latest.unlock.copy(error = QUEUE_FAILED))
                }
            }
        }
    }

    /**
     * The review of [request], quoted now, with [unlock] (a new one when null), or the compose
     * step saying why there is none. A name is resolved again first: what is signed is the
     * address the name has now, and one that moved is said, not signed. In lenient mode, a quote
     * refused as still syncing is drafted instead ([drafted]).
     */
    private suspend fun quoted(request: Request, unlock: UnlockStep?, sender: SendFrom, accounts: List<Account>): Step {
        val to = when (val again = resolveAgain(request.to, ::lookUp)) {
            is ResolvedAgain.Same -> again.found // its block is this resolution's
            is ResolvedAgain.Changed -> {
                inputs.update { it.copy(lookupAttempt = it.lookupAttempt + 1) }
                return Step.Composing(error = again.message)
            }
        }
        // Its age runs from its first read, not from its answer: the figures are as old as that.
        val readFrom = timeSource.markNow()
        val quoting = try {
            quoteTransfer(from, request.to.address, request.option.asset, request.amount, request.inputs.fee)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
        }
        if (quoting !is TransferQuoting.Quoted) {
            if (quoting.syncing && model.value?.lenient == true) return drafted(request.copy(to = to), sender, accounts)
            return refused(quoting, request.option)
        }
        return Step.Reviewing(
            request.copy(to = to),
            quoting.quote,
            readFrom,
            unlock ?: UnlockStep(biometrics = biometricsOffered()),
            sender,
            accounts,
        )
    }

    /**
     * The compose step saying why [quoting] is no quote; an earlier transaction's status offered
     * where one held it back.
     */
    private fun refused(quoting: TransferQuoting, option: AssetOption): Step.Composing = Step.Composing(
        error = refusal(quoting, option),
        earlier = (quoting as? TransferQuoting.EarlierMaybeSent)?.hash,
        simpleError = (quoting as? TransferQuoting.Reverts)?.let { simpleReverts(option) },
        queued = (quoting as? TransferQuoting.EarlierMaybeSent)?.queued == true,
    )

    /** Whether this strict refusal only waits for the light client to catch up: what a lenient draft stands in for. */
    private val TransferQuoting.syncing: Boolean
        get() = this == TransferQuoting.Syncing ||
            (this is TransferQuoting.Unavailable && reason == ReadUnavailable.SYNCING)

    /**
     * [request] drafted on the state the light client verified last (docs/lenient-mode.md, *What a
     * draft is built on*), for the draft's review; or the compose step saying why it is not: a
     * refusal in strict mode's words, what it waits for, or what the account has queued. A name is
     * not resolved for a draft (decision 8): one typed waits for the light client, as in strict mode.
     */
    private suspend fun drafted(request: Request, sender: SendFrom, accounts: List<Account>): Step {
        if (request.to.name != null) return Step.Composing(error = NO_NAME_IN_A_DRAFT)
        val option = request.option
        val drafting = try {
            draftTransfer(from, request.to.address, option.asset, request.amount, request.inputs.fee)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The use case promises not to throw; the backstop.
            TransferDrafting.Refused(TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN))
        }
        return when (drafting) {
            is TransferDrafting.Drafted -> Step.Drafting(
                request,
                drafting.draft,
                UnlockStep(biometrics = biometricsOffered()),
                sender,
                accounts,
            )
            is TransferDrafting.Refused -> refused(drafting.quoting, option)
            is TransferDrafting.Waits -> Step.Composing(error = waitsText(drafting.missing))
            is TransferDrafting.AccountBusy ->
                Step.Composing(error = QUEUED_FIRST, earlier = drafting.queued, queued = true)
        }
    }

    /** What a draft waits for: [missing], which the light client never verified on this device. */
    private fun waitsText(missing: DraftInput): String {
        val what = when (missing) {
            DraftInput.BALANCE -> "this account's balance"
            DraftInput.NONCE -> "this account's count of transactions, which its next nonce comes from,"
            DraftInput.FEES -> "${network.displayName}'s fees"
            // No Safe is drafted here.
            DraftInput.SAFE -> "this Safe's owners and nonce"
        }
        return "The light client is still syncing, and $what has never been verified on this device, so nothing " +
            "can be prepared from it yet. The review waits for the light client to catch up."
    }

    /** Why lenient Max cannot be told: the balance, the count of transactions or the fees were never verified here. */
    private fun maxUnknown(): String = "Max can't be worked out yet: this account's balance, its count of " +
        "transactions or ${network.displayName}'s fees have not been verified on this device. Enter an amount, " +
        "or wait for the light client to catch up."

    /** Why lenient Max fills in nothing: the most the fee can be, and the transfers not mined yet, take it all. */
    private fun maxNothingLeft(): String = "Nothing is left to send once the most the fee can be, and what " +
        "transfers not mined yet may take, are set aside."

    /** Applies [change] to the compose step's inputs: only while composing, dropping a quote in flight. */
    private fun change(edit: (Inputs) -> Inputs) {
        if (step.value !is Step.Composing) return
        job?.cancel()
        inputs.update(edit)
        step.value = Step.Composing()
    }

    /**
     * [send], to its end even when the screen is left meanwhile: a transfer signed is recorded and
     * broadcast as one step, which leaving cannot cut in half (its outcome is then not shown).
     */
    private suspend fun safely(send: suspend () -> TransferSending): TransferSending? =
        withContext(NonCancellable) {
            try {
                send()
            } catch (e: Exception) {
                // The ports promise not to throw; the backstop. Under NonCancellable a cancellation
                // can only be the callee's own, which is no outcome either.
                null
            }
        }

    private fun modelOf(
        list: AccountList,
        value: AccountValue,
        inputs: Inputs,
        lookup: RecipientState,
        lenient: Boolean,
    ): Model {
        // The sender's network's accounts only: the same address on another network is another account.
        val accounts = (list as? AccountList.Loaded)?.accounts.orEmpty().filter { it.network == network }
        val sender = when (list) {
            is AccountList.Unreadable -> Sender.Cannot(LIST_UNAVAILABLE)
            is AccountList.Loaded -> {
                val account = accounts.firstOrNull { it.address == from }
                when {
                    account == null -> Sender.Cannot(NOT_IN_WALLET)
                    account.kind == AccountKind.SAFE -> Sender.Cannot(SAFE)
                    account.custody != AccountCustody.InKeyStore -> Sender.Cannot(WATCH_ONLY)
                    else -> Sender.From(account)
                }
            }
        }
        // Lenient: a balance last verified is offered too, labelled, for a draft.
        val options = assetOptions(value, inputs.asset, network, lastKnown = lenient)
        return Model(
            sender = sender,
            accounts = accounts,
            currency = value.currency,
            options = options,
            selected = options.firstOrNull { it.contract == inputs.asset },
            tokensRead = value.tokensRead,
            inputs = inputs,
            lookup = lookup,
            lenient = lenient,
        )
    }

    private fun render(model: Model?, step: Step): SendUiState {
        val network = network.displayName
        val composing = when (step) {
            is Step.Sent -> return SendUiState.Sent(network, step.hash)
            is Step.Queued -> return SendUiState.Queued(network, step.id)
            // A review stands on its quote, whatever the account list says meanwhile: a refresh
            // quotes it again, and the signature finds whether the key is still here.
            is Step.Reviewing -> return review(model?.accounts ?: step.accounts, step)
            // A draft's likewise, on its draft.
            is Step.Drafting -> return draftReview(model?.accounts ?: step.accounts, step)
            is Step.Composing -> step
        }
        model ?: return SendUiState.Loading(network)
        val account = when (val sender = model.sender) {
            is Sender.Cannot -> return SendUiState.CannotSend(network, sender.message)
            is Sender.From -> sender.account
        }
        return compose(model, SendFrom(account.label, display), composing)
    }

    private fun compose(model: Model, sender: SendFrom, step: Step.Composing): SendUiState.Compose {
        val inputs = model.inputs
        val at = now()
        val text = inputs.to.trim()
        // The lookup of what is typed now; one of an earlier text is no answer about it.
        val lookup = model.lookup.takeIf { it.text == text }
        val result = (lookup as? RecipientState.Done)?.result
        val (note, isError) = recipientNote(text, lookup)
        val selected = model.selected
        val amount = typedAmount(inputs.amount, selected)
        val form = TransferForm(
            network = network.displayName,
            toLabel = addressFieldLabel(network),
            toHint = recipientHint(network),
            from = sender,
            assets = model.options.map { it.choice(at) },
            asset = selected?.choice(at),
            to = inputs.to,
            accounts = model.accounts.filter { it.address != from }
                .map { AccountChoice(it.label, Eip55.format(it.address)) },
            amount = inputs.amount,
            assetNote = when {
                selected != null -> null
                !model.tokensRead -> "Checking this account's tokens…"
                else -> "The token asked for is not among this account's tokens: choose another asset."
            },
            recipient = (result as? RecipientLookup.Found)?.let { recipientItem(it, model.accounts, selected) },
            toNote = note,
            toError = isError,
            canRetryLookup = result is RecipientLookup.Unavailable,
            retryLookupWaits = retryLookupWaits(result),
            amountError = amount.error,
            amountHint = amountHint(amount.units, selected, model.currency, network),
            canMax = selected?.balance != null && !step.quoting,
            quoting = step.quoting,
            error = step.error,
            simpleError = step.simpleError,
            canReview = !step.quoting && requestOf(model) != null,
        )
        return SendUiState.Compose(
            form,
            inputs.fee,
            earlier = step.earlier,
            earlierLabel = if (step.queued) OPEN_QUEUED else "Open the earlier transaction",
        )
    }

    private fun review(accounts: List<Account>, step: Step.Reviewing): SendUiState.Review {
        val quote = step.quote
        val option = step.request.option
        val native = network.nativeSymbol
        val tokenBalance = quote.tokenBefore?.let { before ->
            val after = quote.tokenAfter ?: return@let null
            BalanceChange(
                what = "${option.symbol} balance",
                before = exactAmount(before, option.decimals, option.symbol),
                after = exactAmount(after, option.decimals, option.symbol),
                verifiedAt = verifiedAt(quote.tokenBalanceBlock),
            )
        }
        val nativeBalance = BalanceChange(
            what = "$native balance" + if (tokenBalance != null) ", which pays the fee" else "",
            short = "$native balance",
            before = exactAmount(quote.nativeBefore, NATIVE_DECIMALS, native),
            after = "at least " + exactAmount(quote.nativeAfterAtLeast, NATIVE_DECIMALS, native),
            verifiedAt = verifiedAt(quote.balanceBlock),
        )
        val transaction = quote.transaction
        val ran = quote.simulationBlock?.let { "at block ${grouped(it)}" } ?: "at the head"
        return SendUiState.Review(
            network = network.displayName,
            from = step.sender,
            amount = exactAmount(quote.amount, option.decimals, option.symbol),
            asset = option.choice(),
            recipient = recipientItem(step.request.to, accounts, option),
            maxFee = exactAmount(quote.maxFee, NATIVE_DECIMALS, native),
            feeDetail = feeDetail(quote.level, transaction, quote.feeBlock),
            balances = listOfNotNull(tokenBalance, nativeBalance),
            simulation = if (tokenBalance != null) {
                "The token's transfer, run as a verified call from this account $ran, goes through."
            } else {
                "The transfer, run as a verified call from this account $ran, goes through."
            },
            unlock = step.unlock,
            pending = pendingText(quote.pendingTokenAmount, quote.pendingCost, quote.heldByOrders, option),
            refreshing = step.refreshing,
            signing = step.signing,
            notSent = step.notSent?.let {
                notSentItem(it.broadcast, it.unverified, what = "transfer", maybeSent = it.transfer.maybeSent)
            },
        )
    }

    /**
     * The review of a lenient draft (docs/lenient-mode.md, *The draft's review*): each figure with
     * the block it was last verified at and its age against [now], the balances after "if nothing
     * changed", no simulation and no fiat value.
     */
    private fun draftReview(accounts: List<Account>, step: Step.Drafting): SendUiState.DraftReview {
        val draft = step.draft
        val basis = draft.builtOn
        val option = step.request.option
        val native = network.nativeSymbol
        val at = now()
        fun age(asOf: Long?) = asOf?.let { "as of ${agoText(it, at)}" } ?: "as last verified"
        val tokenBalance = draft.tokenBefore?.let { before ->
            val after = draft.tokenAfter ?: return@let null
            BalanceChange(
                what = "${option.symbol} balance",
                before = exactAmount(before, option.decimals, option.symbol),
                after = exactAmount(after, option.decimals, option.symbol) + IF_NOTHING_CHANGED,
                verifiedAt = lastVerifiedAt(basis.tokenBalanceBlock),
                age = age(basis.tokenBalanceAsOf),
            )
        }
        val nativeBalance = BalanceChange(
            what = "$native balance" + if (tokenBalance != null) ", which pays the fee" else "",
            short = "$native balance",
            before = exactAmount(draft.nativeBefore, NATIVE_DECIMALS, native),
            after = "at least " + exactAmount(draft.nativeAfterAtLeast, NATIVE_DECIMALS, native) + IF_NOTHING_CHANGED,
            verifiedAt = lastVerifiedAt(basis.balanceBlock),
            age = age(basis.balanceAsOf),
        )
        val transaction = draft.transaction
        val oldest = basis.oldestAsOf?.let { agoText(it, at) } ?: "an earlier block"
        return SendUiState.DraftReview(
            network = network.displayName,
            from = step.sender,
            amount = exactAmount(draft.amount, option.decimals, option.symbol),
            asset = option.choice(),
            recipient = recipientItem(step.request.to, accounts, option),
            preparedOn = "Prepared on data from $oldest. It's checked on chain again before it's sent.",
            maxFee = exactAmount(draft.maxFee, NATIVE_DECIMALS, native),
            feeDetail = "${levelName(draft.level)} fee: ${grouped(transaction.gasLimit)} gas, the most this " +
                "transfer is signed for, × at most ${transaction.maxFeePerGas.formatUnits(GWEI_DECIMALS)} gwei, " +
                "from the fee history last verified up to block ${grouped(basis.feeBlock)}. Only the gas used, " +
                "at what the block charges, is paid.",
            feeAge = "Fees ${age(basis.feeAsOf)}",
            nonce = "Nonce ${transaction.nonce}, after this account's count of mined transactions last verified at " +
                "block ${grouped(basis.nonceBlock)}",
            nonceAge = "Nonce ${transaction.nonce}, counted ${age(basis.nonceAsOf)}",
            balances = listOfNotNull(tokenBalance, nativeBalance),
            unlock = step.unlock,
            pending = pendingText(draft.pendingTokenAmount, draft.pendingCost, draft.heldByOrders, option),
            signing = step.signing,
        )
    }

    /** The block a draft's figure was last verified at, as its review says it. */
    private fun lastVerifiedAt(block: Long?) = block?.let { "last verified at block ${grouped(it)}" } ?: "last verified"

    /** What this account's earlier transfers, not mined yet, and its CoW orders may still take, when anything. */
    private fun pendingText(
        pendingTokenAmount: UInt256?,
        pendingCost: UInt256,
        heldByOrders: UInt256?,
        option: AssetOption,
    ): String? {
        val native = network.nativeSymbol
        val parts = listOfNotNull(
            pendingTokenAmount?.takeUnless { it.isZero }?.let { exactAmount(it, option.decimals, option.symbol) },
            pendingCost.takeUnless { it.isZero }?.let { "up to " + exactAmount(it, NATIVE_DECIMALS, native) },
        )
        val held = heldByOrders?.takeUnless { it.isZero }?.let { exactAmount(it, option.decimals, option.symbol) }
        val sentences = listOfNotNull(
            parts.takeIf { it.isNotEmpty() }?.let {
                "Earlier transfers from this account, not mined yet, may still take ${it.joinToString(" and ")}."
            },
            held?.let { "This account's CoW orders that may still fill hold back $it." },
        )
        if (sentences.isEmpty()) return null
        return sentences.joinToString(" ") + " The balance after counts it."
    }

    private fun recipientItem(found: RecipientLookup.Found, accounts: List<Account>, asset: AssetOption?) =
        recipientItem(found, accounts) { address ->
            when (address) {
                from -> "The account you send from: only the fee is spent."
                asset?.contract -> "The token's own contract: nothing sent to it can ever be moved out."
                else -> null
            }
        }

    /** The compose step's transfer, when it is complete and the recipient is known: what a quote is asked for. */
    private fun requestOf(model: Model): Request? {
        if (model.sender !is Sender.From) return null
        val lookup = model.lookup as? RecipientState.Done ?: return null
        if (lookup.text != model.inputs.to.trim()) return null
        val to = lookup.result as? RecipientLookup.Found ?: return null
        val option = model.selected ?: return null
        val amount = typedAmount(model.inputs.amount, option).units ?: return null
        return Request(model.inputs, to, option, amount)
    }

    /** A transfer that reverts, as simple mode says it: no block named (docs/simple-ui.md). */
    private fun simpleReverts(option: AssetOption): String = if (option.asset == TransferAsset.Native) {
        "Checked on chain, this transfer fails: the recipient refuses it. Nothing was signed."
    } else {
        "Checked on chain, the token's contract refuses this transfer. Nothing was signed."
    }

    private fun refusal(quoting: TransferQuoting, option: AssetOption): String = when (quoting) {
        is TransferQuoting.Quoted -> error("a quote is no refusal")
        TransferQuoting.ToZeroAddress -> ZERO_ADDRESS
        TransferQuoting.ToTokenContract -> "${option.symbol}'s own contract: nothing sent to it can ever be moved " +
            "out, so it is refused."
        TransferQuoting.NothingToSend -> "There is nothing to send: enter an amount above zero."
        TransferQuoting.InsufficientFunds -> if (option.asset == TransferAsset.Native) {
            "Not enough ${network.nativeSymbol} for this amount and the most the fee can be: send less, to leave " +
                "room for the fee. Transfers not mined yet count too."
        } else {
            "Not enough ${network.nativeSymbol} for the most the fee can be. Transfers not mined yet count too."
        }
        TransferQuoting.InsufficientToken -> "This account holds less ${option.symbol} than that, once what its " +
            "transfers not mined yet and its CoW orders that may still fill can take is set aside."
        is TransferQuoting.Infeasible -> "This transfer cannot succeed: ${quoting.detail}"
        is TransferQuoting.Reverts -> {
            val at = quoting.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty()
            if (option.asset == TransferAsset.Native) {
                "The transfer reverts, run as a verified call$at: the recipient refuses it. Nothing was signed."
            } else {
                "The token's contract refuses this transfer, run as a verified call$at. Nothing was signed."
            }
        }
        TransferQuoting.NotAToken ->
            "${option.symbol}'s contract does not answer as a token: nothing can be sent with it."
        TransferQuoting.Refused -> "The light client on this device cannot quote this transfer."
        TransferQuoting.Syncing -> "The light client is not synced yet: try again in a moment."
        is TransferQuoting.Unavailable -> when (quoting.reason) {
            ReadUnavailable.NO_ENGINE -> "There is no light client on this device, so nothing can be sent."
            ReadUnavailable.NETWORK_OFF -> "The network's light client is not running, so nothing can be sent."
            ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so nothing can be quoted."
            ReadUnavailable.SYNCING -> "The light client is still syncing, so nothing can be quoted yet."
            ReadUnavailable.UNPROVEN -> "${figureText(quoting.figure, "of the quote")} could not be verified this " +
                "time: try again."
            ReadUnavailable.REFUSED -> "The light client on this device cannot quote this transfer."
        }
        // Queued in lenient mode: one per account, in either mode (docs/lenient-mode.md, decision 6).
        is TransferQuoting.EarlierMaybeSent -> if (quoting.queued) {
            QUEUED_FIRST
        } else {
            "An earlier transaction from this account may still be on its way: no peer answered when it was " +
                "broadcast, and no block has it yet. Try it again from its status rather than sign a new one, which " +
                "could pay twice."
        }
    }

    /** What [text] names, resolved ([ResolveRecipient]); never throws. */
    private suspend fun lookUp(text: String): RecipientLookup = try {
        resolveRecipient(network, recipientText(text))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RecipientLookup.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
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
        const val GWEI_DECIMALS = 9
        const val IF_NOTHING_CHANGED = ", if nothing changed"
        const val SEND_FAILED =
            "Punicus could not finish this transfer. Check the account's balance before you send again."
        const val QUEUE_FAILED = "Punicus could not finish signing this transfer. Nothing was queued or sent."
        const val NOT_KEPT = "Signed, but the outbox could not keep it, so the signature was dropped: nothing was " +
            "queued or sent. Try again."
        const val NO_LONGER_LENIENT = "Sign while syncing, or the network, was turned off since this was " +
            "prepared, so it was not signed: review it again."
        const val NO_NAME_IN_A_DRAFT = "The light client is still syncing, and a name is resolved only once it " +
            "has caught up: to prepare this transfer now, enter the address, or pick one of your accounts."
        const val LIST_UNAVAILABLE =
            "Your account list cannot be read right now, so nothing can be sent. Punicus keeps trying."
        const val NOT_IN_WALLET = "This account is not in your wallet any more."
        const val SAFE =
            "Sending from a Safe takes a Safe transaction its owners sign: Send on the Safe's own screen proposes one."
        const val WATCH_ONLY =
            "This account is watch-only: its key is not on this device, so nothing can be sent from it."
    }
}
