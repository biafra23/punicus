package eth.serotine.ui.safepropose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.AccountValue
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.EncodeSigningRequest
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveSafe
import eth.serotine.domain.usecase.ProposeSafeTx
import eth.serotine.domain.usecase.RecipientLookup
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.SafeCallSimulation
import eth.serotine.domain.usecase.SafeReading
import eth.serotine.domain.usecase.SafeTxProposal
import eth.serotine.domain.usecase.SafeTxProposing
import eth.serotine.domain.usecase.SafeTxRefusal
import eth.serotine.domain.usecase.SafeTxSignCheck
import eth.serotine.domain.usecase.SafeTxSigning
import eth.serotine.domain.usecase.SignSafeTx
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.AssetOption
import eth.serotine.ui.components.CHECK_TOOK_THE_TIME
import eth.serotine.ui.components.DecodingToken
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.MIN_PROMPT_TIME
import eth.serotine.ui.components.PROMPT_TIMED_OUT
import eth.serotine.ui.components.PROMPT_TOO_LATE
import eth.serotine.ui.components.QUOTE_MAX_AGE
import eth.serotine.ui.components.RecipientState
import eth.serotine.ui.components.ResolvedAgain
import eth.serotine.ui.components.STALE_QUOTE
import eth.serotine.ui.components.SafeBalanceChange
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.TransferForm
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.ZERO_ADDRESS
import eth.serotine.ui.components.addressFieldLabel
import eth.serotine.ui.components.amountHint
import eth.serotine.ui.components.assetOptions
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.components.exactAmount
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.promptDeadline
import eth.serotine.ui.components.recipientHint
import eth.serotine.ui.components.recipientItem
import eth.serotine.ui.components.recipientNote
import eth.serotine.ui.components.recipientStates
import eth.serotine.ui.components.recipientText
import eth.serotine.ui.components.redatedWhile
import eth.serotine.ui.components.resolveAgain
import eth.serotine.ui.components.retryLookupWaits
import eth.serotine.ui.components.safeTxActionItem
import eth.serotine.ui.components.safeTxRefusalText
import eth.serotine.ui.components.shareItem
import eth.serotine.ui.components.simpleSafeTxRefusalText
import eth.serotine.ui.components.typedAmount
import eth.serotine.ui.components.verifiedAt
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The ViewModel of Send for a Safe (docs/wallet-ux.md, screens 7 and 16): a transfer from the Safe
 * at [safe] on [network], composed in Send's form — the asset ([asset] the token asked for, null
 * for the network's currency), the recipient typed or picked and resolved ([ResolveRecipient]),
 * the amount in token units; no fee, which the account that executes it pays — then proposed from
 * verified state ([ProposeSafeTx]: at the Safe's next nonce, the Safe's balance less what those
 * queued before it move, its call run as a verified call from the Safe where the engine will) for
 * review, and signed by an owner held here ([SignSafeTx]), which queues it.
 *
 * What is signed is the proposal under review, and a proposal is never signed stale: it is made
 * when the review is entered, again on Refresh, when the screen is shown again after it was not,
 * and when it is older than [QUOTE_MAX_AGE] at the tap that would sign it ([timeSource] tells); its
 * recipient's name is resolved again with every one. Nothing is queued until it is signed, so a
 * proposal given up takes no nonce. A price never reaches it.
 *
 * One of the ViewModels the [WalletPassword] passes through (CLAUDE.md, *Trust*): it holds it in the
 * review's state while the field is shown, hands it to the signer in an [Unlock], and never logs,
 * saves or caches it; it is dropped once used, when the screen is not shown ([onHidden]) and when
 * the screen is gone.
 */
class SafeProposeViewModel(
    private val account: AccountId,
    asset: Address?,
    observeAccounts: ObserveAccounts,
    observeAccountValue: ObserveAccountValue,
    observeSafe: ObserveSafe,
    private val describeKeyStore: DescribeKeyStore,
    private val resolveRecipient: ResolveRecipient,
    private val proposeSafeTx: ProposeSafeTx,
    private val signSafeTx: SignSafeTx,
    private val encodeSigningRequest: EncodeSigningRequest,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val now: () -> Long = ::epochSecondsNow,
) : ViewModel() {
    /** The account this screen is about: its address, on its network. */
    private val safe: Address = account.address

    /** The account's network: everything here is read and sent there. */
    private val network: Network = account.network


    /** What the form has: the token chosen (null: the network's currency), the recipient and amount as typed. */
    private data class Inputs(
        val asset: Address?,
        val to: String = "",
        val amount: String = "",
        val lookupAttempt: Int = 0,
    )

    /** The Safe proposed from, or why nothing can be proposed from it. */
    private sealed interface Sender {
        data object Checking : Sender

        data class From(val account: Account, val reading: SafeReading.Verified) : Sender

        data class Cannot(val message: String) : Sender
    }

    /** Everything the form is derived from. */
    private class Model(
        val sender: Sender,
        val accounts: List<Account>,
        val currency: DisplayCurrency,
        val options: List<AssetOption>,
        val selected: AssetOption?,
        val tokensRead: Boolean,
        val inputs: Inputs,
        val lookup: RecipientState,
    )

    /** A transfer the form can have proposed, exactly as it has it. */
    private data class Request(
        val inputs: Inputs,
        val to: RecipientLookup.Found,
        val option: AssetOption,
        val amount: UInt256,
    )

    /** Where the flow stands. */
    private sealed interface Step {
        data class Composing(
            val proposing: Boolean = false,
            val error: String? = null,
            /** [error] with no block, for simple mode; null where it is [error] itself. */
            val simpleError: String? = null,
        ) : Step

        /**
         * [proposal] of [request], made at [proposedAt], under review, to be signed by [owner];
         * [hidden] once the screen stopped being shown since it was made. [safeAccount] and
         * [accounts] are the wallet's as they were when it was proposed: the review stands on them
         * while the Safe's reading or the list is not there, rather than vanish with a password in
         * it or a signature under way. [unsaved] is the request signed here that the queue could
         * not save: shown for sharing until the review is left (Back asks first: [askingToDrop]),
         * or a new proposal of another transaction replaces it, which says so.
         */
        data class Reviewing(
            val request: Request,
            val proposal: SafeTxProposal,
            val proposedAt: TimeMark,
            val owner: Address,
            val unlock: UnlockStep,
            val safeAccount: Account,
            val accounts: List<Account>,
            val refreshing: Boolean = false,
            val signing: Boolean = false,
            /** While [signing], the Safe is still being checked on chain: nothing is signed yet. */
            val checking: Boolean = false,
            val error: String? = null,
            val hidden: Boolean = false,
            val unsaved: SigningRequest? = null,
            val askingToDrop: Boolean = false,
            /** [error] with no block, for simple mode; null where it is [error] itself. */
            val simpleError: String? = null,
        ) : Step {
            val idle: Boolean
                get() = !refreshing && !signing
        }

        data class Proposed(val hash: String) : Step
    }

    // The checksum once, not on every balance reading.
    private val display = Eip55.format(safe)

    private val inputs = MutableStateFlow(Inputs(asset))
    private val step = MutableStateFlow<Step>(Step.Composing())

    /** The proposal or signature in flight. */
    private var job: Job? = null

    /** Whether the screen is shown now ([onShown], [onHidden]). */
    private var shown = false

    /** Whether the screen was hidden while the first proposal of a review was being made. */
    private var hiddenWhileProposing = false

    /** The typed recipient, resolved: an address at once, a name once the typing paused. */
    private val lookup: Flow<RecipientState> =
        recipientStates(inputs.map { it.to.trim() to it.lookupAttempt }, ::lookUp)

    private val model: StateFlow<Model?> = combine(
        observeAccounts(),
        observeSafe(account),
        observeAccountValue(account),
        inputs,
        lookup,
    ) { list, reading, value, inputs, lookup ->
        modelOf(list, reading, value, inputs, lookup)
    }.stateIn(
        viewModelScope,
        // As on the account screens: back after the timeout, no old balance stands as current.
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        null,
    )

    val uiState: StateFlow<SafeProposeUiState> = combine(model, step) { model, step -> model to step }
        // A balance's age, which simple mode shows, is dated anew every second on the compose step.
        .redatedWhile { (model, step) ->
            step is Step.Composing && model?.options.orEmpty().any { it.provenAt != null }
        }
        .map { (model, step) -> render(model, step) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            SafeProposeUiState.Loading(network.displayName),
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

    /** Fills in the chosen asset's whole verified balance. */
    fun onMax() {
        val option = model.value?.selected ?: return
        val balance = option.balance ?: return
        change { it.copy(amount = balance.formatUnits(option.decimals)) }
    }

    /** Proposes the transfer as composed and, when the proposal stands, shows it for review. */
    fun review() {
        val current = step.value as? Step.Composing ?: return
        if (current.proposing) return
        val model = model.value ?: return
        val request = requestOf(model) ?: return
        val sender = model.sender as? Sender.From ?: return
        step.value = Step.Composing(proposing = true)
        hiddenWhileProposing = false
        job = viewModelScope.launch {
            val next = proposed(request, sender.reading.ownersHeld.firstOrNull(), null, sender.account, model.accounts)
            if (step.value != Step.Composing(proposing = true)) return@launch
            // Made while hidden: it is from before the screen came back, so it is made again.
            step.value = if (next is Step.Reviewing) next.copy(hidden = hiddenWhileProposing) else next
            reproposeIfHidden()
        }
    }

    /**
     * Back from the review to the form: the proposal and the password go. Not while signing (the
     * Safe's check before it is not signing yet, and stops); and with a signature made here that
     * the queue could not save, only once the user said to drop it ([dropUnsaved]): it is kept
     * nowhere else.
     */
    fun edit() {
        var current = step.value as? Step.Reviewing ?: return
        if (current.signing && !current.checking) return
        if (current.checking) {
            // The check stops first, whatever is asked next: no key is asked for after Back.
            job?.cancel()
            current = current.copy(signing = false, checking = false)
            step.value = current
        }
        if (current.unsaved != null) {
            step.value = current.copy(askingToDrop = true)
            return
        }
        job?.cancel()
        step.value = Step.Composing()
    }

    /** Back to the form after all, dropping the signature the queue could not save. */
    fun dropUnsaved() {
        val current = step.value as? Step.Reviewing ?: return
        if (current.signing || !current.askingToDrop) return
        job?.cancel()
        step.value = Step.Composing()
    }

    /** Stays in the review, with the signature the queue could not save, to share it. */
    fun keepUnsaved() = step.update { current ->
        if (current is Step.Reviewing) current.copy(askingToDrop = false) else current
    }

    /** Signs as the owner at [address] (EIP-55) instead. */
    fun onOwnerChange(address: String) {
        val owner = Address.parse(address) ?: return
        step.update { current ->
            if (current is Step.Reviewing && current.idle && owner in current.proposal.safe.ownersHeld) {
                current.copy(
                    owner = owner,
                    unlock = current.unlock.copy(error = null),
                    error = null,
                    simpleError = null,
                )
            } else {
                current
            }
        }
    }

    /** Proposes the reviewed transfer again: the Safe's nonce, its queue and its balance anew. */
    fun refresh() = refresh(note = null)

    private fun refresh(note: String?) {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle) return
        val cleared = current.unlock.copy(error = null)
        step.value = current.copy(refreshing = true, hidden = false, error = null, simpleError = null, unlock = cleared)
        job = viewModelScope.launch {
            val accounts = model.value?.accounts ?: current.accounts
            val next = proposed(current.request, current.owner, current.unlock, current.safeAccount, accounts)
            step.update { latest ->
                when {
                    latest !is Step.Reviewing || !latest.refreshing -> latest
                    // The password typed meanwhile stays: it is the same review, hidden since or not. A
                    // signature not saved is of the transaction it was made for: one proposed anew as
                    // another transaction (another nonce) drops it, and says so. A name that moved
                    // proposes nothing new: the review stays, with the signature, saying why.
                    next is Step.Reviewing -> {
                        val unsaved = latest.unsaved?.takeIf { it.hash == next.proposal.request.hash }
                        latest.copy(
                            request = next.request,
                            proposal = next.proposal,
                            proposedAt = next.proposedAt,
                            owner = next.owner,
                            accounts = next.accounts,
                            refreshing = false,
                            unlock = latest.unlock.copy(error = note),
                            unsaved = unsaved,
                            error = if (latest.unsaved != null && unsaved == null) UNSAVED_REPLACED else latest.error,
                            askingToDrop = latest.askingToDrop && unsaved != null,
                        )
                    }
                    // A request signed here that is kept nowhere else is not given up with a
                    // re-proposal that fails: the review stays, saying why, with it to share.
                    latest.unsaved != null && next is Step.Composing ->
                        latest.copy(
                            refreshing = false,
                            error = next.error,
                            simpleError = next.simpleError,
                            unlock = latest.unlock.copy(error = note),
                        )
                    else -> next
                }
            }
            // Hidden and shown again while it was made: it is from before, so it is made again.
            reproposeIfHidden()
        }
    }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = step.update { current ->
        if (current is Step.Reviewing && !current.signing) {
            current.copy(unlock = current.unlock.copy(password = WalletPassword(text), error = null))
        } else {
            current
        }
    }

    /** Signs the reviewed proposal with the typed wallet password, and queues it. */
    fun sign() {
        val current = step.value as? Step.Reviewing ?: return
        val password = current.unlock.password
        if (!current.idle || password.text.isEmpty() || reproposed(current)) return
        sign(current, Unlock.WithPassword(password))
    }

    /** Signs the reviewed proposal with a fingerprint or face, and queues it. */
    fun signWithBiometrics() {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle || !current.unlock.biometrics || reproposed(current)) return
        val option = current.request.option
        val amount = exactAmount(current.request.amount, option.decimals, option.symbol)
        val label = current.safeAccount.label
        // The recipient in full: the prompt is the last thing read before the key signs.
        val to = Eip55.format(current.request.to.address)
        val warning = option.warning?.let { " ($it)" }.orEmpty()
        // The prompt gets what is left of the proposal's age: past it, nothing is signed, and it is made again.
        val notAfter = promptDeadline(current.proposedAt) ?: return refresh(note = PROMPT_TOO_LATE)
        sign(current, Unlock.WithBiometrics("Sign a Safe transaction of $label: send $amount$warning to $to", notAfter))
    }

    /** Whether [review]'s proposal is too old to sign, and so is being made again instead. */
    private fun reproposed(review: Step.Reviewing): Boolean {
        // Made before the screen was last hidden: never signed as it was.
        if (review.hidden) {
            refresh(note = null)
            return true
        }
        if (review.proposedAt.elapsedNow() <= QUOTE_MAX_AGE) return false
        refresh(note = STALE_QUOTE)
        return true
    }

    /**
     * The screen stopped being shown: the password goes (CLAUDE.md, *Trust*; rule 8), and the
     * review is proposed again when it is shown again ([onShown]). A signature already running
     * finishes, and its outcome is shown when it lands; one still checking the Safe stops, its
     * unlock with it, so that no prompt comes up over another screen.
     */
    fun onHidden() {
        shown = false
        if ((step.value as? Step.Composing)?.proposing == true) hiddenWhileProposing = true
        // Only ever the Safe's check before a signature: a refresh in flight finishes.
        if ((step.value as? Step.Reviewing)?.let { it.signing && it.checking } == true) job?.cancel()
        step.update { current ->
            if (current is Step.Reviewing) {
                current.copy(
                    unlock = current.unlock.copy(password = WalletPassword.EMPTY),
                    hidden = true,
                    signing = current.signing && !current.checking,
                    checking = false,
                )
            } else {
                current
            }
        }
    }

    /** The screen is shown (again): a review left while hidden is proposed anew. */
    fun onShown() {
        shown = true
        reproposeIfHidden()
    }

    private fun reproposeIfHidden() {
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
        step.value = review.copy(signing = true, checking = true, error = null, simpleError = null, unlock = unlocked)
        // The check has what is left of the proposal's minute, less the prompt's least time when
        // the prompt signs: past that the signature could not be made in time anyway.
        val checkUntil = (unlock as? Unlock.WithBiometrics)?.notAfter?.let { it - MIN_PROMPT_TIME }
            ?: (review.proposedAt + QUOTE_MAX_AGE)
        job = viewModelScope.launch {
            // The Safe is checked first, which leaving the screen or Back cancels: no key is asked
            // for, and no prompt shown, until it has passed.
            val checked = try {
                signSafeTx.check(review.proposal.request, review.owner, budget = -checkUntil.elapsedNow())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // the use case promises not to throw; the backstop
            }
            // Left while the check came back: nothing is signed. Otherwise the check is over,
            // whatever it came to.
            currentCoroutineContext().ensureActive()
            step.update { if (it is Step.Reviewing && it.signing) it.copy(checking = false) else it }
            val unproven = (checked as? SafeTxSignCheck.Refused)?.refusal ==
                SafeTxRefusal.SafeUnavailable(ReadUnavailable.UNPROVEN)
            if (checkUntil.hasPassedNow() && (checked is SafeTxSignCheck.Ready || unproven)) {
                // The check took the time the proposal had left: nothing is signed, it is made again.
                step.update { if (it is Step.Reviewing && it.signing) it.copy(signing = false) else it }
                refresh(note = CHECK_TOOK_THE_TIME)
                return@launch
            }
            val signing = when (checked) {
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
            step.update { latest ->
                if (latest !is Step.Reviewing) return@update latest
                when (signing) {
                    is SafeTxSigning.Signed -> if (signing.saved) {
                        Step.Proposed("0x" + signing.request.hash.hex)
                    } else {
                        // Not kept here, but signed: shared from this review, it is not lost.
                        latest.copy(signing = false, error = NOT_SAVED, unsaved = signing.request)
                    }
                    is SafeTxSigning.NotSigned -> latest.copy(
                        signing = false,
                        unlock = latest.unlock.copy(
                            error = notSignedText(signing.signing, SIGN_FAILED),
                            biometrics = latest.unlock.biometrics && biometricsStillOffered(signing.signing),
                        ),
                    )
                    is SafeTxSigning.Refused -> latest.copy(
                        signing = false,
                        error = safeTxRefusalText(signing.refusal, network),
                        simpleError = simpleSafeTxRefusalText(signing.refusal),
                    )
                    null -> latest.copy(signing = false, unlock = latest.unlock.copy(error = SIGN_FAILED))
                }
            }
            // The prompt outlived the proposal's age: nothing was signed, and it is made again.
            if ((signing as? SafeTxSigning.NotSigned)?.signing == Signing.TimedOut) refresh(note = PROMPT_TIMED_OUT)
            // Hidden while it ran and nothing was queued: the proposal is from before, so it is made again.
            reproposeIfHidden()
        }
    }

    /**
     * The review of [request], proposed now, signed by [owner] (the first held when that is no
     * owner any more) with [unlock] (a new one when null), or the form saying why there is none.
     * A name is resolved again first: what is proposed is the address the name has now.
     */
    private suspend fun proposed(
        request: Request,
        owner: Address?,
        unlock: UnlockStep?,
        safeAccount: Account,
        accounts: List<Account>,
    ): Step {
        val to = when (val again = resolveAgain(request.to, ::lookUp)) {
            is ResolvedAgain.Same -> again.found
            is ResolvedAgain.Changed -> {
                inputs.update { it.copy(lookupAttempt = it.lookupAttempt + 1) }
                return Step.Composing(error = again.message)
            }
        }
        // Its age runs from its first read, not from its answer: the figures are as old as that. The
        // first is the one after the Safe's check, which may wait a while for a verified reading.
        var readFrom = timeSource.markNow()
        val proposing = try {
            proposeSafeTx(safe, to.address, request.option.asset, request.amount) {
                readFrom = timeSource.markNow()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The use case promises not to throw; the backstop.
            SafeTxProposing.Refused(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN))
        }
        if (proposing !is SafeTxProposing.Proposed) {
            return Step.Composing(error = refusal(proposing, request.option), simpleError = simpleRefusal(proposing))
        }
        val proposal = proposing.proposal
        val held = proposal.safe.ownersHeld
        val signer = owner?.takeIf { it in held } ?: held.firstOrNull()
            ?: return Step.Composing(error = NO_OWNER_HELD)
        return Step.Reviewing(
            request.copy(to = to),
            proposal,
            readFrom,
            signer,
            unlock ?: UnlockStep(biometrics = biometricsOffered()),
            safeAccount,
            accounts,
        )
    }

    /** Applies [edit] to the form's inputs: only while composing, dropping a proposal in flight. */
    private fun change(edit: (Inputs) -> Inputs) {
        if (step.value !is Step.Composing) return
        job?.cancel()
        inputs.update(edit)
        step.value = Step.Composing()
    }

    private fun modelOf(
        list: AccountList,
        reading: SafeReading,
        value: AccountValue,
        inputs: Inputs,
        lookup: RecipientState,
    ): Model {
        // The Safe's network's accounts only: the same address on another network is another account.
        val accounts = (list as? AccountList.Loaded)?.accounts.orEmpty().filter { it.network == network }
        val sender = when (list) {
            is AccountList.Unreadable -> Sender.Cannot(LIST_UNAVAILABLE)
            is AccountList.Loaded -> {
                val account = accounts.firstOrNull { it.address == safe }
                when {
                    account == null -> Sender.Cannot(NOT_IN_WALLET)
                    account.kind != AccountKind.SAFE -> Sender.Cannot(NOT_A_SAFE_ACCOUNT)
                    else -> when (reading) {
                        SafeReading.Checking -> Sender.Checking
                        SafeReading.NotASafe -> Sender.Cannot(NOT_A_SAFE)
                        is SafeReading.Unavailable -> Sender.Cannot(UNVERIFIED)
                        is SafeReading.Verified -> if (reading.ownersHeld.isEmpty()) {
                            Sender.Cannot(WATCH_ONLY)
                        } else {
                            Sender.From(account, reading)
                        }
                    }
                }
            }
        }
        val options = assetOptions(value, inputs.asset, network)
        return Model(
            sender = sender,
            accounts = accounts,
            currency = value.currency,
            options = options,
            selected = options.firstOrNull { it.contract == inputs.asset },
            tokensRead = value.tokensRead,
            inputs = inputs,
            lookup = lookup,
        )
    }

    private fun render(model: Model?, step: Step): SafeProposeUiState {
        val network = network.displayName
        if (step is Step.Proposed) return SafeProposeUiState.Proposed(network, step.hash)
        // A review stands on its proposal, whatever the Safe's reading says meanwhile: a refresh or
        // the signature checks the Safe again, and says what it finds.
        if (step is Step.Reviewing) return review(model?.accounts ?: step.accounts, step)
        model ?: return SafeProposeUiState.Loading(network)
        val account = when (val sender = model.sender) {
            Sender.Checking -> return SafeProposeUiState.Loading(network)
            is Sender.Cannot -> return SafeProposeUiState.CannotPropose(network, sender.message)
            is Sender.From -> sender.account
        }
        val from = SendFrom(account.label, display)
        return when (step) {
            is Step.Composing -> SafeProposeUiState.Compose(compose(model, from, step))
            is Step.Reviewing -> review(model.accounts, step)
            is Step.Proposed -> SafeProposeUiState.Proposed(network, step.hash)
        }
    }

    private fun compose(model: Model, from: SendFrom, step: Step.Composing): TransferForm {
        val inputs = model.inputs
        val text = inputs.to.trim()
        // The lookup of what is typed now; one of an earlier text is no answer about it.
        val lookup = model.lookup.takeIf { it.text == text }
        val result = (lookup as? RecipientState.Done)?.result
        val (note, isError) = recipientNote(text, lookup)
        val selected = model.selected
        val amount = typedAmount(inputs.amount, selected)
        val at = now()
        return TransferForm(
            network = network.displayName,
            toLabel = addressFieldLabel(network),
            toHint = recipientHint(network),
            from = from,
            assets = model.options.map { it.choice(at) },
            asset = selected?.choice(at),
            to = inputs.to,
            accounts = model.accounts.filter { it.address != safe }
                .map { AccountChoice(it.label, Eip55.format(it.address)) },
            amount = inputs.amount,
            assetNote = when {
                selected != null -> null
                !model.tokensRead -> "Checking this Safe's tokens…"
                else -> "The token asked for is not among this Safe's tokens: choose another asset."
            },
            recipient = (result as? RecipientLookup.Found)?.let { recipientItem(it, model.accounts, selected) },
            toNote = note,
            toError = isError,
            canRetryLookup = result is RecipientLookup.Unavailable,
            retryLookupWaits = retryLookupWaits(result),
            amountError = amount.error,
            amountHint = amountHint(amount.units, selected, model.currency, network),
            canMax = selected?.balance != null && !step.proposing,
            quoting = step.proposing,
            error = step.error,
            simpleError = step.simpleError,
            canReview = !step.proposing && requestOf(model) != null,
        )
    }

    private fun review(accounts: List<Account>, step: Step.Reviewing): SafeProposeUiState.Review {
        val proposal = step.proposal
        val option = step.request.option
        val token = (option.asset as? TransferAsset.Erc20Token)?.token
        val request = proposal.request
        val held = proposal.safe.ownersHeld
        val labels = accounts.associate { it.address to it.label }
        val decoding = listOfNotNull(token?.let { DecodingToken(it, sharesBuiltInSymbol = option.warning != null) })
        return SafeProposeUiState.Review(
            network = network.displayName,
            from = SendFrom(step.safeAccount.label, display),
            action = safeTxActionItem(proposal.tx.action, request.nonce, decoding, network),
            amount = exactAmount(step.request.amount, option.decimals, option.symbol),
            asset = option.choice(),
            recipient = recipientItem(step.request.to, accounts, option),
            nonce = "Safe nonce ${request.nonce}",
            queued = proposal.queuedAhead.takeIf { it > 0 }?.let { ahead ->
                if (ahead == 1) {
                    "1 transaction queued on this device runs before it."
                } else {
                    "$ahead transactions queued on this device run before it."
                }
            },
            balance = SafeBalanceChange(
                what = "${option.symbol} balance of the Safe",
                before = exactAmount(proposal.balanceBefore, option.decimals, option.symbol),
                queued = proposal.queuedAmount.takeUnless { it.isZero }?.let {
                    exactAmount(it, option.decimals, option.symbol) + " moved by those queued before it"
                },
                after = exactAmount(proposal.balanceAfter, option.decimals, option.symbol),
                verifiedAt = verifiedAt(proposal.balanceBlock),
            ),
            simulation = when (val simulation = proposal.simulation) {
                is SafeCallSimulation.Ran -> {
                    val at = simulation.blockNumber?.let { "at block ${grouped(it)}" } ?: "at the head"
                    "The Safe's call, run as a verified call from the Safe $at, goes through. The Safe's own " +
                        "checks — its owners' signatures and its nonce — and its guard run when its execution " +
                        "is quoted."
                }
                SafeCallSimulation.NotRun ->
                    "Not simulated: the light client on this device does not run a call from the Safe's address. " +
                        "Its execution, the whole of it, is run as a verified call before anyone sends it."
            },
            simpleSimulation = (proposal.simulation as? SafeCallSimulation.Ran)?.let {
                "Checked on chain: the Safe's call should go through. Its owners' signatures, its nonce and its " +
                    "guard are checked when its execution is quoted."
            },
            owners = held.map { AccountChoice(labels[it] ?: Eip55.format(it), Eip55.format(it)) },
            owner = Eip55.format(step.owner),
            unlock = step.unlock,
            refreshing = step.refreshing,
            signing = step.signing,
            checking = step.checking,
            error = step.error,
            share = step.unsaved?.let { shareItem(encodeSigningRequest(it), it.signatures.size) },
            askingToDrop = step.askingToDrop,
            simpleError = step.simpleError,
        )
    }

    private fun recipientItem(found: RecipientLookup.Found, accounts: List<Account>, asset: AssetOption?) =
        recipientItem(found, accounts) { address ->
            when (address) {
                safe -> "The Safe itself: nothing would leave it."
                asset?.contract -> "The token's own contract: nothing sent to it can ever be moved out."
                else -> null
            }
        }

    /** The form's transfer, when it is complete and the recipient is known: what a proposal is asked for. */
    private fun requestOf(model: Model): Request? {
        if (model.sender !is Sender.From) return null
        val lookup = model.lookup as? RecipientState.Done ?: return null
        if (lookup.text != model.inputs.to.trim()) return null
        val to = lookup.result as? RecipientLookup.Found ?: return null
        val option = model.selected ?: return null
        val amount = typedAmount(model.inputs.amount, option).units ?: return null
        return Request(model.inputs, to, option, amount)
    }

    private fun refusal(proposing: SafeTxProposing, option: AssetOption): String = when (proposing) {
        is SafeTxProposing.Proposed -> error("a proposal is no refusal")
        SafeTxProposing.ToZeroAddress -> ZERO_ADDRESS
        SafeTxProposing.ToTokenContract ->
            "${option.symbol}'s own contract: nothing sent to it can ever be moved out, so it is refused."
        SafeTxProposing.ToSafeItself -> "The Safe itself: nothing would leave it, so it is refused."
        SafeTxProposing.NothingToSend -> "There is nothing to send: enter an amount above zero."
        SafeTxProposing.InsufficientFunds -> "The Safe holds less ${network.nativeSymbol} than that, once the " +
            "transactions queued on this device before it have moved what they move."
        SafeTxProposing.InsufficientToken -> "The Safe holds less ${option.symbol} than that, once the " +
            "transactions queued on this device before it have moved what they move."
        SafeTxProposing.NotAToken ->
            "${option.symbol}'s contract does not answer as a token: nothing can be sent with it."
        is SafeTxProposing.Reverts -> {
            val at = proposing.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty()
            "The Safe's call reverts, run as a verified call from the Safe$at: the recipient or the token " +
                "refuses it. Nothing was proposed."
        }
        is SafeTxProposing.Refused -> safeTxRefusalText(proposing.refusal, network)
    }

    /** [refusal] in simple mode's words, with no block (docs/simple-ui.md); null where it names none. */
    private fun simpleRefusal(proposing: SafeTxProposing): String? = when (proposing) {
        is SafeTxProposing.Reverts -> "The Safe's call reverts, checked on chain: the recipient or the token refuses " +
            "it. Nothing was proposed."
        is SafeTxProposing.Refused -> simpleSafeTxRefusalText(proposing.refusal)
        else -> null
    }

    /** What [text] names, resolved ([ResolveRecipient]); never throws. */
    private suspend fun lookUp(text: String): RecipientLookup = try {
        resolveRecipient(network, recipientText(text))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RecipientLookup.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
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
        const val SIGN_FAILED = "Serotine could not finish this signature. Nothing was queued: try again."
        const val UNSAVED_REPLACED =
            "Proposed again as another transaction, so the signature made for the earlier one, which this " +
                "device's queue could not save, was dropped: sign this one."
        const val NOT_SAVED =
            "Signed, but this device's queue cannot be saved right now, so the proposal is not kept here: share " +
                "it below before you leave this screen, or sign it again in a moment."
        const val LIST_UNAVAILABLE =
            "Your account list cannot be read right now, so nothing can be proposed. Serotine keeps trying."
        const val NOT_IN_WALLET = "This Safe is not in your wallet any more."
        const val NOT_A_SAFE_ACCOUNT = "Only a Safe proposes a Safe transaction: this account sends with Send."
        const val NOT_A_SAFE = "This address no longer holds a recognised Safe, so nothing can be proposed from it."
        const val UNVERIFIED =
            "The Safe's owners cannot be verified right now, so nothing can be proposed. Serotine keeps trying."
        const val WATCH_ONLY =
            "No owner of this Safe has its key on this device: it is watch-only here, and nothing can be proposed " +
                "from it."
        const val NO_OWNER_HELD =
            "No owner of this Safe has its key on this device any more, so nobody here can sign the proposal."
    }
}
