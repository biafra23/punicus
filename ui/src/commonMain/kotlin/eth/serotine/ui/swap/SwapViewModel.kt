package eth.serotine.ui.swap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
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
import eth.serotine.domain.model.WrapDirection
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
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveSwapVenue
import eth.serotine.domain.usecase.PlaceCowOrder
import eth.serotine.domain.usecase.QuoteCowSwap
import eth.serotine.domain.usecase.QuoteFigure
import eth.serotine.domain.usecase.QuoteSwap
import eth.serotine.domain.usecase.QuoteTokenApproval
import eth.serotine.domain.usecase.QuoteWrap
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.SWAP_DEADLINE_SECONDS
import eth.serotine.domain.usecase.SendSwap
import eth.serotine.domain.usecase.SendTokenApproval
import eth.serotine.domain.usecase.SendWrap
import eth.serotine.domain.usecase.SwapQuote
import eth.serotine.domain.usecase.SwapQuoting
import eth.serotine.domain.usecase.SwapSending
import eth.serotine.domain.usecase.TokenApprovalQuote
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.domain.usecase.WarmFeeHistory
import eth.serotine.domain.usecase.WrapQuote
import eth.serotine.domain.usecase.WrapQuoting
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
import eth.serotine.ui.components.TOLERANCE_ERROR
import eth.serotine.ui.components.TOO_SMALL
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.amountHint
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.exactAmount
import eth.serotine.ui.components.feeDetail
import eth.serotine.ui.components.figureText
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.noRouteText
import eth.serotine.ui.components.noVenueText
import eth.serotine.ui.components.notSentItem
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.priceHintText
import eth.serotine.ui.components.priceImpactText
import eth.serotine.ui.components.promptDeadline
import eth.serotine.ui.components.routeSteps
import eth.serotine.ui.components.sellNote
import eth.serotine.ui.components.swapOptions
import eth.serotine.ui.components.toleranceError
import eth.serotine.ui.components.typedAmount
import eth.serotine.ui.components.verifiedAt
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
 * - **ETH for WETH, or back,** is no swap but a wrap (docs/swaps.md, *Wrapping ETH*), on neither
 *   venue: WETH9's own `deposit` or `withdraw`, one for one, quoted ([QuoteWrap]) and signed and
 *   broadcast ([SendWrap]). It is asked for first, for every pair of ETH and a token, so that
 *   neither venue is (CoW never learns of a wrap).
 * - A swap whose router, or CoW's vault relayer, may not take the amount yet first gets its approval
 *   ([QuoteTokenApproval], [SendTokenApproval]), its own transaction: once it is sent, the compose
 *   step says so, and Review quotes the swap once a block has it. One signed but not broadcast is
 *   retried with its own bytes ([RebroadcastTransfer]), never signed anew.
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
    private val from: Address,
    sell: Address?,
    observeAccounts: ObserveAccounts,
    observeAccountValue: ObserveAccountValue,
    private val describeKeyStore: DescribeKeyStore,
    private val quoteSwap: QuoteSwap,
    private val sendSwap: SendSwap,
    private val quoteWrap: QuoteWrap,
    private val sendWrap: SendWrap,
    private val quoteTokenApproval: QuoteTokenApproval,
    private val sendTokenApproval: SendTokenApproval,
    private val rebroadcastTransfer: RebroadcastTransfer,
    private val warmFeeHistory: WarmFeeHistory,
    observeSwapVenue: ObserveSwapVenue,
    private val chooseSwapVenue: ChooseSwapVenue,
    cowOfferedOnThisDevice: CowOfferedOnThisDevice,
    private val quoteCowSwap: QuoteCowSwap,
    private val placeCowOrder: PlaceCowOrder,
    private val network: Network,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val formatTime: (Long) -> String = ::formatDateTime,
    fill: SwapFill? = null,
) : ViewModel() {

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

    /**
     * A swap the compose step can have quoted, exactly as it has it, on [venue]. [tolerance] is null
     * only for a pair of ETH and a token, which may be a wrap, where it does not apply: a swap of
     * it is refused until one is typed.
     */
    private data class Request(
        val inputs: Inputs,
        val sell: AssetOption,
        val buy: AssetOption,
        val amount: UInt256,
        val tolerance: SlippageTolerance?,
        val venue: SwapVenue,
    ) {
        /** ETH against a token: a wrap, when the token is the pinned WETH9. */
        val mayWrap: Boolean
            get() = (sell.asset == TransferAsset.Native) != (buy.asset == TransferAsset.Native)
    }

    /** What a quote comes to: something to review ([Quoted]), or the compose step saying why not. */
    private sealed interface Next

    /** What a review signs: the approval first, the swap on Uniswap, the wrap, or the CoW order. */
    private sealed interface Quoted : Next {
        data class Approval(val quote: TokenApprovalQuote) : Quoted

        data class Swap(val quote: SwapQuote) : Quoted

        data class Wrap(val quote: WrapQuote) : Quoted

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
         */
        data class Composing(
            val quoting: Boolean = false,
            val error: String? = null,
            val info: String? = null,
            val earlier: EarlierItem? = null,
            val uniswapInstead: Boolean = false,
            val liveOrder: String? = null,
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
        ) : Step {
            val idle: Boolean
                get() = !refreshing && !signing && notSent == null
        }

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

    /** Whether the screen is shown now ([onShown], [onHidden]). */
    private var shown = false

    /** Whether the screen was hidden while the review's first quote was being made. */
    private var hiddenWhileQuoting = false

    private val model: StateFlow<Model?> =
        combine(observeAccounts(), observeAccountValue(from), inputs, observeSwapVenue()) { list, value, inputs, last ->
            modelOf(list, value, inputs, last)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            null,
        )

    val uiState: StateFlow<SwapUiState> = combine(model, step) { model, step -> render(model, step) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        SwapUiState.Loading(network.displayName),
    )

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
            step.value = when {
                // Made while hidden: it is from before the screen came back, so it is made again.
                next is Step.Reviewing -> next.copy(hidden = hiddenWhileQuoting)
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
                    // user has not read yet.
                    next is Step.Reviewing -> latest.copy(
                        request = next.request,
                        quoted = next.quoted,
                        quotedAt = next.quotedAt,
                        refreshing = false,
                        unlock = if (next.quoted::class == latest.quoted::class) {
                            latest.unlock.copy(error = note)
                        } else {
                            latest.unlock.copy(password = WalletPassword.EMPTY, error = note)
                        },
                    )
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
            is Quoted.Wrap ->
                "${verbOf(quoted.quote.direction)} ${sold(quoted.quote.amount)} into ${request.buy.symbol}"
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
        }
    }

    /** The screen stopped being shown: the password goes, and the review is quoted again when it is shown. */
    fun onHidden() {
        shown = false
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
                is Quoted.Wrap -> safely { sendWrap(quoted.quote, unlock) }
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
                step.update { latest -> if (latest is Step.Reviewing) Step.Placed(placing.record) else latest }
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
     * or a wrap broadcast is sent; an approval broadcast is back at the compose step, which says so and
     * quotes the swap once a block has it; the rest stays in the review, saying why.
     */
    private fun after(review: Step.Reviewing, sending: TransferSending?): Step {
        val approval = review.quoted is Quoted.Approval
        val failed = when (val quoted = review.quoted) {
            is Quoted.Approval -> APPROVAL_FAILED
            is Quoted.Wrap -> "Serotine could not finish this ${verbOf(quoted.quote.direction).lowercase()}. Check " +
                "the account's balances before you try again."
            is Quoted.Swap, is Quoted.Cow -> SWAP_FAILED
        }
        return when (sending) {
            is TransferSending.Sent -> if (approval) {
                val resets = (review.quoted as Quoted.Approval).quote.resetsFirst
                val then = if (review.request.venue == SwapVenue.COW) "the order" else "the swap"
                Step.Composing(
                    info = if (resets) RESET_SENT else "Your approval is on its way: once a block has it, Review " +
                        "quotes $then.",
                    earlier = EarlierItem(sending.hash, "Open the approval", waiting = true),
                )
            } else {
                Step.Sent(sending.hash)
            }
            is TransferSending.NotSigned -> review.copy(
                signing = false,
                unlock = review.unlock.copy(
                    error = notSignedText(sending.signing, failed),
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
                unlock = review.unlock.copy(error = failed),
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

    /**
     * What [request] comes to: the wrap it is, when it is ETH against the pinned WETH9, whatever the
     * venue; else on its venue, its quote, the approval it needs first, or why neither.
     */
    private suspend fun quoteOf(request: Request): Next {
        // Only a pair of ETH and a token may be a wrap: no other is asked, so no other waits for it.
        if (request.mayWrap) {
            when (val wrapping = askWrap(request)) {
                WrapQuoting.NotAWrap -> Unit
                is WrapQuoting.Quoted -> return Quoted.Wrap(wrapping.quote)
                is WrapQuoting.EarlierMaybeSent -> return Step.Composing(
                    error = EARLIER_MAYBE_SENT,
                    earlier = EarlierItem(wrapping.hash, "Open the earlier transaction", waiting = false),
                )
                else -> return Step.Composing(error = wrapRefusal(wrapping, request))
            }
        }
        // A swap, not a wrap: its tolerance applies, and one not typed right is no swap to quote.
        val tolerance = request.tolerance ?: return Step.Composing(error = TOLERANCE_ERROR)
        return onVenue(request, tolerance)
    }

    /** What [request] comes to on its venue: its quote, the approval it needs first, or why neither. */
    private suspend fun onVenue(request: Request, tolerance: SlippageTolerance): Next = when (request.venue) {
        SwapVenue.UNISWAP -> when (val swapping = askSwap(request, tolerance)) {
            is SwapQuoting.Quoted -> Quoted.Swap(swapping.quote)
            is SwapQuoting.NeedsApproval -> approvalFirst(swapping.token, swapping.spender, request) {
                when (val again = askSwap(request, tolerance)) {
                    is SwapQuoting.Quoted -> Quoted.Swap(again.quote)
                    else -> composing(again, request)
                }
            }
            else -> composing(swapping, request)
        }
        // CoW is asked only here: the user chose it, and asked for the review.
        SwapVenue.COW -> when (val ordering = askCow(request, tolerance)) {
            is CowSwapQuoting.Quoted -> Quoted.Cow(ordering.quote)
            is CowSwapQuoting.NeedsApproval -> approvalFirst(ordering.token, ordering.spender, request) {
                when (val again = askCow(request, tolerance)) {
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
        else -> Step.Composing(error = approvalRefusal(approving, request.sell))
    }

    private suspend fun askSwap(request: Request, tolerance: SlippageTolerance): SwapQuoting = try {
        quoteSwap(from, request.sell.asset, request.buy.asset, request.amount, tolerance, request.inputs.fee)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SwapQuoting.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
    }

    private suspend fun askWrap(request: Request): WrapQuoting = try {
        quoteWrap(from, request.sell.asset, request.buy.asset, request.amount, request.inputs.fee)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        WrapQuoting.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
    }

    private suspend fun askCow(request: Request, tolerance: SlippageTolerance): CowSwapQuoting = try {
        quoteCowSwap(from, request.sell.asset, request.buy.asset, request.amount, tolerance)
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
        else -> Step.Composing(error = swapRefusal(quoting, request))
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
        val before = inputs.value.sell
        inputs.update(edit)
        val waiting = current.earlier?.takeIf { it.waiting && inputs.value.sell == before }
        step.value = Step.Composing(info = waiting?.let { current.info }, earlier = waiting)
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
                val account = list.accounts.firstOrNull { it.address == from }
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
            is Step.Composing -> step
        }
        model ?: return SwapUiState.Loading(network)
        val account = when (val swapper = model.swapper) {
            is Swapper.Cannot -> return SwapUiState.CannotSwap(network, swapper.message)
            is Swapper.From -> swapper.account
        }
        val inputs = model.inputs
        val amount = typedAmount(inputs.amount, model.sell)
        val form = SwapForm(
            network = network,
            from = SendFrom(account.label, display),
            sellOptions = model.sellOptions.map { it.choice() },
            sell = model.sell?.choice(),
            buyOptions = model.buyOptions.map { it.choice() },
            buy = model.buy?.choice(),
            amount = inputs.amount,
            tolerance = inputs.tolerance,
            amountError = amount.error,
            amountHint = amountHint(amount.units, model.sell, model.currency, this.network),
            canMax = model.sell?.balance != null && !composing.quoting,
            toleranceError = toleranceError(inputs.tolerance),
            note = sellNote(model.sell, model.tokensRead, "this account"),
            quoting = composing.quoting,
            error = composing.error,
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

    private fun review(step: Step.Reviewing): SwapUiState = when (val quoted = step.quoted) {
        is Quoted.Approval -> approvalReview(step, quoted.quote)
        is Quoted.Swap -> swapReview(step, quoted.quote)
        is Quoted.Wrap -> wrapReview(step, quoted.quote)
        is Quoted.Cow -> cowReview(step, quoted.quote)
    }

    private fun approvalReview(step: Step.Reviewing, quote: TokenApprovalQuote): SwapUiState.ApprovalReview {
        val sell = step.request.sell
        val native = network.nativeSymbol
        val ran = quote.simulationBlock?.let { "at block ${grouped(it)}" } ?: "at the head"
        return SwapUiState.ApprovalReview(
            network = network.displayName,
            from = step.sender,
            token = sell.choice(),
            amount = exactAmount(quote.amount, sell.decimals, sell.symbol),
            allowance = exactAmount(quote.allowance, sell.decimals, sell.symbol),
            spender = SpenderItem(
                if (step.request.venue == SwapVenue.COW) VAULT_RELAYER_NAME else ROUTER_NAME,
                Eip55.format(quote.spender),
            ),
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
        )
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
     * ETH for WETH or back (docs/swaps.md, *Wrapping ETH*): what it takes and pays, one for one,
     * through the pinned WETH9, and the balances it leaves.
     */
    private fun wrapReview(step: Step.Reviewing, quote: WrapQuote): SwapUiState.WrapReview {
        val sell = step.request.sell
        val buy = step.request.buy
        val native = network.nativeSymbol
        val verb = verbOf(quote.direction)
        val wethOption = if (quote.direction == WrapDirection.WRAP) buy else sell
        val weth = { units: UInt256 -> exactAmount(units, wethOption.decimals, wethOption.symbol) }
        val ether = { units: UInt256 -> exactAmount(units, NATIVE_DECIMALS, native) }
        val ran = quote.simulationBlock?.let { "at block ${grouped(it)}" } ?: "at the head"
        val nativeChange = BalanceChange(
            what = "$native balance" + if (quote.direction == WrapDirection.UNWRAP) ", which pays the fee" else "",
            before = ether(quote.nativeBefore),
            after = "at least " + ether(quote.nativeAfter),
            verifiedAt = verifiedAt(quote.balanceBlock),
        )
        val wethChange = BalanceChange(
            what = "${wethOption.symbol} balance",
            before = weth(quote.wethBefore),
            after = weth(quote.wethAfter),
            verifiedAt = verifiedAt(quote.tokenBalanceBlock),
        )
        val explanation = when (quote.direction) {
            WrapDirection.WRAP -> "Not a swap: WETH9's own deposit takes the $native and credits as much " +
                "${wethOption.symbol}, one for one."
            WrapDirection.UNWRAP -> "Not a swap: WETH9's own withdraw takes the ${wethOption.symbol} and pays as " +
                "much $native, one for one."
        } + " No pool, no price, no slippage and no approval: only the network fee."
        return SwapUiState.WrapReview(
            network = network.displayName,
            title = "Review ${verb.lowercase()}",
            signLabel = "Sign and ${verb.lowercase()}",
            from = step.sender,
            sell = sell.choice(),
            sold = exactAmount(quote.amount, sell.decimals, sell.symbol),
            buy = buy.choice(),
            receive = exactAmount(quote.amount, buy.decimals, buy.symbol),
            explanation = explanation,
            ranAt = "The ${verb.lowercase()}, run as a verified call from this account $ran, goes through.",
            contract = SpenderItem(WETH9_NAME, Eip55.format(quote.weth.address)),
            maxFee = ether(quote.maxFee),
            feeDetail = feeDetail(quote.level, quote.transaction, quote.feeBlock),
            balances = when (quote.direction) {
                WrapDirection.WRAP -> listOf(nativeChange, wethChange)
                WrapDirection.UNWRAP -> listOf(wethChange, nativeChange)
            },
            pending = pendingText(quote.pendingWeth, quote.pendingCost, quote.heldByOrders, wethOption),
            unlock = step.unlock,
            refreshing = step.refreshing,
            signing = step.signing,
            notSent = step.notSent?.let {
                notSentItem(it.broadcast, it.unverified, what = verb.lowercase(), maybeSent = it.transfer.maybeSent)
            },
        )
    }

    /** "Wrap" or "Unwrap". */
    private fun verbOf(direction: WrapDirection) = when (direction) {
        WrapDirection.WRAP -> "Wrap"
        WrapDirection.UNWRAP -> "Unwrap"
    }

    /** Why the wrap of [request] came to [quoting], not a quote, in a sentence. */
    private fun wrapRefusal(quoting: WrapQuoting, request: Request): String {
        val verb = if (request.sell.asset == TransferAsset.Native) "wrap" else "unwrap"
        return when (quoting) {
            is WrapQuoting.Quoted, WrapQuoting.NotAWrap, is WrapQuoting.EarlierMaybeSent ->
                error("not a refusal said as text")
            WrapQuoting.NothingToWrap -> "There is nothing to $verb: enter an amount above zero."
            WrapQuoting.InsufficientFunds -> if (request.sell.asset == TransferAsset.Native) {
                "Not enough ${network.nativeSymbol} for this amount and the most the fee can be: wrap less, to leave " +
                    "room for the fee. Transactions not mined yet count too."
            } else {
                "Not enough ${network.nativeSymbol} for the most the fee can be. Transactions not mined yet count too."
            }
            WrapQuoting.InsufficientToken -> "This account holds less ${request.sell.symbol} than that, once what " +
                "its transactions not mined yet and its CoW orders that may still fill can take is set aside."
            WrapQuoting.CowOrdersUnreadable -> ORDERS_UNREADABLE
            is WrapQuoting.Infeasible -> "This $verb cannot succeed: ${quoting.detail}"
            is WrapQuoting.Reverts -> {
                val at = quoting.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty()
                "The $verb reverts, run as a verified call$at. Nothing was signed."
            }
            WrapQuoting.NotAToken -> NOT_A_TOKEN
            WrapQuoting.Refused -> "The light client on this device cannot quote this $verb."
            WrapQuoting.Syncing -> "The light client is not synced yet: try again in a moment."
            is WrapQuoting.Unavailable -> unavailableText(quoting.reason, quoting.figure)
        }
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
    private fun pendingText(quote: SwapQuote, sell: AssetOption): String? =
        pendingText(quote.pendingSell, quote.pendingCost, quote.heldByOrders, sell)

    /**
     * What earlier transactions not mined yet may still take ([pendingToken] of [token], [pendingCost]
     * of the network's currency) and what CoW orders hold back of [token] ([held]), when anything.
     */
    private fun pendingText(
        pendingToken: UInt256?,
        pendingCost: UInt256,
        held: UInt256?,
        token: AssetOption,
    ): String? {
        val native = network.nativeSymbol
        val parts = listOfNotNull(
            pendingToken?.takeUnless { it.isZero }?.let { exactAmount(it, token.decimals, token.symbol) },
            pendingCost.takeUnless { it.isZero }?.let { "up to " + exactAmount(it, NATIVE_DECIMALS, native) },
        )
        val held = held?.takeUnless { it.isZero }?.let { exactAmount(it, token.decimals, token.symbol) }
        val sentences = listOfNotNull(
            parts.takeIf { it.isNotEmpty() }?.let {
                "Earlier transactions from this account, not mined yet, may still take ${it.joinToString(" and ")}."
            },
            held?.let { "This account's CoW orders that may still fill hold back $it." },
        )
        if (sentences.isEmpty()) return null
        return sentences.joinToString(" ") + " The balance after counts it."
    }

    /**
     * The compose step's swap, when it is complete: what a quote is asked for. A pair of ETH and a
     * token may be a wrap, which no tolerance applies to: it is complete without one.
     */
    private fun requestOf(model: Model): Request? {
        if (model.swapper !is Swapper.From) return null
        val sell = model.sell ?: return null
        val buy = model.buy ?: return null
        val amount = typedAmount(model.inputs.amount, sell).units ?: return null
        val tolerance = SlippageTolerance.parsePercent(model.inputs.tolerance.trim())
        return Request(model.inputs, sell, buy, amount, tolerance, model.venue)
            .takeIf { tolerance != null || it.mayWrap }
    }

    private fun swapRefusal(quoting: SwapQuoting, request: Request): String = when (quoting) {
        is SwapQuoting.Quoted, is SwapQuoting.NeedsApproval, is SwapQuoting.ApprovalPending,
        is SwapQuoting.EarlierSwapPending, is SwapQuoting.EarlierMaybeSent,
        -> error("not a refusal said as text")
        SwapQuoting.NothingToSwap -> "There is nothing to swap: enter an amount above zero."
        SwapQuoting.NotASwap -> sameTokenText(request.sell.symbol, request.buy.symbol)
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

    /** Why CoW's quote of [request] came to [quoting], not an order, in its own words (docs/swaps.md). */
    private fun cowRefusal(quoting: CowSwapQuoting, request: Request): String = when (quoting) {
        is CowSwapQuoting.Quoted, is CowSwapQuoting.NeedsApproval, is CowSwapQuoting.ApprovalPending,
        is CowSwapQuoting.EarlierMaybeSent,
        -> error("not a refusal said as text")
        CowSwapQuoting.NothingToSwap -> "There is nothing to swap: enter an amount above zero."
        CowSwapQuoting.NotASwap -> sameTokenText(request.sell.symbol, request.buy.symbol)
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

    /** [sell] and [buy], one token: no swap and no wrap. */
    private fun sameTokenText(sell: String, buy: String) = "$sell and $buy are one token: there is nothing to swap."

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
            ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so nothing can be quoted."
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
                warmFeeHistory()
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
        const val RESET_SENT = "Your approval of zero is on its way: once a block has it, Review asks for the " +
            "approval of the whole amount."
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
        const val WETH9_NAME = "WETH9, the wrapped ether contract"
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
