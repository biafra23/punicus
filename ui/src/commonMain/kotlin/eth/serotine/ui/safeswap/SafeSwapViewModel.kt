package eth.serotine.ui.safeswap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.RouterSwap
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.AccountValue
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveSafe
import eth.serotine.domain.usecase.ObserveSafeTxQueue
import eth.serotine.domain.usecase.ProposeSafeSwap
import eth.serotine.domain.usecase.SAFE_SWAP_DEADLINE_SECONDS
import eth.serotine.domain.usecase.SafeCallSimulation
import eth.serotine.domain.usecase.SafeReading
import eth.serotine.domain.usecase.SafeSwapProposal
import eth.serotine.domain.usecase.SafeSwapProposing
import eth.serotine.domain.usecase.SafeSwapSimulation
import eth.serotine.domain.usecase.SafeTxQueueReading
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
import eth.serotine.ui.components.NOT_A_TOKEN
import eth.serotine.ui.components.PROMPT_TIMED_OUT
import eth.serotine.ui.components.PROMPT_TOO_LATE
import eth.serotine.ui.components.QUOTE_MAX_AGE
import eth.serotine.ui.components.ROUTER_NAME
import eth.serotine.ui.components.STALE_QUOTE
import eth.serotine.ui.components.SafeBalanceChange
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.SwapForm
import eth.serotine.ui.components.SwapPick
import eth.serotine.ui.components.TOO_SMALL
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.amountHint
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.components.exactAmount
import eth.serotine.ui.components.figureText
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.noRouteText
import eth.serotine.ui.components.noVenueText
import eth.serotine.ui.components.notASwapText
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.priceHintText
import eth.serotine.ui.components.priceImpactText
import eth.serotine.ui.components.promptDeadline
import eth.serotine.ui.components.redatedWhile
import eth.serotine.ui.components.routeSteps
import eth.serotine.ui.components.safeTxActionItem
import eth.serotine.ui.components.safeTxRefusalText
import eth.serotine.ui.components.sellNote
import eth.serotine.ui.components.simpleSafeTxRefusalText
import eth.serotine.ui.components.swapOptions
import eth.serotine.ui.components.toleranceError
import eth.serotine.ui.components.typedAmount
import eth.serotine.ui.components.verifiedAt
import eth.serotine.ui.platform.formatDateTime
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The ViewModel of Swap for a Safe (docs/swaps.md, *A Safe's swap*): a Uniswap swap from the Safe
 * at [safe] on [network], composed in Swap's form — the asset sold ([asset], the token asked for,
 * null for the network's currency), the asset bought, the amount in the sold asset's units and the
 * tolerance; no fee, which whoever executes it pays — then proposed from verified state
 * ([ProposeSafeSwap]: the router's approval, when it needs one, after the allowance set to zero
 * for a token that refuses one allowance for another, and the swap, at consecutive nonces,
 * with the minimum and a 24-hour deadline fixed) for review, and signed, every one of its Safe
 * transactions, by an owner held here ([SignSafeTx]), which queues them.
 *
 * With [replacing], the hash of a swap queued for the Safe, it proposes that swap again in its
 * place (Execute's offer for a minimum fallen behind the market, or a deadline passed): the pair
 * and the amount are the queued swap's ([SwapForm.fixed]), and only the tolerance may change.
 *
 * What is signed is the proposal under review, and a proposal is never signed stale: it is made
 * when the review is entered, again on Refresh, when the screen is shown again after it was not,
 * and when it is older than [QUOTE_MAX_AGE] at the tap that would sign it ([timeSource] tells).
 * Nothing is queued until it is signed, so a proposal given up takes no nonce. One signed in part
 * keeps what was signed queued (the approval, or the reset to zero, or both): proposed again, the
 * rest goes after it.
 * A price never reaches it.
 *
 * One of the ViewModels the [WalletPassword] passes through (CLAUDE.md, *Trust*): it holds it in the
 * review's state while the field is shown, hands it to the signer in an [Unlock] for each of the
 * transactions, and never logs, saves or caches it; it is dropped once used, when the screen is not
 * shown ([onHidden]) and when the screen is gone. [formatTime] dates the deadline in the user's own
 * time zone.
 */
class SafeSwapViewModel(
    private val account: AccountId,
    asset: Address?,
    private val replacing: Digest?,
    observeAccounts: ObserveAccounts,
    observeAccountValue: ObserveAccountValue,
    observeSafe: ObserveSafe,
    observeSafeTxQueue: ObserveSafeTxQueue,
    private val describeKeyStore: DescribeKeyStore,
    private val proposeSafeSwap: ProposeSafeSwap,
    private val signSafeTx: SignSafeTx,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val formatTime: (Long) -> String = ::formatDateTime,
    private val now: () -> Long = ::epochSecondsNow,
) : ViewModel() {
    /** The account this screen is about: its address, on its network. */
    private val safe: Address = account.address

    /** The account's network: everything here is read and sent there. */
    private val network: Network = account.network


    /**
     * What the form has: the asset sold, the asset bought (null until chosen), the amount and the
     * tolerance as typed.
     */
    private data class Inputs(
        val sell: SwapPick,
        val buy: SwapPick? = null,
        val amount: String = "",
        val tolerance: String = SlippageTolerance.DEFAULT.percent,
    )

    /** The Safe swapped from, or why nothing can be swapped from it. */
    private sealed interface Sender {
        data object Checking : Sender

        data class From(val account: Account, val reading: SafeReading.Verified) : Sender

        data class Cannot(val message: String) : Sender
    }

    /** The swap proposed again: found at [nonce], not in the queue, or the queue unreadable now. */
    private sealed interface Replaced {
        data class Found(val swap: RouterSwap, val nonce: Long) : Replaced

        data object Gone : Replaced

        data object Unreadable : Replaced
    }

    /** Everything the form is derived from. */
    private class Model(
        val sender: Sender,
        val accounts: List<Account>,
        val currency: DisplayCurrency,
        val sellOptions: List<AssetOption>,
        val sell: AssetOption?,
        val buyOptions: List<AssetOption>,
        val buy: AssetOption?,
        val tokensRead: Boolean,
        val inputs: Inputs,
        val replaced: Replaced?,
    )

    /** A swap the form can have proposed, exactly as it has it; [nonce] the one it replaces, if any. */
    private data class Request(
        val sell: AssetOption,
        val buy: AssetOption,
        val amount: UInt256,
        val tolerance: SlippageTolerance,
        val nonce: Long?,
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
         * [accounts] are the wallet's as they were when it was proposed. [signed] how many of its
         * transactions are signed and queued so far, while [signing] and after.
         */
        data class Reviewing(
            val request: Request,
            val proposal: SafeSwapProposal,
            val proposedAt: TimeMark,
            val owner: Address,
            val unlock: UnlockStep,
            val safeAccount: Account,
            val accounts: List<Account>,
            val refreshing: Boolean = false,
            val signing: Boolean = false,
            /** While [signing], the Safe is still being checked on chain: nothing is signed yet. */
            val checking: Boolean = false,
            val signed: Int = 0,
            val error: String? = null,
            val hidden: Boolean = false,
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

    private val inputs = MutableStateFlow(Inputs(asset?.let(SwapPick::Token) ?: SwapPick.Native))
    private val step = MutableStateFlow<Step>(Step.Composing())

    /** The proposal or signature in flight. */
    private var job: Job? = null

    /** Whether the screen is shown now ([onShown], [onHidden]). */
    private var shown = false

    /** Whether the screen was hidden while the first proposal of a review was being made. */
    private var hiddenWhileProposing = false

    /** The swap proposed again, as the queue has it; null when none is. */
    private val replaced: Flow<Replaced?> = if (replacing == null) {
        flowOf(null)
    } else {
        observeSafeTxQueue(safe).map { reading ->
            when (reading) {
                SafeTxQueueReading.Unreadable -> Replaced.Unreadable
                is SafeTxQueueReading.Loaded -> {
                    val found = reading.transactions.firstOrNull { it.request.hash == replacing }
                    val swap = (found?.action as? SafeTxAction.Swap)?.swap
                    if (found != null && swap != null) Replaced.Found(swap, found.request.nonce) else Replaced.Gone
                }
            }
        }
    }

    private val model: StateFlow<Model?> = combine(
        observeAccounts(),
        observeSafe(account),
        observeAccountValue(account),
        inputs,
        replaced,
    ) { list, reading, value, inputs, replaced ->
        modelOf(list, reading, value, inputs, replaced)
    }.stateIn(
        viewModelScope,
        // As on the account screens: back after the timeout, no old balance stands as current.
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        null,
    )

    val uiState: StateFlow<SafeSwapUiState> = combine(model, step) { model, step -> model to step }
        // A balance's age, which simple mode shows, is dated anew every second on the compose step.
        .redatedWhile { (model, step) ->
            step is Step.Composing && model != null &&
                (model.sellOptions + model.buyOptions).any { it.provenAt != null }
        }
        .map { (model, step) -> render(model, step) }
        .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        SafeSwapUiState.Loading(network.displayName),
    )

    /** Sells the token at [contract], or the network's currency (null). */
    fun onSellChange(contract: String?) {
        val pick = SwapPick.of(contract) ?: return
        change { it.copy(sell = pick, buy = it.buy.takeUnless { buy -> buy == pick }, amount = "") }
    }

    /** Buys the token at [contract], or the network's currency (null). */
    fun onBuyChange(contract: String?) {
        val pick = SwapPick.of(contract) ?: return
        change { it.copy(buy = pick) }
    }

    /** The amount field now reads [text], in the sold asset's units. */
    fun onAmountChange(text: String) = change { it.copy(amount = text) }

    /** Fills in the sold asset's whole verified balance. */
    fun onMax() {
        val option = model.value?.sell ?: return
        val balance = option.balance ?: return
        change { it.copy(amount = balance.formatUnits(option.decimals)) }
    }

    /** The tolerance field now reads [text], a percentage. */
    fun onToleranceChange(text: String) = change(fixedToo = true) { it.copy(tolerance = text) }

    /** Proposes the swap as composed and, when the proposal stands, shows it for review. */
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
     * Safe's check before it is not signing yet, and stops).
     */
    fun edit() {
        val current = step.value as? Step.Reviewing ?: return
        if (current.signing && !current.checking) return
        job?.cancel()
        // Stopped after the approval (or the reset, or both) was queued: the form says so, as the review would have.
        step.value = Step.Composing(
            error = queuedLeft(current.proposal, current.signed).takeIf { current.signing && current.signed > 0 },
        )
    }

    /** Signs as the owner at [address] (EIP-55) instead. */
    fun onOwnerChange(address: String) {
        val owner = Address.parse(address) ?: return
        step.update { current ->
            if (current is Step.Reviewing && current.idle && owner in current.proposal.safe.ownersHeld) {
                // Another owner signs every one of them, the approval queued by the last one too.
                current.copy(
                    owner = owner,
                    unlock = current.unlock.copy(error = null),
                    error = null,
                    simpleError = null,
                    signed = 0,
                )
            } else {
                current
            }
        }
    }

    /** Proposes the reviewed swap again: the Safe's nonce, its queue, the quote and the deadline anew. */
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
                    // The password typed meanwhile stays: it is the same review, hidden since or not.
                    next is Step.Reviewing -> latest.copy(
                        proposal = next.proposal,
                        proposedAt = next.proposedAt,
                        owner = next.owner,
                        accounts = next.accounts,
                        refreshing = false,
                        signed = 0,
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

    /** Signs every transaction of the reviewed proposal with the typed wallet password, and queues them. */
    fun sign() {
        val current = step.value as? Step.Reviewing ?: return
        val password = current.unlock.password
        if (!current.idle || password.text.isEmpty() || reproposed(current)) return
        val unlock = Unlock.WithPassword(password)
        sign(current) { unlock }
    }

    /** Signs every transaction of the reviewed proposal with a fingerprint or face, one prompt each. */
    fun signWithBiometrics() {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle || !current.unlock.biometrics || reproposed(current)) return
        // The prompt gets what is left of the proposal's age: past it, nothing is signed, and it is made again.
        val notAfter = promptDeadline(current.proposedAt) ?: return refresh(note = PROMPT_TOO_LATE)
        val label = current.safeAccount.label
        val requests = current.proposal.requests
        val decoding = decodingTokens(current.request)
        sign(current) { index ->
            val request = requests[index]
            val action = safeTxActionItem(SafeTxAction.of(request.safe, request.tx), request.nonce, decoding, network)
            val of = if (requests.size > 1) " ${index + 1} of ${requests.size}" else ""
            // The address it names in full, as screen 17's prompt does: the last thing read before the key signs.
            val named = action.addresses.firstOrNull()?.let { ", ${it.what.lowercase()} ${it.address}" }.orEmpty()
            val reason = "Sign Safe transaction$of of $label, nonce ${request.nonce}: ${action.title}$named"
            Unlock.WithBiometrics(reason, notAfter)
        }
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

    /**
     * Signs [review]'s transactions in nonce order, each with the unlock [unlockFor] gives for its
     * index, and queues each: each one's Safe check first, which leaving the screen or Back
     * cancels, then its signature, which runs to its end. A review whose approval was signed and
     * queued by an earlier tap, its swap not, goes on from the swap: the approval is not signed
     * twice. The first that is not signed stops the rest, and says which were.
     */
    private fun sign(review: Step.Reviewing, unlockFor: (index: Int) -> Unlock) {
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        val unlocked = review.unlock.copy(password = WalletPassword.EMPTY, error = null)
        step.value = review.copy(signing = true, checking = true, error = null, simpleError = null, unlock = unlocked)
        val requests = review.proposal.requests
        // What an earlier tap on this very review signed and queued already, as this owner.
        val from = review.signed
        job = viewModelScope.launch {
            for (index in from until requests.size) {
                val request = requests[index]
                // Left after the approval was signed: the swap is not signed over another screen, and
                // the review, made before, is proposed again when the screen is back.
                val left = (step.value as? Step.Reviewing)?.hidden == true
                if (index > 0 && left) {
                    step.update { if (it is Step.Reviewing) it.copy(signing = false, checking = false) else it }
                    stopAt(index, leftBefore(review.proposal, index))
                    return@launch
                }
                val unlock = unlockFor(index)
                // The check has what is left of the proposal's minute, less the prompt's least time
                // when the prompt signs: past that the signature could not be made in time anyway.
                val checkUntil = (unlock as? Unlock.WithBiometrics)?.notAfter?.let { it - MIN_PROMPT_TIME }
                    ?: (review.proposedAt + QUOTE_MAX_AGE)
                step.update { if (it is Step.Reviewing && it.signing) it.copy(checking = true) else it }
                val checked = try {
                    signSafeTx.check(request, review.owner, budget = -checkUntil.elapsedNow())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // the use case promises not to throw; the backstop
                }
                // Left while the check came back: nothing more is signed.
                currentCoroutineContext().ensureActive()
                step.update { if (it is Step.Reviewing && it.signing) it.copy(checking = false) else it }
                val unproven = (checked as? SafeTxSignCheck.Refused)?.refusal ==
                    SafeTxRefusal.SafeUnavailable(ReadUnavailable.UNPROVEN)
                if (checkUntil.hasPassedNow() && (checked is SafeTxSignCheck.Ready || unproven)) {
                    // The check took the time the proposal had left: nothing more is signed, it is made again.
                    step.update { if (it is Step.Reviewing && it.signing) it.copy(signing = false) else it }
                    if (index == 0) refresh(note = CHECK_TOOK_THE_TIME) else stopAt(index, CHECK_TOOK_THE_TIME)
                    return@launch
                }
                val signing = when (checked) {
                    // Signed and queued as one step, against the state just checked, which leaving
                    // cannot cut in half.
                    is SafeTxSignCheck.Ready -> withContext(NonCancellable) {
                        try {
                            signSafeTx.sign(checked, unlock)
                        } catch (e: Exception) {
                            null // the use case promises not to throw; the backstop
                        }
                    }
                    is SafeTxSignCheck.Refused -> SafeTxSigning.Refused(checked.refusal)
                    null -> null
                }
                if (signing is SafeTxSigning.Signed && signing.saved) {
                    step.update { if (it is Step.Reviewing && it.signing) it.copy(signed = index + 1) else it }
                    continue
                }
                val why = when (signing) {
                    is SafeTxSigning.Signed -> NOT_SAVED
                    is SafeTxSigning.NotSigned -> notSignedText(signing.signing, SIGN_FAILED)
                    is SafeTxSigning.Refused -> safeTxRefusalText(signing.refusal, network)
                    null -> SIGN_FAILED
                }
                val simpleWhy = (signing as? SafeTxSigning.Refused)?.let { simpleSafeTxRefusalText(it.refusal) }
                step.update { latest ->
                    if (latest !is Step.Reviewing) return@update latest
                    val notSigned = signing as? SafeTxSigning.NotSigned
                    // What the key store said goes under the unlock; anything else, and anything
                    // after the approval was queued, above it, saying what was queued.
                    val underUnlock = notSigned != null && index == 0
                    latest.copy(
                        signing = false,
                        error = if (underUnlock) {
                            null
                        } else {
                            signedSoFar(review.proposal, index)?.let { "$it $why" } ?: why
                        },
                        simpleError = simpleWhy?.let { simple ->
                            signedSoFar(review.proposal, index)?.let { "$it $simple" } ?: simple
                        },
                        unlock = latest.unlock.copy(
                            error = why.takeIf { underUnlock },
                            biometrics = latest.unlock.biometrics &&
                                (notSigned == null || biometricsStillOffered(notSigned.signing)),
                        ),
                    )
                }
                // The prompt outlived the proposal's age: nothing was signed, and it is made again.
                if (index == 0 && (signing as? SafeTxSigning.NotSigned)?.signing == Signing.TimedOut) {
                    refresh(note = PROMPT_TIMED_OUT)
                }
                // Hidden while it ran: the proposal is from before, so it is made again.
                reproposeIfHidden()
                return@launch
            }
            step.value = Step.Proposed("0x" + review.proposal.swap.request.hash.hex)
        }
    }

    /** The review signed up to (not including) [index], stopped for [why]. */
    private fun stopAt(index: Int, why: String) = step.update { latest ->
        if (latest !is Step.Reviewing) return@update latest
        latest.copy(error = signedSoFar(latest.proposal, index)?.let { "$it $why" } ?: why)
    }

    /**
     * What of [proposal] is signed and queued before the transaction at [index], when anything is:
     * the approval, or the allowance's reset to zero alone, or the reset and the approval.
     */
    private fun signedSoFar(proposal: SafeSwapProposal, index: Int): String? = when {
        index == 0 -> null
        proposal.reset == null -> APPROVAL_QUEUED
        index == 1 -> RESET_QUEUED
        else -> RESET_AND_APPROVAL_QUEUED
    }

    /** Why the transaction at [index] of [proposal] was not signed: the screen was left before it. */
    private fun leftBefore(proposal: SafeSwapProposal, index: Int): String =
        if (proposal.reset != null && index == 1) LEFT_BEFORE_THE_APPROVAL else LEFT_BEFORE_THE_SWAP

    /** What the form says of [proposal] left with [signed] of its transactions queued. */
    private fun queuedLeft(proposal: SafeSwapProposal, signed: Int): String = when {
        proposal.reset == null -> APPROVAL_QUEUED_LEFT
        signed == 1 -> RESET_QUEUED_LEFT
        else -> RESET_AND_APPROVAL_QUEUED_LEFT
    }

    /**
     * The review of [request], proposed now, signed by [owner] (the first held when that is no
     * owner any more) with [unlock] (a new one when null), or the form saying why there is none.
     */
    private suspend fun proposed(
        request: Request,
        owner: Address?,
        unlock: UnlockStep?,
        safeAccount: Account,
        accounts: List<Account>,
    ): Step {
        // Its age runs from its first read, not from its answer: the figures are as old as that. The
        // first is the one after the Safe's check, which may wait a while for a verified reading.
        var readFrom = timeSource.markNow()
        val proposing = try {
            proposeSafeSwap(
                safe,
                request.sell.asset,
                request.buy.asset,
                request.amount,
                request.tolerance,
                replacing = request.nonce,
            ) {
                readFrom = timeSource.markNow()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The use case promises not to throw; the backstop.
            SafeSwapProposing.Unavailable(ReadUnavailable.UNPROVEN, figure = null)
        }
        if (proposing !is SafeSwapProposing.Proposed) {
            return Step.Composing(error = refusal(proposing, request), simpleError = simpleRefusal(proposing, request))
        }
        val proposal = proposing.proposal
        val held = proposal.safe.ownersHeld
        val signer = owner?.takeIf { it in held } ?: held.firstOrNull()
            ?: return Step.Composing(error = NO_OWNER_HELD)
        return Step.Reviewing(
            request,
            proposal,
            readFrom,
            signer,
            unlock ?: UnlockStep(biometrics = biometricsOffered()),
            safeAccount,
            accounts,
        )
    }

    /** Applies [edit] to the form's inputs: only while composing (a fixed swap's tolerance with [fixedToo]). */
    private fun change(fixedToo: Boolean = false, edit: (Inputs) -> Inputs) {
        if (step.value !is Step.Composing) return
        if (replacing != null && !fixedToo) return
        job?.cancel()
        inputs.update(edit)
        step.value = Step.Composing()
    }

    private fun modelOf(
        list: AccountList,
        reading: SafeReading,
        value: AccountValue,
        typed: Inputs,
        replaced: Replaced?,
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
                    replaced == Replaced.Gone -> Sender.Cannot(REPLACED_GONE)
                    replaced == Replaced.Unreadable -> Sender.Cannot(QUEUE_UNREADABLE)
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
        // A swap proposed again: its pair and its amount, whatever was typed.
        val found = replaced as? Replaced.Found
        val inputs = found?.let { typed.copy(sell = it.swap.sold(), buy = it.swap.bought()) } ?: typed
        val options = swapOptions(value, inputs.sell, inputs.buy, network)
        val sell = options.sell
        val buy = options.buy
        val amount = found?.let { sell?.let { option -> found.swap.amountIn.formatUnits(option.decimals) } }
        return Model(
            sender = sender,
            accounts = accounts,
            currency = value.currency,
            sellOptions = if (found != null) listOfNotNull(sell) else options.sellOptions,
            sell = sell,
            buyOptions = if (found != null) listOfNotNull(buy) else options.buyOptions,
            buy = buy,
            tokensRead = value.tokensRead,
            inputs = amount?.let { inputs.copy(amount = it) } ?: inputs,
            replaced = replaced,
        )
    }

    private fun RouterSwap.sold(): SwapPick = if (sellsEther) SwapPick.Native else SwapPick.Token(route.tokenIn)

    private fun RouterSwap.bought(): SwapPick = if (buysEther) SwapPick.Native else SwapPick.Token(route.tokenOut)

    private fun render(model: Model?, step: Step): SafeSwapUiState {
        val network = network.displayName
        if (step is Step.Proposed) return SafeSwapUiState.Proposed(network, step.hash)
        // A review stands on its proposal, whatever the Safe's reading says meanwhile: a refresh or
        // the signature checks the Safe again, and says what it finds.
        if (step is Step.Reviewing) return review(model?.accounts ?: step.accounts, step)
        model ?: return SafeSwapUiState.Loading(network)
        val account = when (val sender = model.sender) {
            Sender.Checking -> return SafeSwapUiState.Loading(network)
            is Sender.Cannot -> return SafeSwapUiState.CannotSwap(network, sender.message)
            is Sender.From -> sender.account
        }
        val composing = step as Step.Composing
        val inputs = model.inputs
        val found = model.replaced as? Replaced.Found
        if (found != null && (model.sell == null || model.buy == null) && model.tokensRead) {
            return SafeSwapUiState.CannotSwap(network, REPLACED_UNKNOWN_TOKEN)
        }
        val amount = typedAmount(inputs.amount, model.sell)
        val at = now()
        val form = SwapForm(
            network = network,
            from = SendFrom(account.label, display),
            sellOptions = model.sellOptions.map { it.choice(at) },
            sell = model.sell?.choice(at),
            buyOptions = model.buyOptions.map { it.choice(at) },
            buy = model.buy?.choice(at),
            amount = inputs.amount,
            tolerance = inputs.tolerance,
            amountError = amount.error,
            amountHint = amountHint(amount.units, model.sell, model.currency, this.network),
            canMax = found == null && model.sell?.balance != null && !composing.proposing,
            toleranceError = toleranceError(inputs.tolerance),
            note = sellNote(model.sell, model.tokensRead, "this Safe"),
            quoting = composing.proposing,
            error = composing.error,
            simpleError = composing.simpleError,
            info = if (found != null) {
                null
            } else {
                "Proposed as Safe transactions its owners sign: the router's approval first when it needs one, " +
                    "then the swap, its minimum and a deadline ${SAFE_SWAP_DEADLINE_SECONDS / 3_600} hours away " +
                    "fixed now. Whoever executes it pays the network fee."
            },
            canReview = !composing.proposing && requestOf(model) != null,
            fixed = found != null,
        )
        return SafeSwapUiState.Compose(
            form,
            replacing = found?.let {
                "Proposes the swap queued at Safe nonce ${it.nonce} again, in its place: the same amount and " +
                    "pair, with a fresh minimum and deadline. Only one of the two can ever run, and the owners " +
                    "sign the new one anew."
            },
        )
    }

    private fun review(accounts: List<Account>, step: Step.Reviewing): SafeSwapUiState.Review {
        val proposal = step.proposal
        val sell = step.request.sell
        val buy = step.request.buy
        val held = proposal.safe.ownersHeld
        val labels = accounts.associate { it.address to it.label }
        val decoding = decodingTokens(step.request)
        val route = routeSteps(proposal.route, proposal.pools, sell, buy)
        val transactions = listOfNotNull(proposal.reset, proposal.approval, proposal.swap).map { tx ->
            SafeSwapTransaction(
                nonce = "Safe nonce ${tx.request.nonce}",
                action = safeTxActionItem(tx.action, tx.request.nonce, decoding, network),
            )
        }
        val requests = proposal.requests
        return SafeSwapUiState.Review(
            network = network.displayName,
            from = SendFrom(step.safeAccount.label, display),
            sell = sell.choice(),
            sold = exactAmount(proposal.amountIn, sell.decimals, sell.symbol),
            buy = buy.choice(),
            quoted = exactAmount(proposal.quotedOut, buy.decimals, buy.symbol),
            quotedAt = "QuoterV2's verified quote" +
                (proposal.quoteBlock?.let { " at block ${grouped(it)}" } ?: " at the head") +
                ": what the route returns for this amount now.",
            simpleQuotedAt = "QuoterV2's quote, checked on chain: what the route returns for this amount now.",
            minimum = "at least " + exactAmount(proposal.minimumOut, buy.decimals, buy.symbol),
            tolerance = "${proposal.tolerance} tolerance below the quote, fixed in the swap the owners sign: " +
                "it is not quoted again when they do.",
            route = route,
            routeNote = if (route.size == 1) {
                "One Uniswap v3 pool, as the pinned factory lists it, chosen by what it returns."
            } else {
                "Through WETH: two Uniswap v3 pools, as the pinned factory lists them, chosen by what they return."
            } + if (sell.asset == TransferAsset.Native || buy.asset == TransferAsset.Native) {
                " ETH goes in or out as WETH, which the router wraps or unwraps in the same transaction."
            } else {
                ""
            },
            priceImpact = priceImpactText(proposal.priceImpact),
            priceHint = priceHintText(network),
            spender = SpenderItem(ROUTER_NAME, Eip55.format(proposal.router)),
            deadline = "Refused by the router after ${formatTime(proposal.deadline)}: the verified head's time " +
                "and ${SAFE_SWAP_DEADLINE_SECONDS / 3_600} hours, for its owners to sign and one to execute it.",
            transactions = transactions,
            approvalNote = approvalNote(proposal, sell),
            queued = proposal.queuedAhead.takeIf { it > 0 }?.let { ahead ->
                if (ahead == 1) {
                    "1 transaction queued on this device runs before it."
                } else {
                    "$ahead transactions queued on this device run before it."
                }
            },
            replaces = if (proposal.replaces) {
                "Takes the place of the swap queued at Safe nonce ${proposal.swap.request.nonce}: only one of the " +
                    "two can ever run. The signatures collected for that one do not count for this one."
            } else {
                null
            },
            balance = SafeBalanceChange(
                what = "${sell.symbol} balance of the Safe",
                before = exactAmount(proposal.balanceBefore, sell.decimals, sell.symbol),
                queued = proposal.queuedAmount.takeUnless { it.isZero }?.let {
                    exactAmount(it, sell.decimals, sell.symbol) + " moved by those queued before it"
                },
                after = exactAmount(proposal.balanceAfter, sell.decimals, sell.symbol),
                verifiedAt = verifiedAt(proposal.balanceBlock),
            ),
            simulation = simulationText(proposal, buy),
            simpleSimulation = simpleSimulationText(proposal, buy),
            owners = held.map { AccountChoice(labels[it] ?: Eip55.format(it), Eip55.format(it)) },
            owner = Eip55.format(step.owner),
            unlock = step.unlock,
            refreshing = step.refreshing,
            signing = step.signing,
            checking = step.checking,
            progress = if (step.signing && requests.size > 1) {
                "Transaction ${minOf(step.signed + 1, requests.size)} of ${requests.size}"
            } else {
                null
            },
            error = step.error,
            simpleError = step.simpleError,
        )
    }

    private fun approvalNote(proposal: SafeSwapProposal, sell: AssetOption): String? {
        val allowance = proposal.allowance ?: return null
        val now = exactAmount(allowance, sell.decimals, sell.symbol)
        return if (proposal.reset != null) {
            "The router may take $now of the Safe's ${sell.symbol}, and ${sell.symbol} refuses to move one allowance " +
                "to another, as the approval of the whole amount showed: the first Safe transaction sets it to zero, " +
                "the next approves exactly the amount sold, each its own."
        } else if (proposal.approval != null) {
            "The router may take $now of the Safe's ${sell.symbol}: the approval before the swap lets it take " +
                "exactly the amount sold, in its own Safe transaction."
        } else {
            "The router may take $now of the Safe's ${sell.symbol}, on chain or once those queued before it " +
                "run: no approval of its own is needed."
        }
    }

    private fun simulationText(proposal: SafeSwapProposal, buy: AssetOption): String {
        val reset = when (val run = proposal.resetSimulation) {
            is SafeCallSimulation.Ran -> "The allowance set to zero, run as a verified call from the Safe" +
                (run.blockNumber?.let { " at block ${grouped(it)}" } ?: " at the head") + ", goes through; the " +
                "approval after it cannot run before it has, and is run as a verified call when its execution is " +
                "quoted. "
            SafeCallSimulation.NotRun, null -> ""
        }
        val approval = when (val run = proposal.approvalSimulation) {
            is SafeCallSimulation.Ran -> "The approval, run as a verified call from the Safe" +
                (run.blockNumber?.let { " at block ${grouped(it)}" } ?: " at the head") + ", goes through. "
            SafeCallSimulation.NotRun -> ""
            // Proposed with its reset, said above; or after one queued before it.
            null -> if (proposal.approval != null && proposal.reset == null) {
                "The approval cannot run before the allowance set to zero, queued before it, has: it is run as " +
                    "a verified call when its execution is quoted. "
            } else {
                ""
            }
        }
        val swap = when (val run = proposal.swapSimulation) {
            is SafeSwapSimulation.Ran -> "The swap, run as a verified call from the Safe" +
                (run.blockNumber?.let { " at block ${grouped(it)}" } ?: " at the head") + ", returns " +
                exactAmount(run.amountOut, buy.decimals, buy.symbol) + ": at least its minimum."
            SafeSwapSimulation.AfterApproval -> "The swap cannot run before the router's allowance is approved " +
                "on chain: it is run as a verified call when its execution is quoted, before anyone sends it."
            SafeSwapSimulation.NotRun -> "Not simulated: the light client on this device does not run a call " +
                "from the Safe's address. Its execution, the whole of it, is run as a verified call before anyone " +
                "sends it."
        }
        return reset + approval + swap
    }

    /**
     * [simulationText] in simple mode's words, with no block, and said with care (docs/simple-ui.md,
     * decision 8); null where it names none.
     */
    private fun simpleSimulationText(proposal: SafeSwapProposal, buy: AssetOption): String? {
        val ran = listOf(proposal.resetSimulation, proposal.approvalSimulation).any { it is SafeCallSimulation.Ran } ||
            proposal.swapSimulation is SafeSwapSimulation.Ran
        if (!ran) return null
        val reset = if (proposal.resetSimulation is SafeCallSimulation.Ran) {
            "Checked on chain: the allowance set to zero should go through; the approval after it is checked when " +
                "its execution is quoted. "
        } else {
            ""
        }
        val approval = if (proposal.approvalSimulation is SafeCallSimulation.Ran) {
            "Checked on chain: the approval should go through. "
        } else {
            ""
        }
        val swap = when (val run = proposal.swapSimulation) {
            is SafeSwapSimulation.Ran -> "Checked on chain: the swap should return " +
                exactAmount(run.amountOut, buy.decimals, buy.symbol) + ", at least its minimum."
            SafeSwapSimulation.AfterApproval -> "The swap is checked on chain when its execution is quoted, once " +
                "the router's allowance is approved."
            SafeSwapSimulation.NotRun -> "Not checked: the light client on this device does not run a call from " +
                "the Safe's address. Its execution, the whole of it, is checked on chain before anyone sends it."
        }
        return reset + approval + swap
    }

    private fun decodingTokens(request: Request): List<DecodingToken> =
        listOf(request.sell, request.buy).mapNotNull { option ->
            (option.asset as? TransferAsset.Erc20Token)?.token?.let { DecodingToken(it, option.warning != null) }
        }

    /** The form's swap, when it is complete: what a proposal is asked for. */
    private fun requestOf(model: Model): Request? {
        if (model.sender !is Sender.From) return null
        val sell = model.sell ?: return null
        val buy = model.buy ?: return null
        val amount = typedAmount(model.inputs.amount, sell).units ?: return null
        val tolerance = SlippageTolerance.parsePercent(model.inputs.tolerance.trim()) ?: return null
        return Request(sell, buy, amount, tolerance, (model.replaced as? Replaced.Found)?.nonce)
    }

    private fun refusal(proposing: SafeSwapProposing, request: Request): String {
        val sell = request.sell.symbol
        val buy = request.buy.symbol
        return when (proposing) {
            is SafeSwapProposing.Proposed -> error("a proposal is no refusal")
            SafeSwapProposing.NothingToSwap -> "There is nothing to swap: enter an amount above zero."
            SafeSwapProposing.NotASwap -> notASwapText(sell, buy)
            SafeSwapProposing.NoRoute -> noRouteText(sell, buy)
            is SafeSwapProposing.NoVenue -> noVenueText(proposing.contract, network)
            SafeSwapProposing.TooSmall -> TOO_SMALL
            SafeSwapProposing.InsufficientFunds -> "The Safe holds less ${network.nativeSymbol} than that, once the " +
                "transactions queued on this device before it have moved what they move."
            SafeSwapProposing.InsufficientToken -> "The Safe holds less $sell than that, once the transactions " +
                "queued on this device before it have moved what they move."
            SafeSwapProposing.NotAToken -> NOT_A_TOKEN
            is SafeSwapProposing.EarlierSwapQueued -> "A swap of $sell is queued at Safe nonce " +
                "${proposing.nonce}, and counts on the router's allowance this one would set: execute or reject " +
                "it first."
            is SafeSwapProposing.NextNonceTaken -> "The swap's Safe transactions would take the Safe's next " +
                "nonces, Safe nonce ${proposing.nonce} among them, where a transaction is queued already: execute " +
                "or reject what is queued first."
            is SafeSwapProposing.NeedsApproval -> "The router may take only " +
                exactAmount(proposing.allowance, request.sell.decimals, sell) + " of the Safe's $sell now, and a " +
                "swap proposed again gets no approval of its own: reject this one, then propose the swap from the " +
                "Safe's Swap, which proposes the approval with it."
            SafeSwapProposing.NothingToReplace -> REPLACED_GONE
            is SafeSwapProposing.ApprovalReverts -> {
                val at = proposing.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty()
                if (proposing.toZero) {
                    "$sell's contract refuses the approval of the amount over the allowance the router has, and " +
                        "refuses to set that allowance to zero too, each run as a verified call from the Safe$at. " +
                        "Nothing was proposed."
                } else {
                    "$sell's contract refuses the approval, run as a verified call from the Safe$at with its " +
                        "allowance at zero. Nothing was proposed."
                }
            }
            is SafeSwapProposing.Reverts -> "The swap reverts, or returns less than its minimum, run as a verified " +
                "call from the Safe" + (proposing.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty()) +
                ". Nothing was proposed."
            is SafeSwapProposing.Refused -> safeTxRefusalText(proposing.refusal, network)
            is SafeSwapProposing.Unavailable -> when (proposing.reason) {
                ReadUnavailable.NO_ENGINE -> "There is no light client on this device, so nothing can be swapped."
                ReadUnavailable.NETWORK_OFF -> "The network's light client is not running, so nothing can be swapped."
                ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so nothing can be proposed."
                ReadUnavailable.SYNCING -> "The light client is still syncing, so nothing can be proposed yet."
                ReadUnavailable.UNPROVEN ->
                    "${figureText(proposing.figure, "of the proposal")} could not be verified this time: try again."
                ReadUnavailable.REFUSED -> "The light client on this device cannot quote this swap."
            }
        }
    }

    /** [refusal] in simple mode's words, with no block (docs/simple-ui.md); null where it names none. */
    private fun simpleRefusal(proposing: SafeSwapProposing, request: Request): String? = when (proposing) {
        is SafeSwapProposing.ApprovalReverts -> if (proposing.toZero) {
            "${request.sell.symbol}'s contract refuses the approval of the amount over the allowance the router " +
                "has, and refuses to set that allowance to zero too, as checked on chain. Nothing was proposed."
        } else {
            "${request.sell.symbol}'s contract refuses the approval with its allowance at zero, as checked on " +
                "chain. Nothing was proposed."
        }
        is SafeSwapProposing.Reverts ->
            "The swap reverts, or returns less than its minimum, as checked on chain. Nothing was proposed."
        is SafeSwapProposing.Refused -> simpleSafeTxRefusalText(proposing.refusal)
        else -> null
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
        const val SIGN_FAILED = "Serotine could not finish this signature. Nothing more was queued: try again."
        const val NOT_SAVED =
            "It was signed, but this device's queue cannot be saved right now, so it was not kept: sign again in " +
                "a moment."
        const val APPROVAL_QUEUED = "The approval is signed and queued; the swap is not. Refresh, and the swap " +
            "is proposed after the approval:"
        const val LIST_UNAVAILABLE =
            "Your account list cannot be read right now, so nothing can be proposed. Serotine keeps trying."
        const val NOT_IN_WALLET = "This Safe is not in your wallet any more."
        const val NOT_A_SAFE_ACCOUNT = "Only a Safe proposes a Safe transaction: this account swaps with Swap."
        const val NOT_A_SAFE = "This address no longer holds a recognised Safe, so nothing can be proposed from it."
        const val UNVERIFIED =
            "The Safe's owners cannot be verified right now, so nothing can be proposed. Serotine keeps trying."
        const val WATCH_ONLY =
            "No owner of this Safe has its key on this device: it is watch-only here, and nothing can be proposed " +
                "from it."
        const val NO_OWNER_HELD =
            "No owner of this Safe has its key on this device any more, so nobody here can sign the proposal."
        const val REPLACED_GONE = "The swap to propose again is no longer in this device's queue, or the Safe has " +
            "passed its nonce: there is nothing to put in its place."
        const val LEFT_BEFORE_THE_SWAP = "The screen was left before the swap was signed."
        const val LEFT_BEFORE_THE_APPROVAL = "The screen was left before the approval was signed."
        const val RESET_QUEUED = "The allowance set to zero is signed and queued; the approval and the swap are " +
            "not. Refresh, and they are proposed after it:"
        const val RESET_AND_APPROVAL_QUEUED = "The allowance set to zero and the approval are signed and queued; " +
            "the swap is not. Refresh, and the swap is proposed after them:"
        const val RESET_AND_APPROVAL_QUEUED_LEFT = "The allowance set to zero and the approval are signed and " +
            "queued; the swap is not. Review again, and the swap is proposed after them."
        const val RESET_QUEUED_LEFT = "The allowance set to zero is signed and queued; the approval and the swap " +
            "are not. Review again, and they are proposed after it."
        const val QUEUE_UNREADABLE = "This device's queue cannot be read right now, so the swap to propose again " +
            "cannot be found. Serotine keeps trying."
        const val APPROVAL_QUEUED_LEFT = "The approval is signed and queued; the swap is not. Review again, and " +
            "the swap is proposed after the approval."
        const val REPLACED_UNKNOWN_TOKEN = "The swap to propose again sells or buys a token your wallet does not " +
            "list: add it in Settings to propose the swap again."
    }
}
