package eth.serotine.ui.safesettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeSettingsChange
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.EncodeSigningRequest
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveSafe
import eth.serotine.domain.usecase.ProposeSafeSettingsChange
import eth.serotine.domain.usecase.SafeCallSimulation
import eth.serotine.domain.usecase.SafeReading
import eth.serotine.domain.usecase.SafeSettingsProblem
import eth.serotine.domain.usecase.SafeSettingsProposal
import eth.serotine.domain.usecase.SafeSettingsProposing
import eth.serotine.domain.usecase.SafeTxRefusal
import eth.serotine.domain.usecase.SafeTxSignCheck
import eth.serotine.domain.usecase.SafeTxSigning
import eth.serotine.domain.usecase.SignSafeTx
import eth.serotine.domain.usecase.problem
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.CHECK_TOOK_THE_TIME
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.GUARD_REMOVED_WARNING
import eth.serotine.ui.components.MIN_PROMPT_TIME
import eth.serotine.ui.components.MODULE_DISABLED_WARNING
import eth.serotine.ui.components.MODULE_ENABLED_REFUSAL
import eth.serotine.ui.components.PROMPT_TIMED_OUT
import eth.serotine.ui.components.PROMPT_TOO_LATE
import eth.serotine.ui.components.QUOTE_MAX_AGE
import eth.serotine.ui.components.STALE_QUOTE
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.promptDeadline
import eth.serotine.ui.components.safeTxActionItem
import eth.serotine.ui.components.safeTxRefusalText
import eth.serotine.ui.components.shareItem
import eth.serotine.ui.components.simpleSafeTxRefusalText
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The ViewModel of a Safe's settings (docs/wallet-ux.md, screen 28): the Safe at [account] as its
 * verified storage holds it ([ObserveSafe]), and the changes of its settings an owner held here can
 * propose — an owner added, replaced or removed, the threshold changed, a module disabled, the
 * guard removed — each filled in its form, checked as it is typed against the Safe as verified
 * ([problem]), then proposed from verified state ([ProposeSafeSettingsChange]: at the Safe's next
 * nonce, its call run as a verified call from the Safe where the engine will) for review, and
 * signed by an owner held here ([SignSafeTx]), which queues it.
 *
 * What is signed is the proposal under review, and a proposal is never signed stale: it is made
 * when the review is entered, again on Refresh, when the screen is shown again after it was not,
 * and when it is older than [QUOTE_MAX_AGE] at the tap that would sign it ([timeSource] tells).
 * Nothing is queued until it is signed, so a proposal given up takes no nonce.
 *
 * One of the ViewModels the [WalletPassword] passes through (CLAUDE.md, *Trust*): it holds it in
 * the review's state while the field is shown, hands it to the signer in an [Unlock], and never
 * logs, saves or caches it; it is dropped once used, when the screen is not shown ([onHidden]) and
 * when the screen is gone.
 */
class SafeSettingsViewModel(
    private val account: AccountId,
    observeAccounts: ObserveAccounts,
    observeSafe: ObserveSafe,
    private val describeKeyStore: DescribeKeyStore,
    private val proposeChange: ProposeSafeSettingsChange,
    private val signSafeTx: SignSafeTx,
    private val encodeSigningRequest: EncodeSigningRequest,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {
    /** The account this screen is about: its address, on its network. */
    private val safe: Address = account.address

    /** The account's network: everything here is read and sent there. */
    private val network: Network = account.network

    /** The Safe, or why its settings cannot be shown. */
    private sealed interface Model {
        data object Checking : Model

        /**
         * The wallet's [accounts] on the Safe's network, the Safe's own [safeAccount] among them, and
         * the addresses whose keys it holds on any network ([keys]): a key signs on every one.
         */
        data class Shown(
            val safeAccount: Account,
            val reading: SafeReading.Verified,
            val accounts: List<Account>,
            val keys: Set<Address>,
        ) : Model

        data class Cannot(val message: String) : Model
    }

    /** Where the flow stands. */
    private sealed interface Step {
        data object Viewing : Step

        /**
         * The form of [kind] about [subject] (the owner or module it removes or replaces; null for
         * the others), with the new owner's address as typed ([address]) and the [threshold] chosen
         * (null: the default for the Safe as read).
         */
        data class Editing(
            val kind: SettingsChangeKind,
            val subject: Address? = null,
            val address: String = "",
            val threshold: Int? = null,
            val proposing: Boolean = false,
            val error: String? = null,
            /** Simple mode's words for [error], with no block, while it is the error they are of. */
            val simple: SimpleText? = null,
        ) : Step

        /**
         * [proposal] of [change], made at [proposedAt] from the form [form], under review, to be
         * signed by [owner]; [hidden] once the screen stopped being shown since it was made.
         * [safeAccount], [accounts] and [keys] are the wallet's as they were when it was proposed: the
         * review stands on them while the Safe's reading or the list is not there. [unsaved] is
         * the request signed here that the queue could not save: shown for sharing until the
         * review is left (Back asks first: [askingToDrop]), or a new proposal of another
         * transaction replaces it, which says so.
         */
        data class Reviewing(
            val form: Editing,
            val change: SafeSettingsChange,
            val proposal: SafeSettingsProposal,
            val proposedAt: TimeMark,
            val owner: Address,
            val unlock: UnlockStep,
            val safeAccount: Account,
            val accounts: List<Account>,
            val keys: Set<Address>,
            val refreshing: Boolean = false,
            val signing: Boolean = false,
            /** While [signing], the Safe is still being checked on chain: nothing is signed yet. */
            val checking: Boolean = false,
            val error: String? = null,
            val hidden: Boolean = false,
            val unsaved: SigningRequest? = null,
            val askingToDrop: Boolean = false,
            /** Simple mode's words for [error], with no block, while it is the error they are of. */
            val simple: SimpleText? = null,
            /** Proposed again and failed: [proposal] is from before, kept for a signature not saved. */
            val outdated: Boolean = false,
        ) : Step {
            val idle: Boolean
                get() = !refreshing && !signing
        }

        data class Proposed(val hash: String) : Step
    }

    /** [text], simple mode's words for the error [of], which it stands for only while that is the error. */
    private data class SimpleText(val of: String, val text: String)

    // The checksum once, not on every reading.
    private val display = Eip55.format(safe)

    private val step = MutableStateFlow<Step>(Step.Viewing)

    /** The proposal or signature in flight. */
    private var job: Job? = null

    /** Whether the screen is shown now ([onShown], [onHidden]). */
    private var shown = false

    /** Whether the screen was hidden while the first proposal of a review was being made. */
    private var hiddenWhileProposing = false

    private val model: StateFlow<Model?> = combine(observeAccounts(), observeSafe(account), ::modelOf).stateIn(
        viewModelScope,
        // As on the account screens: back after the timeout, no old reading stands as current.
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        null,
    )

    val uiState: StateFlow<SafeSettingsUiState> = combine(model, step, ::render).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        SafeSettingsUiState.Loading(network.displayName),
    )

    /** Opens the form that adds an owner. */
    fun onAddOwner() = open(SettingsChangeKind.ADD_OWNER, subject = null)

    /** Opens the form that replaces the owner at [address] (EIP-55). */
    fun onReplaceOwner(address: String) {
        open(SettingsChangeKind.REPLACE_OWNER, Address.parse(address) ?: return)
    }

    /** Opens the form that removes the owner at [address] (EIP-55). */
    fun onRemoveOwner(address: String) {
        open(SettingsChangeKind.REMOVE_OWNER, Address.parse(address) ?: return)
    }

    /** Opens the form that changes the threshold. */
    fun onChangeThreshold() = open(SettingsChangeKind.CHANGE_THRESHOLD, subject = null)

    /** Opens the form that disables the module at [address] (EIP-55). */
    fun onDisableModule(address: String) {
        open(SettingsChangeKind.DISABLE_MODULE, Address.parse(address) ?: return)
    }

    /** Opens the form that removes the guard. */
    fun onRemoveGuard() = open(SettingsChangeKind.REMOVE_GUARD, subject = null)

    private fun open(kind: SettingsChangeKind, subject: Address?) {
        if (step.value != Step.Viewing) return
        val shown = model.value as? Model.Shown ?: return
        if (changeReason(shown) != null) return
        // A Safe of one owner keeps it, and its threshold of one: those two are greyed out.
        val single = shown.reading.state.owners.size == 1
        if (single && (kind == SettingsChangeKind.REMOVE_OWNER || kind == SettingsChangeKind.CHANGE_THRESHOLD)) return
        step.value = Step.Editing(kind, subject)
    }

    /** Back from the form to the settings: what was filled in goes. */
    fun cancel() {
        if (step.value !is Step.Editing) return
        job?.cancel()
        step.value = Step.Viewing
    }

    /** The new owner's address field now reads [text]. */
    fun onAddressChange(text: String) = change { it.copy(address = text) }

    /** Takes the wallet's account at [address] (EIP-55) as the new owner. */
    fun onPickAccount(address: String) = change { it.copy(address = address) }

    /** The threshold the change leaves is now [value], within what the form allows. */
    fun onThresholdChange(value: Int) {
        val form = step.value as? Step.Editing ?: return
        val shown = model.value as? Model.Shown ?: return
        val max = ownersAfter(form.kind, shown.reading.state) ?: return
        change { it.copy(threshold = value.coerceIn(1, max)) }
    }

    /** Proposes the change as filled in and, when the proposal stands, shows it for review. */
    fun review() {
        val form = step.value as? Step.Editing ?: return
        if (form.proposing) return
        val shown = model.value as? Model.Shown ?: return
        val change = changeOf(form, shown.reading.state) ?: return
        if (change.problem(safe, shown.reading.state) != null) return
        val proposing = form.copy(proposing = true, error = null)
        step.value = proposing
        hiddenWhileProposing = false
        job = viewModelScope.launch {
            val next = proposed(form, change, shown.reading.ownersHeld.firstOrNull(), null, shown)
            if (step.value != proposing) return@launch
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
        step.value = current.form.copy(proposing = false, error = null)
    }

    /** Back to the form after all, dropping the signature the queue could not save. */
    fun dropUnsaved() {
        val current = step.value as? Step.Reviewing ?: return
        if (current.signing || !current.askingToDrop) return
        job?.cancel()
        step.value = current.form.copy(proposing = false, error = null)
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
                current.copy(owner = owner, unlock = current.unlock.copy(error = null), error = null)
            } else {
                current
            }
        }
    }

    /** Proposes the reviewed change again: the Safe's nonce, its queue and its lists anew. */
    fun refresh() = refresh(note = null)

    private fun refresh(note: String?) {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle) return
        val cleared = current.unlock.copy(error = null)
        step.value = current.copy(refreshing = true, hidden = false, error = null, unlock = cleared)
        job = viewModelScope.launch {
            val wallet = model.value as? Model.Shown
            val next = proposed(
                current.form,
                current.change,
                current.owner,
                current.unlock,
                Model.Shown(
                    current.safeAccount,
                    current.proposal.safe,
                    wallet?.accounts ?: current.accounts,
                    wallet?.keys ?: current.keys,
                ),
            )
            step.update { latest ->
                when {
                    latest !is Step.Reviewing || !latest.refreshing -> latest
                    // The password typed meanwhile stays: it is the same review, hidden since or not. A
                    // signature not saved is of the transaction it was made for: one proposed anew as
                    // another transaction (another nonce, another neighbour) drops it, and says so.
                    next is Step.Reviewing -> {
                        val unsaved = latest.unsaved?.takeIf { it.hash == next.proposal.request.hash }
                        latest.copy(
                            proposal = next.proposal,
                            proposedAt = next.proposedAt,
                            owner = next.owner,
                            accounts = next.accounts,
                            keys = next.keys,
                            refreshing = false,
                            unlock = latest.unlock.copy(error = note),
                            unsaved = unsaved,
                            error = if (latest.unsaved != null && unsaved == null) UNSAVED_REPLACED else latest.error,
                            askingToDrop = latest.askingToDrop && unsaved != null,
                            outdated = false,
                        )
                    }
                    // A request signed here that is kept nowhere else is not given up with a
                    // re-proposal that fails: the review stays, saying why, with it to share.
                    latest.unsaved != null && next is Step.Editing ->
                        latest.copy(
                            refreshing = false,
                            error = next.error,
                            simple = next.simple,
                            unlock = latest.unlock.copy(error = note),
                            outdated = true,
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
        // The change in full, with its warning: the prompt is the last thing read before the key signs.
        val action = actionItem(current.proposal)
        val named = action.addresses.joinToString("") { ", ${it.what.lowercase()} ${it.address}" }
        val notes = action.notes.joinToString("") { ", ${it.replaceFirstChar(Char::lowercase)}" }
        val warning = action.warning?.let { " ($it)" }.orEmpty()
        val label = current.safeAccount.label
        // The prompt gets what is left of the proposal's age: past it, nothing is signed, and it is made again.
        val notAfter = promptDeadline(current.proposedAt) ?: return refresh(note = PROMPT_TOO_LATE)
        val text = "Sign a Safe transaction of $label: ${action.title.replaceFirstChar(Char::lowercase)}" +
            "$named$notes$warning"
        sign(current, Unlock.WithBiometrics(text, notAfter))
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
        if ((step.value as? Step.Editing)?.proposing == true) hiddenWhileProposing = true
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
        step.value = review.copy(signing = true, checking = true, error = null, unlock = unlocked)
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
                    is SafeTxSigning.Refused -> {
                        val error = safeTxRefusalText(signing.refusal, network)
                        latest.copy(signing = false, error = error, simple = simpleOf(error, signing.refusal))
                    }
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
     * The review of [change], filled in as [form], proposed now, signed by [owner] (the first held
     * when that is no owner any more) with [unlock] (a new one when null), or the form saying why
     * there is none. [wallet] is the Safe's account and the wallet's accounts and keys the review
     * stands on; its reading is not proposed on: the proposal reads the Safe itself.
     */
    private suspend fun proposed(
        form: Step.Editing,
        change: SafeSettingsChange,
        owner: Address?,
        unlock: UnlockStep?,
        wallet: Model.Shown,
    ): Step {
        // Its age runs from its first read, not from its answer: the figures are as old as that. The
        // first is the one after the Safe's check, which may wait a while for a verified reading.
        var readFrom = timeSource.markNow()
        val proposing = try {
            proposeChange(safe, change) { readFrom = timeSource.markNow() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The use case promises not to throw; the backstop.
            SafeSettingsProposing.Refused(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN))
        }
        val failed = form.copy(proposing = false)
        val proposal = when (proposing) {
            is SafeSettingsProposing.Proposed -> proposing.proposal
            is SafeSettingsProposing.Problem -> return failed.copy(error = problemText(proposing.problem, form.kind))
            is SafeSettingsProposing.Reverts -> {
                val at = proposing.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty()
                val error = "The change reverts, run as a verified call from the Safe$at: the Safe refuses it as it " +
                    "is now. Nothing was proposed."
                val simple = "The change reverts, as checked on chain: the Safe refuses it as it is now. Nothing was " +
                    "proposed."
                return failed.copy(error = error, simple = SimpleText(error, simple))
            }
            is SafeSettingsProposing.Refused -> {
                val error = safeTxRefusalText(proposing.refusal, network)
                return failed.copy(error = error, simple = simpleOf(error, proposing.refusal))
            }
        }
        val held = proposal.safe.ownersHeld
        val signer = owner?.takeIf { it in held } ?: held.firstOrNull()
            ?: return failed.copy(error = NO_OWNER_HELD)
        return Step.Reviewing(
            form.copy(proposing = false, error = null),
            change,
            proposal,
            readFrom,
            signer,
            unlock ?: UnlockStep(biometrics = biometricsOffered()),
            wallet.safeAccount,
            wallet.accounts,
            wallet.keys,
        )
    }

    /** Applies [edit] to the form: only while it is filled in, dropping a proposal in flight. */
    private fun change(edit: (Step.Editing) -> Step.Editing) {
        val form = step.value as? Step.Editing ?: return
        job?.cancel()
        step.value = edit(form).copy(proposing = false, error = null)
    }

    private fun modelOf(list: AccountList, reading: SafeReading): Model {
        val all = (list as? AccountList.Loaded)?.accounts.orEmpty()
        // The Safe's network's accounts only: the same address on another network is another account.
        val accounts = all.filter { it.network == network }
        return when (list) {
            is AccountList.Unreadable -> Model.Cannot(LIST_UNAVAILABLE)
            is AccountList.Loaded -> {
                val account = accounts.firstOrNull { it.address == safe }
                when {
                    account == null -> Model.Cannot(NOT_IN_WALLET)
                    account.kind != AccountKind.SAFE -> Model.Cannot(NOT_A_SAFE_ACCOUNT)
                    else -> when (reading) {
                        SafeReading.Checking -> Model.Checking
                        SafeReading.NotASafe -> Model.Cannot(NOT_A_SAFE)
                        is SafeReading.Unavailable -> Model.Cannot(unavailableText(reading.reason))
                        is SafeReading.Verified -> Model.Shown(
                            account,
                            reading,
                            accounts,
                            all.filter { it.custody == AccountCustody.InKeyStore }.mapTo(HashSet()) { it.address },
                        )
                    }
                }
            }
        }
    }

    private fun render(model: Model?, step: Step): SafeSettingsUiState {
        val network = network.displayName
        if (step is Step.Proposed) return SafeSettingsUiState.Proposed(network, step.hash)
        // A review stands on its proposal, whatever the Safe's reading says meanwhile: a refresh or
        // the signature checks the Safe again, and says what it finds.
        if (step is Step.Reviewing) return review(step)
        val shown = when (model) {
            null, Model.Checking -> return SafeSettingsUiState.Loading(network)
            is Model.Cannot -> return SafeSettingsUiState.Unavailable(network, model.message)
            is Model.Shown -> model
        }
        val from = SendFrom(shown.safeAccount.label, display)
        return when (step) {
            Step.Viewing -> overview(shown, from)
            is Step.Editing -> SafeSettingsUiState.Edit(network, from, form(shown, step))
            is Step.Reviewing, is Step.Proposed -> error("handled above")
        }
    }

    private fun overview(shown: Model.Shown, from: SendFrom): SafeSettingsUiState.Overview {
        val state = shown.reading.state
        return SafeSettingsUiState.Overview(
            network = network.displayName,
            safe = from,
            title = "Safe ${state.version}" + (if (state.l2) " (L2)" else "") + " · nonce ${state.nonce}",
            signers = "${state.threshold} of ${state.owners.size} owners must sign",
            owners = state.owners.map { settingsOwner(it, shown.reading) },
            modules = state.modules.map(Eip55::format),
            guard = state.guard?.let(Eip55::format),
            checkedAt = "Verified at block ${grouped(shown.reading.blockNumber)}",
            simpleCheckedAt = "Checked on chain",
            changeReason = changeReason(shown),
            enableModuleReason = MODULE_ENABLED_REFUSAL,
            updateReason = "Updating a Safe's version runs a migration contract's code as the Safe, a delegate " +
                "call, which Serotine does not sign. It comes once a migration contract is pinned here.",
            moduleGuard = state.moduleGuard?.let(Eip55::format),
            removeReason = "The Safe's only owner cannot be removed: replace it instead."
                .takeIf { state.owners.size == 1 },
            thresholdReason = "A Safe of one owner needs that owner's signature: add an owner first, then the " +
                "threshold can change.".takeIf { state.owners.size == 1 },
        )
    }

    private fun form(shown: Model.Shown, form: Step.Editing): SettingsForm {
        val reading = shown.reading
        val state = reading.state
        val change = changeOf(form, state)
        val typed = form.address.trim()
        val owner = Address.parse(typed)
        val problem = change?.problem(safe, state)
        val subject = form.subject?.let { settingsOwner(it, reading) }
        val address = if (form.kind == SettingsChangeKind.ADD_OWNER || form.kind == SettingsChangeKind.REPLACE_OWNER) {
            val inWallet = owner?.let { address -> shown.accounts.firstOrNull { it.address == address } }
            OwnerField(
                text = form.address,
                note = when {
                    typed.isEmpty() -> "An address: another device's account, a hardware wallet, another Safe."
                    owner == null -> "Not an address: 0x and 40 hexadecimal digits."
                    inWallet != null -> "${inWallet.label}, in this wallet" +
                        (if (inWallet.custody == AccountCustody.InKeyStore) ": its key is on this device." else ".")
                    else -> "${Eip55.format(owner)}: not in this wallet."
                },
                isError = typed.isNotEmpty() && owner == null,
                accounts = shown.accounts
                    .filter { it.address != safe && it.address !in state.owners }
                    .distinctBy { it.address }
                    .map { AccountChoice(it.label, Eip55.format(it.address)) },
            )
        } else {
            null
        }
        val threshold = ownersAfter(form.kind, state)?.let { owners ->
            val value = thresholdOf(form, state)
            ThresholdField(value, owners, "$value of $owners owners must sign")
        }
        val (title, explanation) = when (form.kind) {
            SettingsChangeKind.ADD_OWNER -> "Add an owner" to
                "The new owner signs with the others once the change runs. The threshold is how many of them " +
                "must sign each of the Safe's transactions."
            SettingsChangeKind.REPLACE_OWNER -> "Replace an owner" to
                "The new owner takes this owner's place, and the threshold stays: how a Safe moves to a new " +
                "device, or away from a lost one."
            SettingsChangeKind.REMOVE_OWNER -> "Remove an owner" to
                "The owner signs for the Safe no more once the change runs. The threshold can be no more than " +
                "the owners left."
            SettingsChangeKind.CHANGE_THRESHOLD -> "Change the threshold" to
                "How many of the owners must sign each of the Safe's transactions."
            SettingsChangeKind.DISABLE_MODULE -> "Disable a module" to
                "The module moves the Safe's funds no more once the change runs."
            SettingsChangeKind.REMOVE_GUARD -> "Remove the guard" to
                "The guard checks the Safe's transactions no more once the change runs. It checks this one too: " +
                "a guard that refuses it stays."
        }
        return SettingsForm(
            kind = form.kind,
            title = title,
            explanation = explanation,
            subject = subject ?: state.guard?.takeIf { form.kind == SettingsChangeKind.REMOVE_GUARD }
                ?.let { SettingsOwner(Eip55.format(it), label = null, heldHere = false) },
            address = address,
            threshold = threshold,
            warning = warningOf(form, change, reading, shown.keys),
            // A threshold not chosen yet is no mistake: Review waits for one that changes it.
            problem = problem
                ?.takeUnless {
                    it == SafeSettingsProblem.NoChange && form.kind == SettingsChangeKind.CHANGE_THRESHOLD &&
                        form.threshold == null
                }
                ?.let { problemText(it, form.kind) },
            proposing = form.proposing,
            error = form.error,
            canReview = !form.proposing && change != null && problem == null,
            simpleError = form.simple?.takeIf { it.of == form.error }?.text,
        )
    }

    /** [refusal]'s text [error] with simple mode's words for it, where they differ: no block. */
    private fun simpleOf(error: String, refusal: SafeTxRefusal): SimpleText? =
        simpleSafeTxRefusalText(refusal)?.let { SimpleText(error, it) }

    private fun review(step: Step.Reviewing): SafeSettingsUiState.Review {
        val proposal = step.proposal
        val request = proposal.request
        val held = proposal.safe.ownersHeld
        val labels = step.accounts.associate { it.address to it.label }
        return SafeSettingsUiState.Review(
            network = network.displayName,
            safe = SendFrom(step.safeAccount.label, display),
            action = actionItem(proposal),
            nonce = "Safe nonce ${request.nonce}",
            queued = proposal.queuedAhead.takeIf { it > 0 }?.let { ahead ->
                if (ahead == 1) {
                    "1 transaction queued on this device runs before it."
                } else {
                    "$ahead transactions queued on this device run before it."
                }
            },
            changesQueued = proposal.changesQueuedAhead.takeIf { it > 0 }?.let {
                "A change of this Safe's settings queued before it runs first. This one is built against the " +
                    "owners and modules as they are now: if that one changes them, this one may fail when it is " +
                    "executed, which its execution's verified run says before anyone sends it."
            },
            simulation = when (val simulation = proposal.simulation) {
                is SafeCallSimulation.Ran -> {
                    val at = simulation.blockNumber?.let { "at block ${grouped(it)}" } ?: "at the head"
                    "The change, run as a verified call from the Safe $at, goes through. The Safe's own checks — " +
                        "its owners' signatures and its nonce — and its guard run when its execution is quoted."
                }
                SafeCallSimulation.NotRun ->
                    "Not simulated: the light client on this device does not run a call from the Safe's address. " +
                        "Its execution, the whole of it, is run as a verified call before anyone sends it."
            },
            // Detailed view orders a kept run and a later failure by their blocks; simple mode, by its words.
            simpleSimulation = (proposal.simulation as? SafeCallSimulation.Ran)?.let {
                val run = if (step.outdated) {
                    "When it was proposed, it was checked on chain: the change was to go through then."
                } else {
                    "Checked on chain: the change should go through."
                }
                "$run The Safe's own checks — its owners' signatures and its nonce — and its guard run when its " +
                    "execution is quoted."
            },
            owners = held.map { AccountChoice(labels[it] ?: Eip55.format(it), Eip55.format(it)) },
            owner = Eip55.format(step.owner),
            unlock = step.unlock,
            warning = warningOf(step.form, step.change, proposal.safe, step.keys),
            refreshing = step.refreshing,
            signing = step.signing,
            checking = step.checking,
            error = step.error,
            share = step.unsaved?.let { shareItem(encodeSigningRequest(it), it.signatures.size) },
            askingToDrop = step.askingToDrop,
            simpleError = step.simple?.takeIf { it.of == step.error }?.text,
        )
    }

    private fun actionItem(proposal: SafeSettingsProposal): SafeTxActionItem =
        safeTxActionItem(proposal.tx.action, proposal.request.nonce, emptyList(), network)

    private fun settingsOwner(owner: Address, reading: SafeReading.Verified): SettingsOwner {
        val account = reading.ownersInWallet.firstOrNull { it.address == owner }
        return SettingsOwner(Eip55.format(owner), account?.label, account?.custody == AccountCustody.InKeyStore)
    }

    /** Why no change can be proposed from here, or null when one can: an owner held here signs it. */
    private fun changeReason(shown: Model.Shown): String? =
        WATCH_ONLY.takeIf { shown.reading.ownersHeld.isEmpty() }

    /** The change [form] is filled in for, against the Safe in [state]; null while it is not complete. */
    private fun changeOf(form: Step.Editing, state: SafeState): SafeSettingsChange? {
        val threshold = thresholdOf(form, state).toLong()
        return when (form.kind) {
            SettingsChangeKind.ADD_OWNER -> SafeSettingsChange.OwnerAdded(newOwner(form) ?: return null, threshold)
            SettingsChangeKind.REPLACE_OWNER ->
                SafeSettingsChange.OwnerSwapped(form.subject ?: return null, newOwner(form) ?: return null)
            SettingsChangeKind.REMOVE_OWNER -> SafeSettingsChange.OwnerRemoved(form.subject ?: return null, threshold)
            SettingsChangeKind.CHANGE_THRESHOLD -> SafeSettingsChange.ThresholdChanged(threshold)
            SettingsChangeKind.DISABLE_MODULE -> SafeSettingsChange.ModuleDisabled(form.subject ?: return null)
            SettingsChangeKind.REMOVE_GUARD -> SafeSettingsChange.GuardSet(null)
        }
    }

    private fun newOwner(form: Step.Editing): Address? = Address.parse(form.address.trim())

    /** The owners the Safe has once [kind] has run, for a change that sets the threshold; null otherwise. */
    private fun ownersAfter(kind: SettingsChangeKind, state: SafeState): Int? = when (kind) {
        SettingsChangeKind.ADD_OWNER -> state.owners.size + 1
        SettingsChangeKind.REMOVE_OWNER -> (state.owners.size - 1).takeIf { it > 0 }
        SettingsChangeKind.CHANGE_THRESHOLD -> state.owners.size
        else -> null
    }

    /**
     * The threshold [form] leaves: the one chosen, or by default the Safe's own as far as the owners
     * left allow, below their count where that count is above one (every owner needed is refused).
     */
    private fun thresholdOf(form: Step.Editing, state: SafeState): Int {
        val owners = ownersAfter(form.kind, state) ?: return state.threshold
        form.threshold?.let { return it.coerceIn(1, owners) }
        val kept = state.threshold.coerceAtMost(owners)
        return if (owners > 1 && kept >= owners) owners - 1 else kept
    }

    /**
     * What to know before [change], filled in as [form], is proposed: what it stops (a module, a
     * guard), and what it does to this device's part in the Safe when it removes or replaces an
     * owner held here; [keys] the addresses whose keys the wallet holds.
     */
    private fun warningOf(
        form: Step.Editing,
        change: SafeSettingsChange?,
        reading: SafeReading.Verified,
        keys: Set<Address>,
    ): String? {
        when (form.kind) {
            SettingsChangeKind.DISABLE_MODULE -> return MODULE_DISABLED_WARNING
            SettingsChangeKind.REMOVE_GUARD -> return GUARD_REMOVED_WARNING
            else -> Unit
        }
        val removed = form.subject?.takeIf { it in reading.ownersHeld } ?: return null
        val added = (change as? SafeSettingsChange.OwnerSwapped)?.new
        val others = reading.ownersHeld - removed
        return when {
            added != null && added in keys ->
                "Its key is on this device, and so is the new owner's: once the change runs, this device signs " +
                    "for the Safe as the new owner" + (if (others.isEmpty()) "." else " and its other owners held here.")
            others.isEmpty() ->
                "Its key is the only one of this Safe's owners on this device: once the change runs, this device " +
                    "can no longer sign for the Safe, which becomes watch-only here."
            else -> "Its key is on this device: once the change runs, this device signs for the Safe with its " +
                "other owners held here only."
        }
    }

    /** What [problem] says in the form of [kind]. */
    private fun problemText(problem: SafeSettingsProblem, kind: SettingsChangeKind): String = when (problem) {
        is SafeSettingsProblem.InvalidOwner -> when (problem.address) {
            safe -> "The Safe cannot be its own owner."
            Address.ZERO -> "The zero address cannot be an owner: nobody holds its key."
            else -> "${Eip55.format(problem.address)} marks the end of the Safe's list of owners: it cannot be one."
        }
        is SafeSettingsProblem.AlreadyAnOwner -> "${Eip55.format(problem.address)} is an owner of this Safe already."
        is SafeSettingsProblem.NotAnOwner -> "${Eip55.format(problem.address)} is no owner of this Safe any more."
        SafeSettingsProblem.LastOwner -> "The Safe's only owner cannot be removed: replace it instead."
        is SafeSettingsProblem.ThresholdOutOfRange -> "The threshold must be from 1 to ${problem.max}."
        SafeSettingsProblem.NoChange ->
            if (kind == SettingsChangeKind.REMOVE_GUARD) "The Safe has no guard any more." else "The threshold is that already."
        is SafeSettingsProblem.AlreadyEnabled -> "${Eip55.format(problem.module)} is enabled already."
        is SafeSettingsProblem.NoSuchModule -> "${Eip55.format(problem.module)} is no module of this Safe any more."
        is SafeSettingsProblem.NeedsRecoveryModule ->
            safeTxRefusalText(SafeTxRefusal.NeedsRecoveryModule(problem.owners), network)
    }

    /** Why the Safe's settings cannot be read for [reason]. */
    private fun unavailableText(reason: ReadUnavailable): String = when (reason) {
        ReadUnavailable.NO_ENGINE -> "There is no light client on this device, so the Safe's settings cannot be read."
        ReadUnavailable.NETWORK_OFF ->
            "${network.displayName} is off in this wallet, so the Safe's settings cannot be read. Turn it on in " +
                "Settings → Networks."
        ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so the Safe's settings cannot be read."
        ReadUnavailable.SYNCING -> "The light client is still syncing, so the Safe's settings cannot be read yet."
        ReadUnavailable.UNPROVEN -> "The Safe's storage could not be verified this time. Serotine keeps trying."
        ReadUnavailable.REFUSED -> "The light client on this device cannot read this Safe's storage."
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
            "Your account list cannot be read right now, so the Safe's settings cannot be shown. Serotine keeps " +
                "trying."
        const val NOT_IN_WALLET = "This Safe is not in your wallet any more."
        const val NOT_A_SAFE_ACCOUNT = "Only a Safe has a Safe's settings."
        const val NOT_A_SAFE = "This address no longer holds a recognised Safe, so it has no settings to change."
        const val WATCH_ONLY =
            "No owner of this Safe has its key on this device: it is watch-only here, so no change of its " +
                "settings can be proposed from it. An owner's recovery phrase, imported, makes it possible."
        const val NO_OWNER_HELD =
            "No owner of this Safe has its key on this device any more, so nobody here can sign the proposal."
    }
}
