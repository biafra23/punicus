package eth.serotine.ui.swap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.CowPlacement
import eth.serotine.domain.repository.CowRefusal
import eth.serotine.domain.repository.CowUnavailable
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.repository.WaitingSwap
import eth.serotine.domain.usecase.AccountValue
import eth.serotine.domain.usecase.ApprovalQuoting
import eth.serotine.domain.usecase.COW_ORDER_VALIDITY_SECONDS
import eth.serotine.domain.usecase.ChooseSwapVenue
import eth.serotine.domain.usecase.CowNotOffered
import eth.serotine.domain.usecase.CowOfferedOnThisDevice
import eth.serotine.domain.usecase.CowOrderPlacing
import eth.serotine.domain.usecase.CowSwapQuote
import eth.serotine.domain.usecase.CowSwapQuoting
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.DropWaitingSwap
import eth.serotine.domain.usecase.FindWaitingSwap
import eth.serotine.domain.usecase.KeepWaitingSwap
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveSwapVenue
import eth.serotine.domain.usecase.PlaceCowOrder
import eth.serotine.domain.usecase.QuoteCowSwap
import eth.serotine.domain.usecase.QuoteFigure
import eth.serotine.domain.usecase.QuoteSwap
import eth.serotine.domain.usecase.QuoteTokenApproval
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.SWAP_DEADLINE_SECONDS
import eth.serotine.domain.usecase.SendSwap
import eth.serotine.domain.usecase.SendTokenApproval
import eth.serotine.domain.usecase.SwapQuote
import eth.serotine.domain.usecase.SwapQuoting
import eth.serotine.domain.usecase.SwapSending
import eth.serotine.domain.usecase.TokenApprovalQuote
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.domain.usecase.WarmFeeHistory
import eth.serotine.ui.components.AssetOption
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NATIVE_DECIMALS
import eth.serotine.ui.components.NOT_A_TOKEN
import eth.serotine.ui.components.PROMPT_TIMED_OUT
import eth.serotine.ui.components.PROMPT_TOO_LATE
import eth.serotine.ui.components.QUOTE_MAX_AGE
import eth.serotine.ui.components.ROUTER_NAME
import eth.serotine.ui.components.STALE_QUOTE
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
import eth.serotine.ui.components.feeDetail
import eth.serotine.ui.components.figureText
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.noRouteText
import eth.serotine.ui.components.noVenueText
import eth.serotine.ui.components.notASwapText
import eth.serotine.ui.components.notSentItem
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.priceHintText
import eth.serotine.ui.components.priceImpactText
import eth.serotine.ui.components.promptDeadline
import eth.serotine.ui.components.redatedWhile
import eth.serotine.ui.components.routeSteps
import eth.serotine.ui.components.sellNote
import eth.serotine.ui.components.swapOptions
import eth.serotine.ui.components.toleranceError
import eth.serotine.ui.components.typedAmount
import eth.serotine.ui.components.verifiedAt
import eth.serotine.ui.platform.formatDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Swap screen's ViewModel (docs/swaps.md; screens 22 and 23): a swap from [from] on [network],
 * composed — the venue, the asset sold ([sell], the token asked for, null for the network's
 * currency), the asset bought, the amount in the sold asset's units, the slippage tolerance and
 * the fee level — then quoted for review and signed.
 *
 * - **The venue** is the user's to choose (*The user's choice of venue*): it starts at the one last
 *   chosen ([ObserveSwapVenue]), Uniswap the first time, and a choice is kept for the next swap
 *   ([ChooseSwapVenue]). CoW is greyed out where it is not offered, and a tap says why. When CoW
 *   cannot quote a swap, the compose step offers the same swap on Uniswap: nothing switches by
 *   itself.
 * - **On Uniswap**, the swap is quoted from verified state ([QuoteSwap]) and signed and broadcast
 *   ([SendSwap]).
 * - **On CoW**, it is quoted ([QuoteCowSwap]: CoW's estimate beside the verified Uniswap net) and
 *   its order signed and handed to CoW ([PlaceCowOrder]). CoW is asked only once the user chose
 *   it and asked for the review (CLAUDE.md, *Data sources*).
 * - A swap whose router, or CoW's vault relayer, may not take the amount yet first gets its approval
 *   ([QuoteTokenApproval], [SendTokenApproval]), its own transaction: once it is sent, the screen waits
 *   for its block and quotes the next step for the user to confirm. One signed but not broadcast is
 *   retried with its own bytes ([RebroadcastTransfer]), never signed anew. The swap is kept as waiting
 *   ([KeepWaitingSwap]) until a swap or an order of the token is sent from the account
 *   ([DropWaitingSwap]): opened [continuing] from the account's Activity, its next step is quoted
 *   again from what was kept ([FindWaitingSwap]).
 *
 * What is signed is the quote under review, and a quote is never signed stale, as on Send: it is
 * made when the review is entered, again on Refresh, when the screen is shown again after it was
 * not, and when it is older than [QUOTE_MAX_AGE] at the tap that would sign it ([timeSource]
 * tells); the biometric prompt gets what is left of that age ([promptDeadline]). [SendSwap] runs
 * the swap once more before it signs, and one that no longer returns its minimum is quoted again
 * and shown, not signed. A price never reaches it: every amount is the pool's or the user's.
 *
 * One of the ViewModels the [WalletPassword] passes through (CLAUDE.md, *Trust*): it holds it in
 * the review's state while the field is shown, hands it to the signer in an [Unlock], and never
 * logs, saves or caches it; it is dropped once used, when the screen is not shown ([onHidden]) and
 * when the screen is gone ([onCleared]). [formatTime] dates the swap's deadline in the user's own
 * time zone. [fill], when given, is what the compose step opens with: the same swap again.
 */
class SwapViewModel(
    private val account: AccountId,
    sell: Address?,
    observeAccounts: ObserveAccounts,
    observeAccountValue: ObserveAccountValue,
    private val describeKeyStore: DescribeKeyStore,
    private val quoteSwap: QuoteSwap,
    private val sendSwap: SendSwap,
    private val quoteTokenApproval: QuoteTokenApproval,
    private val sendTokenApproval: SendTokenApproval,
    private val rebroadcastTransfer: RebroadcastTransfer,
    private val warmFeeHistory: WarmFeeHistory,
    observeSwapVenue: ObserveSwapVenue,
    private val chooseSwapVenue: ChooseSwapVenue,
    cowOfferedOnThisDevice: CowOfferedOnThisDevice,
    private val quoteCowSwap: QuoteCowSwap,
    private val placeCowOrder: PlaceCowOrder,
    private val keepWaitingSwap: KeepWaitingSwap,
    private val dropWaitingSwap: DropWaitingSwap,
    private val findWaitingSwap: FindWaitingSwap,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val formatTime: (Long) -> String = ::formatDateTime,
    private val now: () -> Long = ::epochSecondsNow,
    fill: SwapFill? = null,
    continuing: Boolean = false,
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
     * What the compose step has: the asset sold, the asset bought (null until chosen), the amount
     * and the tolerance as typed, the fee level, and the venue chosen on this screen (null: the one
     * last chosen).
     */
    private data class Inputs(
        val sell: SwapPick,
        val buy: SwapPick? = null,
        val amount: String = "",
        val tolerance: String = SlippageTolerance.DEFAULT.percent,
        val fee: FeeLevel = FeeLevel.NORMAL,
        val venue: SwapVenue? = null,
    )

    /** The account swapped from, or why nothing can be swapped from it. */
    private sealed interface Swapper {
        data class From(val account: Account) : Swapper

        data class Cannot(val message: String) : Swapper
    }

    /**
     * Everything the compose step is derived from. [venue] is the one the swap goes to: the one
     * chosen, unless CoW is, and is not offered for it ([cowUnavailable] says why).
     */
    private class Model(
        val swapper: Swapper,
        val currency: DisplayCurrency,
        val sellOptions: List<AssetOption>,
        val sell: AssetOption?,
        val buyOptions: List<AssetOption>,
        val buy: AssetOption?,
        val tokensRead: Boolean,
        val inputs: Inputs,
        val venue: SwapVenue,
        val cowUnavailable: String?,
    )

    /** A swap the compose step can have quoted, exactly as it has it, on [venue]. */
    private data class Request(
        val inputs: Inputs,
        val sell: AssetOption,
        val buy: AssetOption,
        val amount: UInt256,
        val tolerance: SlippageTolerance,
        val venue: SwapVenue,
    )

    /** What a quote comes to: something to review ([Quoted]), or the compose step saying why not. */
    private sealed interface Next

    /** What a review signs: the approval first, the swap on Uniswap, or the CoW order. */
    private sealed interface Quoted : Next {
        data class Approval(val quote: TokenApprovalQuote) : Quoted

        data class Swap(val quote: SwapQuote) : Quoted

        data class Cow(val quote: CowSwapQuote) : Quoted
    }

    /**
     * A transaction signed but not broadcast: why ([broadcast]), and the [transfer] a retry sends;
     * [unverified] when a retry's rejection could not be checked against the receipt.
     */
    private data class NotSent(val broadcast: Broadcast, val transfer: SentTransfer, val unverified: Boolean = false) {
        val canRetry: Boolean
            get() = unverified || broadcast is Broadcast.Unavailable || transfer.maybeSent
    }

    /** Where the flow stands. */
    private sealed interface Step {
        /**
         * Composing; [quoting] while a review is quoted, [error] why the last one was not, [info]
         * what to know (an approval sent), and [earlier] the transaction that holds the swap back.
         * [uniswapInstead]: CoW could not quote the swap, and the same swap may go to Uniswap.
         * [liveOrder]: the UID of an earlier CoW order of the token that may still fill.
         * [simpleError]: [error] in simple mode's words, with no block, or null where it names none.
         */
        data class Composing(
            val quoting: Boolean = false,
            val error: String? = null,
            val info: String? = null,
            val earlier: EarlierItem? = null,
            val uniswapInstead: Boolean = false,
            val liveOrder: String? = null,
            val simpleError: String? = null,
        ) : Step, Next

        /**
         * [quoted] of [request], made at [quotedAt], under review; [hidden] once the screen stopped
         * being shown since it was made.
         */
        data class Reviewing(
            val request: Request,
            val quoted: Quoted,
            val quotedAt: TimeMark,
            val unlock: UnlockStep,
            val sender: SendFrom,
            val refreshing: Boolean = false,
            val signing: Boolean = false,
            val notSent: NotSent? = null,
            val hidden: Boolean = false,
            /**
             * Which step of the swap this is, after a step before it was sent ([Step.Awaiting]): the
             * step's number and how many there are; null for the first one quoted.
             */
            val position: Pair<Int, Int>? = null,
            /**
             * The hash of the approval sent before, when a block has it and the allowance still falls
             * short: it may have failed.
             */
            val earlierFailed: String? = null,
        ) : Step {
            val idle: Boolean
                get() = !refreshing && !signing && notSent == null
        }

        /**
         * The approval [hash] of [request] is broadcast, and the swap waits for a block to have it
         * (`ApprovalPending`, docs/simple-ui.md): quoted again until it is, then reviewed as step 2.
         */
        data class Awaiting(
            val request: Request,
            val hash: String,
            val sender: SendFrom,
            val resets: Boolean,
            /** Which step was sent ([Pair.first]) of how many ([Pair.second]). */
            val sent: Pair<Int, Int>,
            /** When the wait began: it gives up [AWAIT_MAX] after. */
            val since: TimeMark,
        ) : Step

        data class Sent(val hash: String) : Step

        /** The CoW order [record] is signed and handed to CoW, as its placement says. */
        data class Placed(val record: CowOrderRecord) : Step
    }

    // The checksum once, not on every balance reading.
    private val display = Eip55.format(from)

    // Whether CoW can be offered here at all: a fact of the device, read once.
    private val cowHere = cowOfferedOnThisDevice()

    private val inputs = MutableStateFlow(
        Inputs(
            sell = sell?.let(SwapPick::Token) ?: SwapPick.Native,
            buy = fill?.let { it.buy?.let(SwapPick::Token) ?: SwapPick.Native },
            amount = fill?.amount.orEmpty(),
            venue = fill?.venue,
        ),
    )
    private val step = MutableStateFlow<Step>(Step.Composing())

    /** The quote, signature or broadcast in flight. */
    private var job: Job? = null

    /**
     * The wait for an approval's block ([Step.Awaiting]): its own job, as it is started from within
     * [job]'s, which a launch that runs at once would otherwise overwrite.
     */
    private var waiting: Job? = null

    /** Whether the screen is shown now ([onShown], [onHidden]). */
    private var shown = false

    /** The waiting swap Continue opened, until its review is quoted: the step that review is. */
    private var resumed: WaitingSwap? = null

    /** Whether the screen was hidden while the review's first quote was being made. */
    private var hiddenWhileQuoting = false

    private val model: StateFlow<Model?> =
        combine(
            observeAccounts(),
            observeAccountValue(account),
            inputs,
            observeSwapVenue(),
        ) { list, value, inputs, last ->
            modelOf(list, value, inputs, last)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            null,
        )

    val uiState: StateFlow<SwapUiState> = combine(model, step) { model, step -> model to step }
        // A balance's age, which simple mode shows, is dated anew every second on the compose step.
        .redatedWhile { (model, step) ->
            step is Step.Composing && model != null &&
                (model.sellOptions + model.buyOptions).any { it.provenAt != null }
        }
        .map { (model, step) -> render(model, step) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            SwapUiState.Loading(network.displayName),
        )

    init {
        // Opened with Continue: once what the screen reads is set up, the waiting swap is quoted again.
        if (continuing && sell != null) continueWaiting(sell)
    }

    /**
     * Sells the token at [contract], or the network's currency (null). The venue shown stays as it
     * is: one CoW was greyed out for comes back only at the user's tap.
     */
    fun onSellChange(contract: String?) {
        val pick = SwapPick.of(contract) ?: return
        val shown = model.value?.venue
        change {
            val venue = if (pick == SwapPick.Native) SwapVenue.UNISWAP else it.venue ?: shown
            it.copy(sell = pick, buy = it.buy.takeUnless { buy -> buy == pick }, amount = "", venue = venue)
        }
    }

    /** Buys the token at [contract], or the network's currency (null). */
    fun onBuyChange(contract: String?) {
        val pick = SwapPick.of(contract) ?: return
        change { it.copy(buy = pick) }
    }

    /** The amount field now reads [text], in the sold asset's units. */
    fun onAmountChange(text: String) = change { it.copy(amount = text) }

    /**
     * Fills in the sold asset's whole verified balance. For the network's currency that leaves
     * nothing for the fee, which the quote then says.
     */
    fun onMax() {
        val option = model.value?.sell ?: return
        val balance = option.balance ?: return
        change { it.copy(amount = balance.formatUnits(option.decimals)) }
    }

    /** The tolerance field now reads [text], a percentage. */
    fun onToleranceChange(text: String) = change { it.copy(tolerance = text) }

    /** Chooses the fee [level]. */
    fun onFeeChange(level: FeeLevel) = change { it.copy(fee = level) }

    /**
     * Swaps on [venue], kept as the one the next swap starts at. CoW while it is not offered for the
     * swap is not chosen: the screen says why instead.
     */
    fun onVenueChange(venue: SwapVenue) {
        val model = model.value ?: return
        if (venue == SwapVenue.COW && model.cowUnavailable != null) return
        change { it.copy(venue = venue) }
        viewModelScope.launch {
            try {
                chooseSwapVenue(venue)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not kept for the next swap: this one still goes where the user chose.
            }
        }
    }

    /**
     * The same swap on Uniswap: after CoW could not quote it, or from CoW's review when by CoW's own
     * estimate Uniswap gives more. The user's choice, never made for them.
     */
    fun swapOnUniswap() {
        when (val current = step.value) {
            is Step.Composing -> if (!current.uniswapInstead || current.quoting) return
            is Step.Reviewing -> {
                val order = current.quoted as? Quoted.Cow ?: return
                if (!current.idle || !order.quote.belowNet) return
                job?.cancel()
                step.value = Step.Composing()
            }
            else -> return
        }
        onVenueChange(SwapVenue.UNISWAP)
        review(venue = SwapVenue.UNISWAP)
    }

    /** Quotes the swap as composed and shows it, or the approval it needs first, for review. */
    fun review() = review(venue = null)

    /** [review], on [venue] when it is given (chosen a moment ago), else on the model's. */
    private fun review(venue: SwapVenue?) {
        val current = step.value as? Step.Composing ?: return
        if (current.quoting) return
        val model = model.value ?: return
        val request = requestOf(model)?.let { request ->
            venue?.let { request.copy(inputs = request.inputs.copy(venue = it), venue = it) } ?: request
        } ?: return
        val account = (model.swapper as? Swapper.From)?.account ?: return
        // A transaction only waiting for a block stays said while the quote runs, and after one
        // that failed short of finding it again (a read unavailable, the light client syncing).
        val waiting = current.earlier?.takeIf { it.waiting }
        val quoting = Step.Composing(quoting = true, info = waiting?.let { current.info }, earlier = waiting)
        step.value = quoting
        hiddenWhileQuoting = false
        job = viewModelScope.launch {
            val next = quoted(request, unlock = null, SendFrom(account.label, display))
            if (step.value != quoting) return@launch
            // Continued from the account's Activity: the review is the step that waited.
            val waited = resumed?.takeIf { it.isFor(request) }
            resumed = null
            val pending = next as? Step.Composing
            if (waited != null && pending?.earlier?.waiting == true && pending.earlier.hash == waited.approval) {
                // Its approval is still on its way: the screen waits for its block, as when it was sent.
                step.value = Step.Awaiting(
                    request,
                    waited.approval,
                    SendFrom(account.label, display),
                    resets = waited.steps == RESET_STEPS && waited.step == 2,
                    sent = waited.step - 1 to waited.steps,
                    since = timeSource.markNow(),
                )
                awaitApproval()
                return@launch
            }
            step.value = when {
                // Made while hidden: it is from before the screen came back, so it is made again.
                next is Step.Reviewing -> {
                    val numbered = waited?.let { positioned(next, it.step - 1 to it.steps, it.approval) } ?: next
                    numbered.copy(hidden = hiddenWhileQuoting)
                }
                next is Step.Composing && next.earlier == null && waiting != null ->
                    next.copy(info = quoting.info, earlier = waiting)
                else -> next
            }
            requoteIfHidden()
        }
    }

    /**
     * Back from a review to the compose step: the quote and the password go. Not while signing, nor
     * after a broadcast no peer answered: only a retry of those bytes may send them again.
     */
    fun edit() {
        val current = step.value as? Step.Reviewing ?: return
        if (current.signing || current.notSent?.canRetry == true) return
        job?.cancel()
        resumed = null
        step.value = Step.Composing()
    }

    /** Quotes the reviewed approval or swap again. */
    fun refresh() = refresh(note = null)

    private fun refresh(note: String?) {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle) return
        step.value = current.copy(refreshing = true, hidden = false, unlock = current.unlock.copy(error = null))
        job = viewModelScope.launch {
            val next = quoted(current.request, unlock = current.unlock, current.sender)
            step.update { latest ->
                when {
                    latest !is Step.Reviewing || !latest.refreshing -> latest
                    // The password typed meanwhile stays when it is the same review: not when the
                    // approval became the swap (mined meanwhile) or the other way round, which the
                    // user has not read yet. Nor does its step number, which was the old quote's:
                    // the new one is numbered as a first quote is.
                    next is Step.Reviewing -> if (next.quoted::class == latest.quoted::class) {
                        latest.copy(
                            request = next.request,
                            quoted = next.quoted,
                            quotedAt = next.quotedAt,
                            refreshing = false,
                            unlock = latest.unlock.copy(error = note),
                        )
                    } else {
                        latest.copy(
                            request = next.request,
                            quoted = next.quoted,
                            quotedAt = next.quotedAt,
                            refreshing = false,
                            unlock = latest.unlock.copy(password = WalletPassword.EMPTY, error = note),
                            position = null,
                            earlierFailed = null,
                        )
                    }
                    else -> next
                }
            }
            requoteIfHidden()
        }
    }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = step.update { current ->
        if (current is Step.Reviewing && !current.signing && current.notSent == null) {
            current.copy(unlock = current.unlock.copy(password = WalletPassword(text), error = null))
        } else {
            current
        }
    }

    /** Signs the reviewed approval or swap with the typed wallet password, and broadcasts it. */
    fun sign() {
        val current = step.value as? Step.Reviewing ?: return
        val password = current.unlock.password
        if (!current.idle || password.text.isEmpty() || requoted(current)) return
        sign(current, Unlock.WithPassword(password))
    }

    /** Signs the reviewed approval or swap with a fingerprint or face, and broadcasts it. */
    fun signWithBiometrics() {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle || !current.unlock.biometrics || requoted(current)) return
        val notAfter = promptDeadline(current.quotedAt) ?: return refresh(note = PROMPT_TOO_LATE)
        // What is signed, in full: the prompt is the last thing read before the key signs.
        val request = current.request
        val sold = { units: UInt256 -> exactAmount(units, request.sell.decimals, request.sell.symbol) }
        val bought = { units: UInt256 -> exactAmount(units, request.buy.decimals, request.buy.symbol) }
        val prompt = when (val quoted = current.quoted) {
            is Quoted.Approval -> "Allow ${Eip55.format(quoted.quote.spender)} to take ${sold(quoted.quote.amount)}"
            is Quoted.Swap -> "Swap ${sold(quoted.quote.amountIn)} for at least ${bought(quoted.quote.minimumOut)}"
            is Quoted.Cow ->
                "Sign a CoW order: ${sold(quoted.quote.amountIn)} for at least ${bought(quoted.quote.minimumOut)}"
        }
        sign(current, Unlock.WithBiometrics(prompt, notAfter))
    }

    /** Whether [review]'s quote is too old to sign, and so is being made again instead. */
    private fun requoted(review: Step.Reviewing): Boolean {
        if (review.hidden) {
            refresh(note = null)
            return true
        }
        if (review.quotedAt.elapsedNow() <= QUOTE_MAX_AGE) return false
        refresh(note = STALE_QUOTE)
        return true
    }

    /** Broadcasts the approval or swap signed but not sent again, byte for byte. */
    fun retry() {
        val current = step.value as? Step.Reviewing ?: return
        val notSent = current.notSent ?: return
        if (current.signing || !notSent.canRetry) return
        step.value = current.copy(signing = true)
        job = viewModelScope.launch {
            val sending = safely { rebroadcastTransfer(notSent.transfer) }
            step.update { latest -> if (latest is Step.Reviewing) after(latest, sending) else latest }
            keepOrLetGo(current)
            awaitApproval()
        }
    }

    /**
     * While the approval is on its way ([Step.Awaiting]): quotes the swap again every [AWAIT_POLL]
     * until a block has the approval, then shows its review, step 2, for the user to confirm: nothing
     * is signed for it before that. A swap that can no longer be quoted goes back to the compose
     * step, which says why, and so does one whose approval no block has after [AWAIT_MAX], which says
     * it is still on its way. Leaving the screen ends it.
     */
    private fun awaitApproval() {
        val awaiting = step.value as? Step.Awaiting ?: return
        waiting?.cancel()
        waiting = viewModelScope.launch {
            var failed = 0
            var polls = 0
            // Bounded by time, and by the polls that fit in it should a clock not move. At least one read:
            // a wait shown again past its time checks the chain before it says no block has the approval.
            do {
                delay(AWAIT_POLL)
                val next = quoted(awaiting.request, unlock = null, awaiting.sender)
                if (step.value != awaiting) return@launch
                when {
                    // Still not in a block: the quote says the approval is pending. Another transaction
                    // pending (an earlier swap of the token) is not waited out here: the compose step says it.
                    next is Step.Composing && next.info == APPROVAL_PENDING && next.error == null -> failed = 0
                    next is Step.Reviewing -> {
                        step.value = positioned(next, awaiting.sent, awaiting.hash).copy(hidden = !shown)
                        requoteIfHidden()
                        return@launch
                    }
                    // A read that failed for a moment (a peer, the light client between heads): waited out.
                    next is Step.Composing && next.error != null && ++failed < AWAIT_FAILURES -> Unit
                    else -> {
                        // The quote cannot go on: the compose step says why, and keeps the approval's link.
                        val approval = EarlierItem(awaiting.hash, "Open the approval", waiting = true)
                        step.value = if (next is Step.Composing) next.copy(earlier = next.earlier ?: approval) else next
                        return@launch
                    }
                }
            } while (++polls < (AWAIT_MAX / AWAIT_POLL).toInt() && awaiting.since.elapsedNow() < AWAIT_MAX)
            // No block has it yet: back to the compose step, which says so and offers its status.
            if (step.value == awaiting) {
                step.value = Step.Composing(
                    info = APPROVAL_PENDING,
                    earlier = EarlierItem(awaiting.hash, "Open the approval", waiting = true),
                )
            }
        }
    }

    /**
     * [next], quoted after step [sent] of the swap was sent (its first, how many there are) by the
     * approval [hash]: numbered as the step after it. An approval again where the swap should come
     * is not: the one sent may have failed in its block, or been replaced, which the review says with
     * its status before another is sent.
     */
    private fun positioned(next: Step.Reviewing, sent: Pair<Int, Int>, hash: String): Step.Reviewing {
        val (done, total) = sent
        val position = done + 1 to total
        val quoted = next.quoted
        val failedApproval = quoted is Quoted.Approval && (position.first == total || quoted.quote.resetsFirst)
        return next.copy(
            position = if (failedApproval) null else position,
            earlierFailed = hash.takeIf { failedApproval },
        )
    }

    /**
     * Keeps the swap of [review] as waiting once its approval is sent ([Step.Awaiting]), for Continue in
     * the account's Activity; lets it go once the swap, or the order, is. A swap of the network's
     * currency needs no approval, so none of it waits. Not saved: no card, and nothing else changes.
     */
    private suspend fun keepOrLetGo(review: Step.Reviewing) {
        val request = review.request
        val token = (request.sell.asset as? TransferAsset.Erc20Token)?.token?.address ?: return
        val now = step.value
        // Run to its end, the screen left or not: a swap sent must not stay offered with Continue.
        withContext(NonCancellable) { keepOrLetGo(now, request, token) }
    }

    private suspend fun keepOrLetGo(now: Step, request: Request, token: Address) {
        when (now) {
            is Step.Awaiting -> keepWaitingSwap(
                WaitingSwap(
                    account = account,
                    sell = token,
                    buy = (request.buy.asset as? TransferAsset.Erc20Token)?.token?.address,
                    amount = request.amount,
                    tolerance = request.tolerance,
                    venue = request.venue,
                    approval = now.hash,
                    step = now.sent.first + 1,
                    steps = now.sent.second,
                ),
            )
            is Step.Sent, is Step.Placed -> dropWaitingSwap(account, token)
            else -> Unit
        }
    }

    /** Whether [request] is this waiting swap: the same token sold, for the same asset, amount and venue. */
    private fun WaitingSwap.isFor(request: Request): Boolean =
        (request.sell.asset as? TransferAsset.Erc20Token)?.token?.address == sell &&
            (request.buy.asset as? TransferAsset.Erc20Token)?.token?.address == buy &&
            request.amount == amount && request.venue == venue

    /**
     * Opened with Continue from the account's Activity: the swap kept as waiting for the token at
     * [sell] is filled in and quoted again, its review numbered as the step that waits. None kept: the
     * compose step, as for any swap. A form the kept inputs leave short (the balance moved since) stays
     * at the compose step, which says why.
     */
    private fun continueWaiting(sell: Address) {
        viewModelScope.launch {
            val waiting = findWaitingSwap(account, sell) ?: return@launch
            val token = SwapPick.Token(waiting.sell)
            val opened = inputs.value
            // Once the account's tokens are read: a token no longer among them is said by the compose step,
            // and nothing is waited for, so that nothing keeps the balances read for a screen left behind.
            val shown = model.first { it != null && it.inputs.sell == token && (it.sell != null || it.tokensRead) }
            val option = shown?.sell ?: return@launch
            // The user changed the form meanwhile: it is theirs, and nothing is quoted for them.
            if (inputs.value != opened || opened.sell != token) return@launch
            val filled = opened.copy(
                buy = waiting.buy?.let(SwapPick::Token) ?: SwapPick.Native,
                amount = waiting.amount.formatUnits(option.decimals),
                tolerance = waiting.tolerance.percent,
                venue = waiting.venue,
            )
            change { filled }
            // Read for the form as filled, or edited since, which ends the wait too.
            val read = model.first { it?.inputs == filled || inputs.value != filled } ?: return@launch
            // A swap the form cannot quote as it stands (a balance moved, a token no longer listed) stays
            // at the compose step, which says why; one edited meanwhile is the user's.
            if (inputs.value != filled || requestOf(read) == null) return@launch
            resumed = waiting
            review()
        }
    }

    /** The first step of a swap whose approval is [quote]: 1 of 2, or of 3 when the allowance is set to zero first. */
    private fun firstOf(quote: TokenApprovalQuote): Pair<Int, Int> = 1 to if (quote.resetsFirst) 3 else 2

    /** The screen stopped being shown: the password goes, and the review is quoted again when it is shown. */
    fun onHidden() {
        shown = false
        // Nothing is read for a screen no one sees: the wait goes on when it is shown again.
        waiting?.cancel()
        if ((step.value as? Step.Composing)?.quoting == true) hiddenWhileQuoting = true
        step.update { current ->
            if (current is Step.Reviewing) {
                current.copy(unlock = current.unlock.copy(password = WalletPassword.EMPTY), hidden = true)
            } else {
                current
            }
        }
    }

    /** The screen is shown (again): a review left while hidden is quoted anew. */
    fun onShown() {
        shown = true
        if (waiting?.isActive != true) awaitApproval()
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
            var noLongerMet = false
            val sending = when (val quoted = review.quoted) {
                // An order is placed, not broadcast: what follows is its own.
                is Quoted.Cow -> {
                    place(quoted.quote, unlock)
                    return@launch
                }
                is Quoted.Approval -> safely { sendTokenApproval(quoted.quote, unlock) }
                is Quoted.Swap -> when (val swapped = safely { sendSwap(quoted.quote, unlock) }) {
                    is SwapSending.Done -> swapped.sending
                    is SwapSending.NoLongerMet -> {
                        noLongerMet = true
                        null
                    }
                    is SwapSending.NotRun -> {
                        step.update { latest ->
                            if (latest !is Step.Reviewing) return@update latest
                            val error = notRunText(swapped.reason)
                            latest.copy(signing = false, unlock = latest.unlock.copy(error = error))
                        }
                        requoteIfHidden()
                        return@launch
                    }
                    null -> null
                }
            }
            if (noLongerMet) {
                step.update { latest -> if (latest is Step.Reviewing) latest.copy(signing = false) else latest }
                refresh(note = NO_LONGER_MET)
                return@launch
            }
            step.update { latest -> if (latest is Step.Reviewing) after(latest, sending) else latest }
            keepOrLetGo(review)
            awaitApproval()
            // The prompt outlived the quote's age: nothing was signed, and the quote is made again.
            if ((sending as? TransferSending.NotSigned)?.signing == Signing.TimedOut) refresh(note = PROMPT_TIMED_OUT)
            requoteIfHidden()
        }
    }

    /**
     * The CoW order of [quote] signed with [unlock] and handed to CoW ([PlaceCowOrder]): placed, it
     * is said so; a quote that no longer stands is made again and shown; else the review says why
     * nothing was sent.
     */
    private suspend fun place(quote: CowSwapQuote, unlock: Unlock) {
        val placing = safely { placeCowOrder(quote, unlock) }
        val requote = when (placing) {
            is CowOrderPlacing.Placed -> {
                val review = step.value as? Step.Reviewing
                step.update { latest -> if (latest is Step.Reviewing) Step.Placed(placing.record) else latest }
                review?.let { keepOrLetGo(it) }
                return
            }
            CowOrderPlacing.FloorRose -> FLOOR_ROSE
            CowOrderPlacing.QuoteStale -> STALE_QUOTE
            is CowOrderPlacing.NotSigned -> if (placing.signing == Signing.TimedOut) PROMPT_TIMED_OUT else null
            else -> null
        }
        step.update { latest ->
            if (latest !is Step.Reviewing) return@update latest
            if (requote != null) return@update latest.copy(signing = false)
            val error = when (placing) {
                is CowOrderPlacing.NotSigned -> notSignedText(placing.signing, ORDER_FAILED)
                is CowOrderPlacing.EarlierOrderLive -> earlierOrderText(latest.request)
                CowOrderPlacing.OrdersUnreadable -> ORDERS_UNREADABLE
                is CowOrderPlacing.FloorUnavailable -> "The Uniswap floor could not be quoted again just before " +
                    "signing: nothing was signed. Try again."
                CowOrderPlacing.NotRecorded -> "The order was signed, but this device could not record it, so it " +
                    "was not sent: nothing can fill it. Try again."
                else -> ORDER_FAILED
            }
            val biometrics = latest.unlock.biometrics &&
                ((placing as? CowOrderPlacing.NotSigned)?.signing?.let(::biometricsStillOffered) ?: true)
            latest.copy(signing = false, unlock = latest.unlock.copy(error = error, biometrics = biometrics))
        }
        if (requote != null) refresh(note = requote)
        requoteIfHidden()
    }

    /**
     * Where [review] goes once its transaction's signature or broadcast came to [sending]: a swap
     * broadcast is sent; an approval broadcast is back at the compose step, which says so and
     * quotes the swap once a block has it; the rest stays in the review, saying why.
     */
    private fun after(review: Step.Reviewing, sending: TransferSending?): Step {
        val approval = review.quoted is Quoted.Approval
        return when (sending) {
            // The approval is step 1 of 2: the swap waits for it, and is quoted on its own once a block has it.
            is TransferSending.Sent -> if (approval) {
                Step.Awaiting(
                    review.request,
                    sending.hash,
                    review.sender,
                    resets = (review.quoted as Quoted.Approval).quote.resetsFirst,
                    sent = review.position ?: firstOf((review.quoted as Quoted.Approval).quote),
                    since = timeSource.markNow(),
                )
            } else {
                Step.Sent(sending.hash)
            }
            is TransferSending.NotSigned -> review.copy(
                signing = false,
                unlock = review.unlock.copy(
                    error = notSignedText(sending.signing, if (approval) APPROVAL_FAILED else SWAP_FAILED),
                    biometrics = review.unlock.biometrics && biometricsStillOffered(sending.signing),
                ),
            )
            is TransferSending.NotSent ->
                review.copy(signing = false, notSent = NotSent(sending.broadcast, sending.transfer))
            is TransferSending.RejectionUnverified -> review.copy(
                signing = false,
                notSent = NotSent(sending.rejection, sending.transfer, unverified = true),
            )
            null -> review.copy(
                signing = false,
                unlock = review.unlock.copy(error = if (approval) APPROVAL_FAILED else SWAP_FAILED),
            )
        }
    }

    /**
     * The review of [request] quoted now, with [unlock] (a new one when null): the swap's or the
     * order's, or the approval's it needs first; or the compose step saying why there is neither.
     */
    private suspend fun quoted(request: Request, unlock: UnlockStep?, sender: SendFrom): Step {
        val readFrom = timeSource.markNow()
        val quoted = when (val next = quoteOf(request)) {
            is Quoted -> next
            is Step.Composing -> return next
        }
        return Step.Reviewing(request, quoted, readFrom, unlock ?: UnlockStep(biometrics = biometricsOffered()), sender)
    }

    /** What [request] comes to on its venue: its quote, the approval it needs first, or why neither. */
    private suspend fun quoteOf(request: Request): Next = when (request.venue) {
        SwapVenue.UNISWAP -> when (val swapping = askSwap(request)) {
            is SwapQuoting.Quoted -> Quoted.Swap(swapping.quote)
            is SwapQuoting.NeedsApproval -> approvalFirst(swapping.token, swapping.spender, request) {
                when (val again = askSwap(request)) {
                    is SwapQuoting.Quoted -> Quoted.Swap(again.quote)
                    else -> composing(again, request)
                }
            }
            else -> composing(swapping, request)
        }
        // CoW is asked only here: the user chose it, and asked for the review.
        SwapVenue.COW -> when (val ordering = askCow(request)) {
            is CowSwapQuoting.Quoted -> Quoted.Cow(ordering.quote)
            is CowSwapQuoting.NeedsApproval -> approvalFirst(ordering.token, ordering.spender, request) {
                when (val again = askCow(request)) {
                    is CowSwapQuoting.Quoted -> Quoted.Cow(again.quote)
                    else -> cowComposing(again, request)
                }
            }
            else -> cowComposing(ordering, request)
        }
    }

    /**
     * The approval of [spender] for [request]'s amount of [token], its own transaction first; or,
     * allowed meanwhile (an approval mined since), the quote [allowed] makes after all; or why
     * neither.
     */
    private suspend fun approvalFirst(
        token: Token,
        spender: Address,
        request: Request,
        allowed: suspend () -> Next,
    ): Next = when (val approving = askApproval(token, spender, request.amount, request.inputs.fee)) {
        is ApprovalQuoting.Quoted -> Quoted.Approval(approving.quote)
        is ApprovalQuoting.AlreadyAllowed -> allowed()
        is ApprovalQuoting.ApprovalPending -> Step.Composing(
            info = APPROVAL_PENDING,
            earlier = EarlierItem(approving.hash, "Open the approval", waiting = true),
        )
        is ApprovalQuoting.EarlierSwapPending -> Step.Composing(
            info = earlierSwapText(request),
            earlier = EarlierItem(approving.hash, "Open the earlier swap", waiting = true),
        )
        is ApprovalQuoting.EarlierMaybeSent -> Step.Composing(
            error = EARLIER_MAYBE_SENT,
            earlier = EarlierItem(approving.hash, "Open the earlier transaction", waiting = false),
        )
        else -> Step.Composing(
            error = approvalRefusal(approving, request.sell),
            simpleError = simpleApprovalRefusal(approving, request.sell),
        )
    }

    private suspend fun askSwap(request: Request): SwapQuoting = try {
        quoteSwap(from, request.sell.asset, request.buy.asset, request.amount, request.tolerance, request.inputs.fee)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SwapQuoting.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
    }

    private suspend fun askCow(request: Request): CowSwapQuoting = try {
        quoteCowSwap(from, request.sell.asset, request.buy.asset, request.amount, request.tolerance)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CowSwapQuoting.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
    }

    private suspend fun askApproval(token: Token, spender: Address, amount: UInt256, fee: FeeLevel) = try {
        quoteTokenApproval(from, token, spender, amount, fee)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApprovalQuoting.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
    }

    /** The compose step saying why [quoting], not a quote, came back. */
    private fun composing(quoting: SwapQuoting, request: Request): Step.Composing = when (quoting) {
        is SwapQuoting.ApprovalPending -> Step.Composing(
            info = APPROVAL_PENDING,
            earlier = EarlierItem(quoting.hash, "Open the approval", waiting = true),
        )
        is SwapQuoting.EarlierSwapPending -> Step.Composing(
            info = earlierSwapText(request),
            earlier = EarlierItem(quoting.hash, "Open the earlier swap", waiting = true),
        )
        // Short of the allowance on a second read, after an approval read it as enough.
        is SwapQuoting.NeedsApproval -> Step.Composing(error = ALLOWANCE_MOVED)
        is SwapQuoting.EarlierMaybeSent -> Step.Composing(
            error = EARLIER_MAYBE_SENT,
            earlier = EarlierItem(quoting.hash, "Open the earlier transaction", waiting = false),
        )
        else -> Step.Composing(error = swapRefusal(quoting, request), simpleError = simpleSwapRefusal(quoting))
    }

    /** The compose step saying why [quoting], not an order to review, came back from CoW's quote. */
    private fun cowComposing(quoting: CowSwapQuoting, request: Request): Step.Composing = when (quoting) {
        is CowSwapQuoting.ApprovalPending -> Step.Composing(
            info = APPROVAL_PENDING,
            earlier = EarlierItem(quoting.hash, "Open the approval", waiting = true),
        )
        // Short of the allowance on a second read, after an approval read it as enough.
        is CowSwapQuoting.NeedsApproval -> Step.Composing(error = RELAYER_ALLOWANCE_MOVED)
        is CowSwapQuoting.EarlierMaybeSent -> Step.Composing(
            error = EARLIER_MAYBE_SENT,
            earlier = EarlierItem(quoting.hash, "Open the earlier transaction", waiting = false),
        )
        else -> Step.Composing(
            error = cowRefusal(quoting, request),
            uniswapInstead = uniswapMayTake(quoting),
            liveOrder = (quoting as? CowSwapQuoting.EarlierOrderLive)?.uid?.hex,
        )
    }

    /**
     * Whether the same swap is offered on Uniswap after [quoting]: CoW itself refused it or did not
     * answer (docs/swaps.md, *The user's choice of venue*). Only those come after the record of
     * orders was read, so no earlier order of the token may still fill: the two could both swap.
     */
    private fun uniswapMayTake(quoting: CowSwapQuoting): Boolean =
        quoting is CowSwapQuoting.CowRefused || quoting is CowSwapQuoting.CowNoAnswer

    private fun earlierSwapText(request: Request) = when (request.venue) {
        SwapVenue.UNISWAP -> "An earlier swap of ${request.sell.symbol} is on its way and not in a block yet: " +
            "this one waits for it, since both would count on one allowance."
        SwapVenue.COW -> "An earlier swap of ${request.sell.symbol} is on its way and not in a block yet: the " +
            "order's approval waits for it."
    }

    /**
     * Applies [edit] to the compose step's inputs: only while composing, dropping a quote in flight
     * and the last one's verdict. A transaction of the sold asset's that is only waiting for a
     * block still holds the swap back, so it stays said while that asset does.
     */
    private fun change(edit: (Inputs) -> Inputs) {
        val current = step.value as? Step.Composing ?: return
        job?.cancel()
        // An edit makes it another swap than the one that waited: its review is numbered as any.
        resumed = null
        val before = inputs.value.sell
        inputs.update(edit)
        val pending = current.earlier?.takeIf { it.waiting && inputs.value.sell == before }
        step.value = Step.Composing(info = pending?.let { current.info }, earlier = pending)
    }

    /** [send] run to its end, the screen gone or not: a signed transaction is recorded whatever happens. */
    private suspend fun <T> safely(send: suspend () -> T): T? =
        withContext(NonCancellable) {
            try {
                send()
            } catch (e: Exception) {
                null // the use cases promise not to throw; the backstop
            }
        }

    private fun modelOf(list: AccountList, value: AccountValue, inputs: Inputs, last: SwapVenue): Model {
        val swapper = when (list) {
            is AccountList.Unreadable -> Swapper.Cannot(LIST_UNAVAILABLE)
            is AccountList.Loaded -> {
                val account = list.accounts.firstOrNull { it.id == this@SwapViewModel.account }
                when {
                    account == null -> Swapper.Cannot(NOT_IN_WALLET)
                    account.kind == AccountKind.SAFE -> Swapper.Cannot(SAFE)
                    account.custody != AccountCustody.InKeyStore -> Swapper.Cannot(WATCH_ONLY)
                    else -> Swapper.From(account)
                }
            }
        }
        val options = swapOptions(value, inputs.sell, inputs.buy, network)
        val sell = options.sell
        val buy = options.buy
        // What the compose step can know: the rest of what keeps CoW from a swap, its quote says.
        val cowUnavailable = when {
            !cowHere -> NOT_ON_THIS_DEVICE
            sell?.asset == TransferAsset.Native -> SELLS_ETHER
            else -> null
        }
        val chosen = inputs.venue ?: last
        return Model(
            swapper = swapper,
            currency = value.currency,
            sellOptions = options.sellOptions,
            sell = sell,
            buyOptions = options.buyOptions,
            buy = buy,
            tokensRead = value.tokensRead,
            inputs = inputs,
            venue = if (chosen == SwapVenue.COW && cowUnavailable != null) SwapVenue.UNISWAP else chosen,
            cowUnavailable = cowUnavailable,
        )
    }

    private fun render(model: Model?, step: Step): SwapUiState {
        val network = network.displayName
        val composing = when (step) {
            is Step.Sent -> return SwapUiState.Sent(network, step.hash)
            is Step.Placed -> return placed(step.record)
            is Step.Reviewing -> return review(step)
            is Step.Awaiting -> return awaiting(step)
            is Step.Composing -> step
        }
        model ?: return SwapUiState.Loading(network)
        val account = when (val swapper = model.swapper) {
            is Swapper.Cannot -> return SwapUiState.CannotSwap(network, swapper.message)
            is Swapper.From -> swapper.account
        }
        val inputs = model.inputs
        val at = now()
        val amount = typedAmount(inputs.amount, model.sell)
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
            canMax = model.sell?.balance != null && !composing.quoting,
            toleranceError = toleranceError(inputs.tolerance),
            note = sellNote(model.sell, model.tokensRead, "this account"),
            quoting = composing.quoting,
            error = composing.error,
            simpleError = composing.simpleError,
            info = composing.info,
            canReview = !composing.quoting && requestOf(model) != null,
        )
        return SwapUiState.Compose(
            form,
            inputs.fee,
            earlier = composing.earlier,
            venue = VenueItem(model.venue, cowUnavailable = model.cowUnavailable),
            uniswapInstead = composing.uniswapInstead && !composing.quoting,
            liveOrder = composing.liveOrder,
        )
    }

    /** The approval [step] waits for: its step is sent, and the next is quoted once a block has it. */
    private fun awaiting(step: Step.Awaiting): SwapUiState.ApprovalPending {
        val cow = step.request.venue == SwapVenue.COW
        val then = if (cow) "the order is quoted" else "the swap is quoted"
        val (done, total) = step.sent
        return SwapUiState.ApprovalPending(
            network = network.displayName,
            hash = step.hash,
            title = if (step.resets) {
                "${stepText(done to total)} sent: the allowance set to zero"
            } else {
                "${stepText(done to total)} sent: the approval"
            },
            message = if (step.resets) {
                "Once a block has it, the approval of the whole amount is quoted here for you to confirm, then the " +
                    (if (cow) "order." else "swap.") + " $LATER"
            } else {
                "Once a block has the approval, $then again here and you confirm step ${done + 1}. $LATER " +
                    "The approval only lets ${step.request.sell.symbol} be taken in " +
                    (if (cow) "an order you sign yourself." else "a swap you send yourself.")
            },
        )
    }

    private fun review(step: Step.Reviewing): SwapUiState = when (val quoted = step.quoted) {
        is Quoted.Approval -> approvalReview(step, quoted.quote)
        is Quoted.Swap -> swapReview(step, quoted.quote).copy(step = step.position?.let(::stepText))
        is Quoted.Cow -> cowReview(step, quoted.quote).copy(step = step.position?.let(::stepText))
    }

    private fun approvalReview(step: Step.Reviewing, quote: TokenApprovalQuote): SwapUiState.ApprovalReview {
        val sell = step.request.sell
        val position = step.position ?: firstOf(quote)
        val native = network.nativeSymbol
        val ran = quote.simulationBlock?.let { "at block ${grouped(it)}" } ?: "at the head"
        return SwapUiState.ApprovalReview(
            network = network.displayName,
            from = step.sender,
            token = sell.choice(),
            amount = exactAmount(quote.amount, sell.decimals, sell.symbol),
            allowance = exactAmount(quote.allowance, sell.decimals, sell.symbol),
            spender = SpenderItem(spenderName(step.request.venue), Eip55.format(quote.spender)),
            resetNote = if (quote.resetsFirst) {
                "${sell.symbol} refuses to move one allowance to another, as the approval of the whole amount " +
                    "showed: this sets it to zero first, and the whole amount is approved once that is in a block."
            } else {
                null
            },
            maxFee = exactAmount(quote.maxFee, NATIVE_DECIMALS, native),
            feeDetail = feeDetail(quote.level, quote.transaction, quote.feeBlock),
            balance = BalanceChange(
                what = "$native balance, which pays the fee",
                short = "$native balance",
                before = exactAmount(quote.nativeBefore, NATIVE_DECIMALS, native),
                after = "at least " + exactAmount(quote.nativeAfterAtLeast, NATIVE_DECIMALS, native),
                verifiedAt = verifiedAt(quote.balanceBlock),
            ),
            simulation = "The approval, run as a verified call from this account $ran, goes through. It moves " +
                "nothing: " + if (step.request.venue == SwapVenue.COW) {
                    "the order is signed on its own, quoted once a block has this one."
                } else {
                    "the swap is its own transaction, quoted once a block has this one."
                },
            unlock = step.unlock,
            refreshing = step.refreshing,
            signing = step.signing,
            notSent = step.notSent?.let {
                notSentItem(it.broadcast, it.unverified, what = "approval", maybeSent = it.transfer.maybeSent)
            },
            step = stepText(position),
            next = nextAfter(position, quote, step.request.venue),
            headline = if (quote.resetsFirst) {
                "Set what ${spenderName(step.request.venue)} may use of ${sell.symbol} to zero"
            } else {
                "Allow ${spenderName(step.request.venue)} to use " +
                    exactAmount(quote.amount, sell.decimals, sell.symbol)
            },
            scope = if (quote.resetsFirst) {
                "It allows nothing: the whole amount is approved in the next step."
            } else {
                "Only this amount, for this swap."
            },
            earlierFailed = step.earlierFailed?.let {
                FailedApproval(
                    "A block has the approval sent before, yet the allowance still falls short: it may have " +
                        "failed. Open it to see why before you send another.",
                    it,
                )
            },
        )
    }

    /** Who the approval lets take the token on [venue]. */
    private fun spenderName(venue: SwapVenue) = if (venue == SwapVenue.COW) VAULT_RELAYER_NAME else ROUTER_NAME

    /** What follows the approval at [position]: the approval of the whole amount after a reset, or the swap. */
    private fun nextAfter(position: Pair<Int, Int>, quote: TokenApprovalQuote, venue: SwapVenue): String {
        val then = if (venue == SwapVenue.COW) "the order" else "the swap"
        val number = position.first + 1
        return when {
            quote.resetsFirst -> "Step $number, the approval of the whole amount: once a block has this one, it is " +
                "checked again and you confirm it, then $then."
            venue == SwapVenue.COW -> "Step $number, the order: once a block has this approval, the order is " +
                "quoted again and you sign it."
            else -> "Step $number, the swap: once a block has this approval, the price is checked again and you " +
                "confirm the swap."
        }
    }

    private fun swapReview(step: Step.Reviewing, quote: SwapQuote): SwapUiState.SwapReview {
        val sell = step.request.sell
        val buy = step.request.buy
        val native = network.nativeSymbol
        val route = routeSteps(quote.route, quote.pools, sell, buy)
        val sold = sell.asset as? TransferAsset.Erc20Token
        val bought = buy.asset as? TransferAsset.Erc20Token
        val balances = listOfNotNull(
            quote.sellBefore?.let { before ->
                val after = quote.sellAfter ?: return@let null
                BalanceChange(
                    what = "${sell.symbol} balance",
                    before = exactAmount(before, sell.decimals, sell.symbol),
                    after = exactAmount(after, sell.decimals, sell.symbol),
                    verifiedAt = verifiedAt(quote.tokenBalanceBlock),
                )
            },
            quote.buyBefore?.let { before ->
                BalanceChange(
                    what = "${buy.symbol} balance",
                    before = exactAmount(before, buy.decimals, buy.symbol),
                    after = "at least " + exactAmount(before + quote.minimumOut, buy.decimals, buy.symbol),
                    verifiedAt = verifiedAt(quote.buyBalanceBlock),
                )
            },
            BalanceChange(
                what = "$native balance" + if (sold != null && bought != null) ", which pays the fee" else "",
                short = "$native balance",
                before = exactAmount(quote.nativeBefore, NATIVE_DECIMALS, native),
                after = "at least " + exactAmount(
                    if (buy.asset == TransferAsset.Native) quote.nativeAfterAtLeast + quote.minimumOut else
                        quote.nativeAfterAtLeast,
                    NATIVE_DECIMALS,
                    native,
                ),
                verifiedAt = verifiedAt(quote.balanceBlock),
            ),
        )
        return SwapUiState.SwapReview(
            network = network.displayName,
            from = step.sender,
            sell = sell.choice(),
            sold = exactAmount(quote.amountIn, sell.decimals, sell.symbol),
            buy = buy.choice(),
            receive = exactAmount(quote.returnedOut, buy.decimals, buy.symbol),
            ranAt = "The swap, run as a verified call from this account " +
                (quote.simulationBlock?.let { "at block ${grouped(it)}" } ?: "at the head") + ", returns this.",
            minimum = "at least " + exactAmount(quote.minimumOut, buy.decimals, buy.symbol),
            tolerance = "${quote.tolerance} tolerance, below QuoterV2's verified quote of " +
                exactAmount(quote.quotedOut, buy.decimals, buy.symbol),
            route = route,
            routeNote = if (route.size == 1) {
                "One Uniswap v3 pool, as the pinned factory lists it."
            } else {
                "Through WETH: two Uniswap v3 pools, as the pinned factory lists them."
            } + if (sell.asset == TransferAsset.Native || buy.asset == TransferAsset.Native) {
                " ETH goes in or out as WETH, which the router wraps or unwraps in the same transaction."
            } else {
                ""
            },
            priceImpact = priceImpactText(quote.priceImpact),
            priceHint = priceHintText(network),
            spender = SpenderItem(ROUTER_NAME, Eip55.format(quote.router)),
            // A token sold: the quote read the router's allowance as covering the amount.
            allowanceNote = sold?.let {
                "An earlier approval already lets the router take this much ${sell.symbol}: no approval is needed."
            },
            deadline = "Refused by the router after ${formatTime(quote.deadline)}: the verified head's time " +
                "and ${SWAP_DEADLINE_SECONDS / 60} minutes.",
            maxFee = exactAmount(quote.maxFee, NATIVE_DECIMALS, native),
            feeDetail = feeDetail(quote.level, quote.transaction, quote.feeBlock),
            balances = balances,
            pending = pendingText(quote, sell),
            netNote = if (quote.netOfFees) {
                null
            } else {
                "The route was chosen by what it returns, without its network fee, which could not be had right now."
            },
            unlock = step.unlock,
            refreshing = step.refreshing,
            signing = step.signing,
            notSent = step.notSent?.let {
                notSentItem(it.broadcast, it.unverified, what = "swap", maybeSent = it.transfer.maybeSent)
            },
        )
    }

    /**
     * Screen 23 for a CoW order (docs/swaps.md, *The user's choice of venue*): CoW's estimate, not
     * verified, beside the verified Uniswap net, which gives more, and the minimum signed.
     */
    private fun cowReview(step: Step.Reviewing, quote: CowSwapQuote): SwapUiState.CowReview {
        val sell = step.request.sell
        val buy = step.request.buy
        val bought = { units: UInt256 -> exactAmount(units, buy.decimals, buy.symbol) }
        val at = quote.quoteBlock?.let { " at block ${grouped(it)}" }.orEmpty()
        val feeNote = quote.uniswapFeeOut?.let { " less its expected network fee, about ${bought(it)}" }
            ?: ": its network fee could not be had, so it is not taken off"
        return SwapUiState.CowReview(
            network = network.displayName,
            from = step.sender,
            sell = sell.choice(),
            sold = exactAmount(quote.amountIn, sell.decimals, sell.symbol),
            buy = buy.choice(),
            estimate = "about " + bought(quote.estimate),
            estimateNote = "CoW's own estimate, net of the network fee it takes out of the trade: " +
                exactAmount(quote.cowFee, sell.decimals, sell.symbol) + ". Not verified.",
            uniswapNet = bought(quote.uniswapNet),
            uniswapNote = "Uniswap's verified quote$at$feeNote.",
            comparison = if (quote.belowNet) {
                "By CoW's own estimate, Uniswap gives more. The order is signed at the floor below, and may well " +
                    "not fill. Then nothing is swapped, but the ${sell.symbol} it sells stays held back until a " +
                    "finalized block is past its validity, about 18 to 24 minutes from now."
            } else {
                "By CoW's estimate, CoW gives about ${bought(quote.estimate - quote.uniswapNet)} more than Uniswap. " +
                    "Only the minimum below is signed."
            },
            uniswapGivesMore = quote.belowNet,
            minimum = "at least " + bought(quote.minimumOut),
            minimumNote = "CoW's estimate less the ${quote.tolerance} tolerance, never below the Uniswap net less it " +
                "(${bought(quote.floor)}). A fill that buys less is refused by the settlement contract.",
            validUntil = "Until ${formatTime(quote.order.validTo)}: the verified head's time and " +
                "${COW_ORDER_VALIDITY_SECONDS / 60} minutes. It fills by then or not at all.",
            settlement = SpenderItem(SETTLEMENT_NAME, Eip55.format(quote.deployment.settlement)),
            balances = listOf(
                BalanceChange(
                    what = "${sell.symbol} balance",
                    before = exactAmount(quote.sellBefore, sell.decimals, sell.symbol),
                    after = exactAmount(quote.sellAfter, sell.decimals, sell.symbol) + " once it fills",
                    verifiedAt = verifiedAt(quote.tokenBalanceBlock),
                ),
            ),
            pending = quote.pendingSell.takeUnless { it.isZero }?.let {
                "Earlier transactions from this account, not mined yet, may still take " +
                    exactAmount(it, sell.decimals, sell.symbol) + ": the balance after counts it."
            },
            disclosure = "Sent to CoW's servers (api.cow.fi): this account's address, the pair, the amounts, the " +
                "signed order and this device's IP address. Whether it fills, and what it bought, is read from " +
                "the chain, never from CoW.",
            unlock = step.unlock,
            refreshing = step.refreshing,
            signing = step.signing,
        )
    }

    /** What became of the CoW order [record] when it was handed to CoW. */
    private fun placed(record: CowOrderRecord): SwapUiState.OrderPlaced {
        val until = formatTime(record.order.validTo)
        val sold = record.sell as? TransferAsset.Erc20Token
        val held = "The ${sold?.token?.symbol ?: "token"} it sells is held back from other sends and swaps until it " +
            "fills or can no longer fill."
        val (title, message) = when (val placement = record.placement) {
            is CowPlacement.Accepted -> "Sent to CoW" to
                "CoW took the order. It fills before $until or not at all; what it bought shows once the chain " +
                "proves it. $held"
            is CowPlacement.Refused -> "CoW refused the order" to
                "CoW refused it (${placement.errorType}). It is signed all the same, so it counts as live until a " +
                "finalized block is past $until. $held"
            is CowPlacement.Unclear -> if (placement.why == CowUnavailable.UNDECODABLE) {
                "CoW's answer could not be read" to
                    "CoW answered, but not in a way this version can check. The order may be with CoW, and may " +
                    "fill until $until. $held"
            } else {
                "CoW's answer did not come" to "The order may be with CoW, and may fill until $until. $held"
            }
            CowPlacement.Attempted -> "CoW's answer did not come" to
                "The order may be with CoW, and may fill until $until. $held"
        }
        return SwapUiState.OrderPlaced(network.displayName, title, message, record.uid.hex)
    }

    /** What this account's earlier transactions, not mined yet, and its CoW orders may still take, when anything. */
    private fun pendingText(quote: SwapQuote, sell: AssetOption): String? {
        val native = network.nativeSymbol
        val parts = listOfNotNull(
            quote.pendingSell?.takeUnless { it.isZero }?.let { exactAmount(it, sell.decimals, sell.symbol) },
            quote.pendingCost.takeUnless { it.isZero }?.let { "up to " + exactAmount(it, NATIVE_DECIMALS, native) },
        )
        val held = quote.heldByOrders?.takeUnless { it.isZero }?.let { exactAmount(it, sell.decimals, sell.symbol) }
        val sentences = listOfNotNull(
            parts.takeIf { it.isNotEmpty() }?.let {
                "Earlier transactions from this account, not mined yet, may still take ${it.joinToString(" and ")}."
            },
            held?.let { "This account's CoW orders that may still fill hold back $it." },
        )
        if (sentences.isEmpty()) return null
        return sentences.joinToString(" ") + " The balance after counts it."
    }

    /** The compose step's swap, when it is complete: what a quote is asked for. */
    private fun requestOf(model: Model): Request? {
        if (model.swapper !is Swapper.From) return null
        val sell = model.sell ?: return null
        val buy = model.buy ?: return null
        val amount = typedAmount(model.inputs.amount, sell).units ?: return null
        val tolerance = SlippageTolerance.parsePercent(model.inputs.tolerance.trim()) ?: return null
        return Request(model.inputs, sell, buy, amount, tolerance, model.venue)
    }

    private fun swapRefusal(quoting: SwapQuoting, request: Request): String = when (quoting) {
        is SwapQuoting.Quoted, is SwapQuoting.NeedsApproval, is SwapQuoting.ApprovalPending,
        is SwapQuoting.EarlierSwapPending, is SwapQuoting.EarlierMaybeSent,
        -> error("not a refusal said as text")
        SwapQuoting.NothingToSwap -> "There is nothing to swap: enter an amount above zero."
        SwapQuoting.NotASwap -> notASwapText(request.sell.symbol, request.buy.symbol)
        SwapQuoting.NoRoute -> noRouteText(request.sell.symbol, request.buy.symbol)
        is SwapQuoting.NoVenue -> noVenueText(quoting.contract, network)
        SwapQuoting.TooSmall -> TOO_SMALL
        SwapQuoting.InsufficientFunds -> if (request.sell.asset == TransferAsset.Native) {
            "Not enough ${network.nativeSymbol} for this amount and the most the fee can be: swap less, to leave " +
                "room for the fee. Transactions not mined yet count too."
        } else {
            "Not enough ${network.nativeSymbol} for the most the fee can be. Transactions not mined yet count too."
        }
        SwapQuoting.InsufficientToken -> "This account holds less ${request.sell.symbol} than that, once what its " +
            "transactions not mined yet and its CoW orders that may still fill can take is set aside."
        SwapQuoting.CowOrdersUnreadable -> ORDERS_UNREADABLE
        is SwapQuoting.Infeasible -> "This swap cannot succeed: ${quoting.detail}"
        is SwapQuoting.Reverts -> {
            val at = quoting.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty()
            "The swap reverts, or returns less than its minimum, run as a verified call$at. Nothing was signed."
        }
        SwapQuoting.NotAToken -> NOT_A_TOKEN
        SwapQuoting.Refused -> "The light client on this device cannot quote this swap."
        SwapQuoting.Syncing -> "The light client is not synced yet: try again in a moment."
        is SwapQuoting.Unavailable -> unavailableText(quoting.reason, quoting.figure)
    }

    /** [swapRefusal] in simple mode's words, with no block (docs/simple-ui.md); null where it names none. */
    private fun simpleSwapRefusal(quoting: SwapQuoting): String? = when (quoting) {
        is SwapQuoting.Reverts ->
            "The swap reverts, or returns less than its minimum, as checked on chain. Nothing was signed."
        else -> null
    }

    /** [approvalRefusal] in simple mode's words, with no block (docs/simple-ui.md); null where it names none. */
    private fun simpleApprovalRefusal(quoting: ApprovalQuoting, sell: AssetOption): String? = when (quoting) {
        is ApprovalQuoting.Reverts -> "${sell.symbol}'s contract refuses the approval, as checked on chain. Nothing " +
            "was signed."
        else -> null
    }

    /** Why CoW's quote of [request] came to [quoting], not an order, in its own words (docs/swaps.md). */
    private fun cowRefusal(quoting: CowSwapQuoting, request: Request): String = when (quoting) {
        is CowSwapQuoting.Quoted, is CowSwapQuoting.NeedsApproval, is CowSwapQuoting.ApprovalPending,
        is CowSwapQuoting.EarlierMaybeSent,
        -> error("not a refusal said as text")
        CowSwapQuoting.NothingToSwap -> "There is nothing to swap: enter an amount above zero."
        CowSwapQuoting.NotASwap -> notASwapText(request.sell.symbol, request.buy.symbol)
        is CowSwapQuoting.NotOffered -> when (quoting.why) {
            CowNotOffered.NOT_ON_THIS_DEVICE -> NOT_ON_THIS_DEVICE
            CowNotOffered.SELLS_ETHER -> SELLS_ETHER
            CowNotOffered.NO_ROUTE -> noRouteText(request.sell.symbol, request.buy.symbol) +
                " Without a verified Uniswap quote no floor stands for a CoW order, so CoW is not offered either."
            CowNotOffered.FEE_ABOVE_PROCEEDS -> "Uniswap's network fee for this swap is more than it returns, so no " +
                "verified floor stands for a CoW order: swap more."
            CowNotOffered.NOT_FOLLOWED_YET -> "This account's history is followed from a block that is not " +
                "finalized yet, so a CoW order's end could not be proven. CoW is offered once it is, in about a " +
                "quarter of an hour."
        }
        is CowSwapQuoting.NoFloorVenue -> noVenueText(quoting.contract, network) +
            " Without it no verified floor stands for a CoW order."
        CowSwapQuoting.HistoryUnreadable ->
            "Where this account's history starts cannot be read, so a CoW order's end could not be proven. Try again."
        is CowSwapQuoting.NoVenue -> when (val contract = quoting.contract) {
            null -> "No CoW Protocol deployment is pinned for ${network.displayName}, so CoW is not offered."
            else -> "CoW's contract at ${Eip55.format(contract)} is not what this wallet pins, so CoW is not offered."
        }
        is CowSwapQuoting.EarlierOrderLive -> earlierOrderText(request)
        CowSwapQuoting.OrdersUnreadable -> ORDERS_UNREADABLE
        CowSwapQuoting.TooSmall -> "The order's minimum rounds down to nothing: an order that may buy nothing is not " +
            "signed. Swap more."
        CowSwapQuoting.InsufficientToken -> "This account holds less ${request.sell.symbol} than that, once what " +
            "its transactions not mined yet can take is set aside."
        CowSwapQuoting.NotAToken -> NOT_A_TOKEN
        CowSwapQuoting.Refused -> "The light client on this device cannot quote this swap."
        CowSwapQuoting.Syncing -> "The light client is not synced yet: try again in a moment."
        is CowSwapQuoting.Unavailable -> unavailableText(quoting.reason, quoting.figure)
        is CowSwapQuoting.CowRefused -> when (quoting.why) {
            CowRefusal.NO_LIQUIDITY -> "CoW found no liquidity for this swap"
            CowRefusal.UNSUPPORTED_TOKEN ->
                "CoW does not trade ${request.sell.symbol} or ${request.buy.symbol}, or not right now"
            CowRefusal.TOO_SMALL -> "The amount does not cover CoW's fee"
            CowRefusal.FORBIDDEN -> "CoW does not serve this account"
            CowRefusal.FUNDS -> "By CoW's own check, the balance or the allowance does not cover the order"
            CowRefusal.OTHER -> "CoW refused to quote this swap"
        } + " (CoW's word: ${quoting.errorType})."
        is CowSwapQuoting.CowNoAnswer -> when (quoting.why) {
            CowUnavailable.UNDECODABLE ->
                "CoW gave an answer this version does not understand: an update of Serotine brings CoW back."
            CowUnavailable.NOT_PINNED -> "No CoW API is pinned for ${network.displayName}."
            CowUnavailable.TIMEOUT, CowUnavailable.NO_CONNECTION, CowUnavailable.SERVER_ERROR -> "CoW did not answer."
        }
    }

    private fun earlierOrderText(request: Request) =
        "An earlier CoW order selling ${request.sell.symbol} may still fill: one order of a token at a time, since " +
            "both would draw on one allowance. It is done once it fills or can no longer fill."

    private fun approvalRefusal(quoting: ApprovalQuoting, sell: AssetOption): String = when (quoting) {
        is ApprovalQuoting.Quoted, is ApprovalQuoting.AlreadyAllowed, is ApprovalQuoting.ApprovalPending,
        is ApprovalQuoting.EarlierSwapPending, is ApprovalQuoting.EarlierMaybeSent,
        -> error("not a refusal said as text")
        ApprovalQuoting.InsufficientFunds ->
            "Not enough ${network.nativeSymbol} for the most the approval's fee can be."
        is ApprovalQuoting.Infeasible -> "The approval cannot succeed: ${quoting.detail}"
        is ApprovalQuoting.Reverts -> "${sell.symbol}'s contract refuses the approval, run as a verified call" +
            (quoting.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty()) + ". Nothing was signed."
        ApprovalQuoting.NotAToken -> "${sell.symbol}'s contract does not answer as a token."
        ApprovalQuoting.Refused -> "The light client on this device cannot quote the approval."
        ApprovalQuoting.Syncing -> "The light client is not synced yet: try again in a moment."
        is ApprovalQuoting.Unavailable -> unavailableText(quoting.reason, quoting.figure)
    }

    private fun unavailableText(reason: ReadUnavailable, figure: QuoteFigure?) =
        when (reason) {
            ReadUnavailable.NO_ENGINE -> "There is no light client on this device, so nothing can be swapped."
            ReadUnavailable.NETWORK_OFF -> "The network's light client is not running, so nothing can be swapped."
            ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so nothing can be quoted."
            ReadUnavailable.SYNCING -> "The light client is still syncing, so nothing can be quoted yet."
            ReadUnavailable.UNPROVEN -> "${figureText(figure, "of the quote")} could not be verified this time: " +
                "try again."
            ReadUnavailable.REFUSED -> "The light client on this device cannot quote this swap."
        }

    private fun notRunText(reason: ReadUnavailable): String = when (reason) {
        ReadUnavailable.UNPROVEN -> "The swap could not be run again before signing: nothing was signed. Try again."
        else -> "The swap cannot be run again on this device right now: nothing was signed."
    }

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

    private suspend fun biometricsOffered(): Boolean = try {
        biometricsOn(describeKeyStore())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false // the use case promises not to throw; the backstop: the password still works
    }

    private companion object {
        /** How often the swap is quoted again while its approval waits for a block. */
        val AWAIT_POLL = 4.seconds

        /** For how long the screen waits for the approval's block before it goes back to the compose step. */
        val AWAIT_MAX = 15.minutes

        /** How many quotes in a row may fail while the approval waits before the compose step says why. */
        const val AWAIT_FAILURES = 3

        /** How many steps a swap has whose allowance is set to zero first: zero, the whole amount, the swap. */
        const val RESET_STEPS = 3

        /** Where the next step waits once the screen is left (docs/simple-ui.md, H5). */
        const val LATER = "If you leave, it waits in this account's Activity: Continue there quotes it again."

        /** "Step 2 of 2": which step of a swap a review is (docs/simple-ui.md, W2 and W3). */
        fun stepText(position: Pair<Int, Int>) = "Step ${position.first} of ${position.second}"

        const val APPROVAL_PENDING =
            "An approval of this token is on its way and not in a block yet: once a block has it, Review goes on."
        const val ALLOWANCE_MOVED =
            "The router's allowance changed while the swap was quoted: nothing was signed. Review again."
        const val RELAYER_ALLOWANCE_MOVED = "CoW's vault relayer's allowance changed while the order was quoted: " +
            "nothing was signed. Review again."
        const val NOT_ON_THIS_DEVICE = "CoW Protocol is not offered on this device: its light client cannot date " +
            "a finalized block yet, which proving an order's end needs. Swap on Uniswap."
        const val NO_LONGER_MET =
            "Run again just before signing, the swap no longer returned its minimum: it was quoted again. Check it."
        const val EARLIER_MAYBE_SENT = "An earlier transaction from this account may still be on its way: no peer " +
            "answered when it was broadcast, and no block has it yet. Try it again from its status rather than " +
            "sign a new one, which could pay twice."
        const val SWAP_FAILED =
            "Serotine could not finish this swap. Check the account's balances before you swap again."
        const val ORDER_FAILED = "Serotine could not finish this order. If it reached CoW, it is held back until it " +
            "fills or can no longer fill: check the account's balances before you swap again."
        const val FLOOR_ROSE = "The verified Uniswap net rose above this order's floor since it was quoted: it was " +
            "quoted again. Check it."
        const val ORDERS_UNREADABLE = "The record of this device's CoW orders cannot be read: one may still fill, " +
            "so no token is swapped until it can be read."
        const val SELLS_ETHER = "CoW Protocol is offered for tokens sold: selling ETH would go through CoW's " +
            "ETH-flow contract, which this wallet does not use yet. Sell WETH on CoW, or swap ETH on Uniswap."
        const val VAULT_RELAYER_NAME = "CoW's vault relayer"
        const val SETTLEMENT_NAME = "CoW's settlement contract"
        const val APPROVAL_FAILED = "Serotine could not finish this approval. Try again."
        const val LIST_UNAVAILABLE =
            "Your account list cannot be read right now, so nothing can be swapped. Serotine keeps trying."
        const val NOT_IN_WALLET = "This account is not in your wallet any more."
        const val SAFE = "A Safe's swap is proposed as Safe transactions its owners sign: Swap on the Safe's " +
            "own screen proposes one."
        const val WATCH_ONLY =
            "This account is watch-only: its key is not on this device, so nothing can be swapped from it."
    }
}
