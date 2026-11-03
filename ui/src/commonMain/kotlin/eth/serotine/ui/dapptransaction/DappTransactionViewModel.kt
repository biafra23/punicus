package eth.serotine.ui.dapptransaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.DappCallMeaning
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.DappAnswer
import eth.serotine.domain.repository.DappRequestError
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.DappMessageReview
import eth.serotine.domain.usecase.DappSignContent
import eth.serotine.domain.usecase.DappSignRefusal
import eth.serotine.domain.usecase.DappTransactionQuote
import eth.serotine.domain.usecase.DappTransactionQuoting
import eth.serotine.domain.usecase.DappTransactionSending
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveDappMessages
import eth.serotine.domain.usecase.QuoteDappTransaction
import eth.serotine.domain.usecase.RefuseDappRequest
import eth.serotine.domain.usecase.SendDappTransaction
import eth.serotine.domain.usecase.SignedAnswer
import eth.serotine.domain.usecase.SignedState
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.PROMPT_TIMED_OUT
import eth.serotine.ui.components.QUOTE_MAX_AGE
import eth.serotine.ui.components.STALE_QUOTE
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.callRefusalText
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.components.notSentItem
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.promptDeadline
import eth.serotine.ui.dapprequest.DappRequestViewModel
import eth.serotine.ui.dapprequest.SignInField
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
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What a tap on a greyed-out item says. */
data class TransactionExplanation(val title: String, val reason: String)

/**
 * A dApp's transaction (docs/walletconnect.md, screen D6) as display-ready values: who asks (the
 * dApp's claim), the account it is sent from, on Sepolia, the recipient in full, the value, and the
 * call: decoded for the calls this app knows, else its data raw and marked not decoded. Then the quote,
 * worked out here from verified state: the fee at most, the balance left at least, and the call run as
 * a verified call at the head; or why there is none. The [warnings], the [refusal] when it can only be
 * refused, and the unlock. [gone] once the request is no longer waiting; [done] once answered.
 */
data class DappTransactionUiState(
    val loading: Boolean = true,
    val gone: Boolean = false,
    /** No longer waiting, and [notice] says why: Back leaves. */
    val ended: Boolean = false,
    val name: String = "",
    val url: String = "",
    val accountName: String? = null,
    val accountAddress: String = "",
    /** The recipient, EIP-55 checksummed, in full. */
    val to: String = "",
    /** The value, in the network's currency, testnet marked. */
    val value: String = "",
    /** What the call does, in a sentence; null when it is not decoded. */
    val meaning: String? = null,
    val meaningFields: List<SignInField> = emptyList(),
    /** The call data as hex, when there is some, with its size. */
    val dataHex: String? = null,
    val dataSize: String = "",
    val warnings: List<String> = emptyList(),
    val refusal: String? = null,
    /** Being quoted. */
    val quoting: Boolean = false,
    /** The quote's lines (the fee at most, the balance after, the verified run), when quoted. */
    val quote: List<SignInField> = emptyList(),
    /** Why it is not quoted, when it is not: Try again quotes anew. */
    val notQuoted: String? = null,
    val waits: String = "",
    val unlock: UnlockStep = UnlockStep(),
    val busy: Boolean = false,
    val notSent: NotSentItem? = null,
    /** Refuse is greyed out, still tappable: a transaction signed for it may be on its way already. */
    val refuseUnavailable: Boolean = false,
    val notice: String? = null,
    val explaining: TransactionExplanation? = null,
    val done: Boolean = false,
) {
    /** Send is greyed out, still tappable, while it cannot be done (owner ruling, 2026-10-18, #55). */
    val sendUnavailable: Boolean get() = refusal != null || quote.isEmpty()
}

/**
 * Screen D6's ViewModel: quotes the dApp's transaction from verified state, and signs and sends it with
 * the wallet password or a fingerprint or face, or refuses it. A quote older than a minute, or one made
 * before the screen was last hidden, is made again before anything is signed. The password typed is held
 * while the field is shown, handed to the signer in an [Unlock], and dropped once used and whenever the
 * screen is not shown (CLAUDE.md, *Trust*).
 */
class DappTransactionViewModel(
    /** The request, as its route names it: `topic/id`. */
    requestKey: String,
    observeDappMessages: ObserveDappMessages,
    private val quoteDappTransaction: QuoteDappTransaction,
    private val sendDappTransaction: SendDappTransaction,
    private val refuseDappRequest: RefuseDappRequest,
    private val describeKeyStore: DescribeKeyStore,
    private val clock: () -> Long = ::epochSecondsNow,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** When how long the dApp waits is said again: every 15 seconds while the screen is open. */
    ticks: Flow<Unit> = DappRequestViewModel.every15Seconds(),
) : ViewModel() {

    private val id: DappRequestId? = requestKey.split('/').takeIf { it.size == 2 }?.let { (topic, number) ->
        number.toLongOrNull()?.let { DappRequestId(DappSessionId(topic), it) }
    }

    private data class Local(
        val quote: DappTransactionQuote? = null,
        val quotedAt: TimeMark? = null,
        val quoting: Boolean = false,
        val notQuoted: String? = null,
        val hidden: Boolean = false,
        val unlock: UnlockStep = UnlockStep(),
        val busy: Boolean = false,
        val notSent: Pair<NotSentItem, SentTransfer>? = null,
        /** The dApp asks for a gas limit below the verified estimate: refused as invalid, not declined. */
        val gasTooLow: Boolean = false,
        val notice: String? = null,
        val explaining: TransactionExplanation? = null,
        val done: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private var job: Job? = null

    private val shownReview = observeDappMessages()
        .map { reviews ->
            reviews.find { it.request.id == id && it.content is DappSignContent.Transaction }
                ?.let { review -> review to shown(review) }
        }
        .distinctUntilChanged()

    val uiState: StateFlow<DappTransactionUiState> = combine(shownReview, local, ticks) { shown, local, _ ->
        val (review, base) = shown
            ?: return@combine if (local.notice != null && !local.done) {
                DappTransactionUiState(loading = false, ended = true, notice = local.notice)
            } else {
                DappTransactionUiState(loading = false, gone = true, done = local.done)
            }
        base.copy(
            quoting = local.quoting,
            quote = local.quote?.let(::quoteLines).orEmpty(),
            notQuoted = local.notQuoted,
            waits = DappRequestViewModel.waitsLine(review.request.expiresAtSeconds - clock()),
            unlock = local.unlock,
            busy = local.busy,
            notSent = local.notSent?.first,
            refuseUnavailable = local.notSent?.second?.maybeSent == true,
            notice = local.notice,
            explaining = local.explaining,
            done = local.done,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DappTransactionUiState())

    init {
        viewModelScope.launch {
            val offered = try {
                biometricsOn(describeKeyStore())
            } catch (_: Exception) {
                false // the use case promises not to throw; the backstop: the password still works
            }
            local.update { it.copy(unlock = it.unlock.copy(biometrics = offered)) }
        }
        requote(note = null)
    }

    /** Quotes the transaction anew: on opening, on Try again, and before signing a quote too old. */
    fun requote(note: String? = null) {
        val request = id ?: return
        if (local.value.busy || local.value.quoting) return
        local.update {
            it.copy(quoting = true, notQuoted = null, gasTooLow = false, unlock = it.unlock.copy(error = note))
        }
        job = viewModelScope.launch {
            val quoting = try {
                quoteDappTransaction(request)
            } catch (_: Exception) {
                null // the use case promises not to throw
            }
            val mark = timeSource.markNow()
            local.update { current ->
                when (quoting) {
                    is DappTransactionQuoting.Quoted ->
                        current.copy(quoting = false, quote = quoting.quote, quotedAt = mark, hidden = false)
                    is DappTransactionQuoting.NotQuoted ->
                        current.copy(
                            quoting = false,
                            quote = null,
                            notQuoted = callRefusalText(quoting.refusal, Network.SEPOLIA),
                        )
                    is DappTransactionQuoting.GasTooLow -> current.copy(
                        quoting = false,
                        quote = null,
                        gasTooLow = true,
                        notQuoted = "The dApp asks for a gas limit of ${quoting.asked}, below the " +
                            "${quoting.estimate} " +
                            "the verified estimate needs: it would run out of gas. Ask the dApp again, or refuse.",
                    )
                    // The review says why: a refused request shows its refusal, and only Refuse.
                    is DappTransactionQuoting.Refused -> current.copy(quoting = false, quote = null)
                    DappTransactionQuoting.Expired -> current.copy(quoting = false, quote = null, notice = EXPIRED)
                    DappTransactionQuoting.Gone -> current.copy(quoting = false, quote = null)
                    is DappTransactionQuoting.AlreadySigned ->
                        signedEarlier(current.copy(quoting = false, quote = null), quoting.answer)
                    null -> current.copy(quoting = false, quote = null, notQuoted = QUOTE_FAILED)
                }
            }
        }
    }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = local.update {
        if (it.busy) it else it.copy(unlock = it.unlock.copy(password = WalletPassword(text), error = null))
    }

    /** Signs with the typed wallet password and sends. */
    fun send() {
        if (explainUnavailable()) return
        val password = local.value.unlock.password
        if (local.value.busy || password.text.isEmpty()) return
        val quote = freshQuote() ?: return
        send(quote, Unlock.WithPassword(password))
    }

    /** Signs with a fingerprint or face and sends. */
    fun sendWithBiometrics() {
        if (explainUnavailable()) return
        if (local.value.busy || !local.value.unlock.biometrics) return
        val quote = freshQuote() ?: return
        val notAfter = local.value.quotedAt?.let(::promptDeadline) ?: return requote(note = STALE_QUOTE)
        // What is signed, said in the prompt: nothing the dApp states.
        val reason = "Send a dApp's transaction from ${uiState.value.accountAddress} to ${uiState.value.to}"
        send(quote, Unlock.WithBiometrics(reason, notAfter))
    }

    /** Sends the transaction signed but not sent again, byte for byte. */
    fun retry() {
        val request = id ?: return
        val (_, transfer) = local.value.notSent ?: return
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        job = viewModelScope.launch {
            val sending = try {
                withContext(NonCancellable) { sendDappTransaction.retry(request, transfer) }
            } catch (_: Exception) {
                null // the use case promises not to throw; the backstop: the retry stays offered
            }
            local.update { if (sending == null) it.copy(busy = false) else after(it, sending) }
        }
    }

    /** Refuses the request: as declined, or with its refusal's error when it cannot be sent. */
    fun refuse() {
        val request = id ?: return
        if (local.value.busy) return
        if (local.value.notSent?.second?.maybeSent == true) {
            local.update { it.copy(explaining = TransactionExplanation("Cannot be refused now", MAYBE_SENT)) }
            return
        }
        val why = if (local.value.gasTooLow) DappRequestError.INVALID_PARAMS else null
        local.update { it.copy(busy = true, notice = null, unlock = it.unlock.copy(password = WalletPassword.EMPTY)) }
        viewModelScope.launch {
            val answer = try {
                withContext(NonCancellable) { refuseDappRequest(request, why) }
            } catch (_: Exception) {
                DappAnswer.NOT_SAVED
            }
            val answered = answer == DappAnswer.DELIVERED || answer == DappAnswer.NOT_DELIVERED
            local.update { it.copy(busy = false, done = answered, notice = if (answered) null else answerLine(answer)) }
        }
    }

    fun dismissExplanation() = local.update { it.copy(explaining = null) }

    /** The screen stopped being shown: the password goes, and the quote is made again when it is shown. */
    fun onHidden() = local.update { it.copy(unlock = it.unlock.copy(password = WalletPassword.EMPTY), hidden = true) }

    /** The screen is shown again: a quote made before it was hidden is made again. */
    fun onShown() {
        if (local.value.hidden && local.value.quote != null && !local.value.busy) requote(note = null)
    }

    override fun onCleared() {
        onHidden()
    }

    /** The quote to sign, or null when it is made again first (too old, or made before the screen was hidden). */
    private fun freshQuote(): DappTransactionQuote? {
        val current = local.value
        val quote = current.quote ?: return null
        val age = current.quotedAt?.elapsedNow()
        if (current.hidden || age == null || age > QUOTE_MAX_AGE) {
            requote(note = STALE_QUOTE)
            return null
        }
        return quote
    }

    private fun explainUnavailable(): Boolean {
        val state = uiState.value
        val reason = state.refusal ?: if (state.quote.isEmpty()) state.notQuoted ?: NOT_QUOTED_YET else return false
        local.update { it.copy(explaining = TransactionExplanation("Cannot be sent", reason)) }
        return true
    }

    private fun send(quote: DappTransactionQuote, unlock: Unlock) {
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        local.update {
            it.copy(busy = true, notice = null, unlock = it.unlock.copy(password = WalletPassword.EMPTY, error = null))
        }
        job = viewModelScope.launch {
            val sending = try {
                withContext(NonCancellable) { sendDappTransaction(quote, unlock) }
            } catch (_: Exception) {
                null
            }
            local.update { after(it, sending) }
            if (((sending as? DappTransactionSending.NotSent)?.sending as? TransferSending.NotSigned)?.signing ==
                Signing.TimedOut
            ) {
                requote(note = PROMPT_TIMED_OUT)
            }
        }
    }

    /** Where [local] goes when [answer] was signed already as this request's answer: never signed again. */
    private fun signedEarlier(local: Local, answer: SignedAnswer): Local = when (answer.state) {
        SignedState.ON_ITS_WAY -> {
            val item = NotSentItem(
                "Signed earlier, but it may not have reached the network. Try again sends the same signed " +
                    "transaction, never a new one.",
                canRetry = true,
            )
            local.copy(busy = false, notSent = item to answer.transfer)
        }
        SignedState.SENT, SignedState.MINED ->
            local.copy(
                busy = false,
                notSent = null,
                notice = "Sent already as ${answer.transfer.hash}: the dApp is being told.",
            )
        SignedState.DROPPED -> local.copy(
            busy = false,
            notSent = null,
            notice = "Signed earlier as ${answer.transfer.hash}, but another transaction of this account took its " +
                "nonce, so it can never be mined. Refuse answers the dApp; ask it again for a new one.",
        )
    }

    private fun after(local: Local, sending: DappTransactionSending?): Local = when (sending) {
        is DappTransactionSending.Sent -> when (sending.answer) {
            DappAnswer.DELIVERED, DappAnswer.NOT_DELIVERED -> local.copy(busy = false, notSent = null, done = true)
            // Sent all the same: said, never closed in silence; the record answers it once it can.
            else -> local.copy(
                busy = false,
                notSent = null,
                notice = "Sent as ${sending.hash}, but the dApp could not be told: " +
                    (answerLine(sending.answer) ?: "") + " It is in Activity.",
            )
        }
        is DappTransactionSending.Refused -> local.copy(
            busy = false,
            explaining = TransactionExplanation("Cannot be sent", refusalLine(sending.refusal)),
        )
        is DappTransactionSending.AlreadySigned -> signedEarlier(local, sending.answer)
        is DappTransactionSending.NotSent -> when (val inner = sending.sending) {
            is TransferSending.NotSigned -> local.copy(
                busy = false,
                unlock = local.unlock.copy(
                    error = notSignedText(inner.signing, SEND_FAILED),
                    biometrics = local.unlock.biometrics && biometricsStillOffered(inner.signing),
                ),
            )
            is TransferSending.NotSent ->
                local.copy(busy = false, notSent = notSentItem(inner.broadcast, false, "transaction") to inner.transfer)
            is TransferSending.RejectionUnverified ->
                local.copy(busy = false, notSent = notSentItem(inner.rejection, true, "transaction") to inner.transfer)
            // Sent is answered as DappTransactionSending.Sent.
            is TransferSending.Sent -> local.copy(busy = false, done = true)
        }
        DappTransactionSending.Expired -> local.copy(busy = false, notice = EXPIRED)
        DappTransactionSending.Gone -> local.copy(busy = false, notice = GONE)
        null -> local.copy(busy = false, unlock = local.unlock.copy(error = SEND_FAILED))
    }

    private fun shown(review: DappMessageReview): DappTransactionUiState {
        val content = review.content as DappSignContent.Transaction
        val transaction = content.transaction
        val token = content.tokens[transaction.to]
        return DappTransactionUiState(
            loading = false,
            name = review.request.dapp.name,
            url = review.request.dapp.url,
            accountName = review.account?.label,
            accountAddress = Eip55.format(review.address),
            to = Eip55.format(transaction.to),
            value = ether(transaction.value),
            meaning = meaningLine(transaction.meaning, token),
            meaningFields = meaningFields(transaction.meaning, token),
            dataHex = transaction.dataHex.takeIf { it.isNotEmpty() },
            dataSize = DappRequestViewModel.bytesLine(transaction.dataSize),
            warnings = buildList {
                if (transaction.meaning is DappCallMeaning.NotDecoded) add(NOT_DECODED)
                if (transaction.meaning is DappCallMeaning.TokenApproval) add(APPROVAL_NOTE)
            },
            refusal = review.refusal?.let { refusalLine(it) },
        )
    }

    private fun quoteLines(quote: DappTransactionQuote): List<SignInField> {
        val call = quote.call
        return listOfNotNull(
            SignInField("Fee, at most", ether(call.maxFee)),
            SignInField("Left in the account, at least", ether(call.nativeAfterAtLeast)),
            call.simulationBlock?.let { SignInField("Verified run", "Runs without reverting at block $it") }
                ?: SignInField("Verified run", "Not run: the light client cannot run calls on this build"),
            SignInField("Gas limit", call.transaction.gasLimit.toString()),
            SignInField("Nonce", call.transaction.nonce.toString()),
        )
    }

    companion object {
        const val EXPIRED = "This request has expired: the dApp stopped waiting. Nothing was sent."
        const val GONE = "This request is no longer waiting: it was answered, or its connection ended."
        const val SEND_FAILED = "Sending failed. Nothing was sent to the dApp."
        const val QUOTE_FAILED = "The transaction could not be worked out right now. Try again."
        const val NOT_QUOTED_YET = "The transaction is still being worked out from verified state."
        const val MAYBE_SENT = "A transaction signed for this request may be on its way already. Try again, " +
            "or wait for it in Activity: refusing now would tell the dApp no while it may still be mined."
        const val NOT_DECODED = "Serotine does not decode this call, so it cannot tell what it does: its data is " +
            "shown as it is. Send it only if you trust this dApp."
        const val APPROVAL_NOTE = "An approval moves nothing itself, but the spender may then take the tokens " +
            "without asking you again."

        fun ether(wei: UInt256): String = "${wei.formatUnits(18)} ${Network.SEPOLIA.nativeSymbol} (testnet)"

        fun answerLine(answer: DappAnswer): String? = when (answer) {
            DappAnswer.DELIVERED, DappAnswer.NOT_DELIVERED -> null
            DappAnswer.EXPIRED -> EXPIRED
            DappAnswer.NO_SUCH_REQUEST -> GONE
            DappAnswer.NOT_SAVED -> "That could not be saved. Nothing was sent to the dApp."
            DappAnswer.OFF -> "This build has dApp connections switched off."
        }

        fun refusalLine(refusal: DappSignRefusal): String = when (refusal) {
            DappSignRefusal.SAFE -> "A Safe's transactions from a dApp come with a later version."
            else -> DappRequestViewModel.refusalLine(refusal)
        }

        private fun amountOf(amount: UInt256, token: Token?, address: String): String = when {
            amount == UInt256.MAX -> "an unlimited amount of ${token?.symbol ?: "the token"}"
            token != null -> "${amount.formatUnits(token.decimals)} ${token.symbol}"
            else -> "${amount.toDecimal()} units of the token at $address"
        }

        fun meaningLine(meaning: DappCallMeaning, token: Token?): String? = when (meaning) {
            DappCallMeaning.Payment -> "A payment of its value to the recipient."
            is DappCallMeaning.TokenTransfer -> "A transfer of " +
                "${amountOf(meaning.amount, token, Eip55.format(meaning.token))} to ${Eip55.format(meaning.recipient)}."
            is DappCallMeaning.TokenApproval -> "An approval: lets ${Eip55.format(meaning.spender)} take up to " +
                "${amountOf(meaning.amount, token, Eip55.format(meaning.token))} from this account."
            is DappCallMeaning.NotDecoded -> null
        }

        fun meaningFields(meaning: DappCallMeaning, token: Token?): List<SignInField> = when (meaning) {
            is DappCallMeaning.TokenTransfer -> listOf(
                SignInField("Token", token?.let { "${it.symbol} · ${Eip55.format(meaning.token)}" }
                    ?: "${Eip55.format(meaning.token)} · not in the built-in token list"),
                SignInField("Recipient", Eip55.format(meaning.recipient)),
                SignInField("Amount", amountOf(meaning.amount, token, Eip55.format(meaning.token))),
            )
            is DappCallMeaning.TokenApproval -> listOf(
                SignInField("Token", token?.let { "${it.symbol} · ${Eip55.format(meaning.token)}" }
                    ?: "${Eip55.format(meaning.token)} · not in the built-in token list"),
                SignInField("Spender", Eip55.format(meaning.spender)),
                SignInField("Amount", amountOf(meaning.amount, token, Eip55.format(meaning.token))),
            )
            is DappCallMeaning.NotDecoded -> listOfNotNull(meaning.selector?.let { SignInField("Function", "0x$it") })
            DappCallMeaning.Payment -> emptyList()
        }
    }
}
