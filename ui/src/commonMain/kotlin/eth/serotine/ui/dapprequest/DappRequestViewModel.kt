package eth.serotine.ui.dapprequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignInMessage
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.DappAnswer
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.DappMessageReview
import eth.serotine.domain.usecase.DappMessageSigning
import eth.serotine.domain.usecase.DappSignRefusal
import eth.serotine.domain.usecase.DappSignWarning
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveDappMessages
import eth.serotine.domain.usecase.RefuseDappRequest
import eth.serotine.domain.usecase.SignDappMessage
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One field of a sign-in, display-ready. */
data class SignInField(val label: String, val value: String)

/** What a tap on a greyed-out item says. */
data class RequestExplanation(val title: String, val reason: String)

/**
 * A dApp's request to sign a message (docs/walletconnect.md, screen D4) as display-ready values: who
 * asks (the dApp's claim), the account asked to sign, on Sepolia, and the message: field by field
 * when it is a sign-in, else as text, or as hex marked "not text". The [warnings] to read before
 * signing, the [refusal] when it can only be refused, and the unlock. [gone] once the request is no
 * longer waiting; [done] once answered.
 */
data class DappRequestUiState(
    val loading: Boolean = true,
    val gone: Boolean = false,
    /** No longer waiting, and [notice] says why (it expired as Sign was tapped): Back leaves. */
    val ended: Boolean = false,
    /** "Sign-in request" for a sign-in, "Message to sign" otherwise. */
    val title: String = "",
    val name: String = "",
    val url: String = "",
    val accountName: String? = null,
    /** The account asked to sign, EIP-55 checksummed, in full. */
    val accountAddress: String = "",
    /** The sign-in's fields, in the order EIP-4361 gives them; empty when the message is not a sign-in. */
    val signIn: List<SignInField> = emptyList(),
    /** The message as text, when it is text and not a sign-in shown field by field. */
    val messageText: String? = null,
    /** The message as hex, when it is not text. */
    val messageHex: String? = null,
    val messageSize: String = "",
    val warnings: List<String> = emptyList(),
    val refusal: String? = null,
    /** How long the dApp waits for an answer. */
    val waits: String = "",
    val unlock: UnlockStep = UnlockStep(),
    val busy: Boolean = false,
    val notice: String? = null,
    val explaining: RequestExplanation? = null,
    val done: Boolean = false,
) {
    /** Sign is greyed out, still tappable, while it cannot be done (owner ruling, 2026-10-18, #55). */
    val signUnavailable: Boolean get() = refusal != null

    /** "Sign in" for a sign-in, "Sign" otherwise. */
    val signLabel: String get() = if (signIn.isNotEmpty()) "Sign in" else "Sign"
}

/**
 * The message request's ViewModel: signs the message with the wallet password or a fingerprint or
 * face, or refuses it. The password typed is held in the state while the field is shown, handed to
 * the signer in an [Unlock], never logged, and dropped once used and whenever the screen is not shown
 * (CLAUDE.md, *Trust*).
 */
class DappRequestViewModel(
    /** The request, as its route names it: `topic/id`. */
    requestKey: String,
    observeDappMessages: ObserveDappMessages,
    private val signDappMessage: SignDappMessage,
    private val refuseDappRequest: RefuseDappRequest,
    private val describeKeyStore: DescribeKeyStore,
    private val clock: () -> Long = ::epochSecondsNow,
    private val formatTime: (Long) -> String = ::formatDateTime,
    /** When how long the dApp waits is said again: every 15 seconds while the screen is open. */
    ticks: Flow<Unit> = every15Seconds(),
) : ViewModel() {

    private val id: DappRequestId? = requestKey.split('/').takeIf { it.size == 2 }?.let { (topic, number) ->
        number.toLongOrNull()?.let { DappRequestId(DappSessionId(topic), it) }
    }

    private data class Local(
        val unlock: UnlockStep = UnlockStep(),
        val busy: Boolean = false,
        val notice: String? = null,
        val explaining: RequestExplanation? = null,
        val done: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    init {
        viewModelScope.launch {
            val offered = try {
                biometricsOn(describeKeyStore())
            } catch (_: Exception) {
                false // the use case promises not to throw; the backstop: the password still works
            }
            local.update { it.copy(unlock = it.unlock.copy(biometrics = offered)) }
        }
    }

    val uiState: StateFlow<DappRequestUiState> = combine(observeDappMessages(), local, ticks) { reviews, local, _ ->
        val review = reviews.find { it.request.id == id }
            // No longer waiting: the screen leaves, unless an answer that did not take says why first.
            ?: return@combine if (local.notice != null && !local.done) {
                DappRequestUiState(loading = false, ended = true, notice = local.notice)
            } else {
                DappRequestUiState(loading = false, gone = true, done = local.done)
            }
        shown(review, local)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DappRequestUiState())

    private fun shown(review: DappMessageReview, local: Local): DappRequestUiState {
        val signIn = review.signIn
        val text = review.message.text
        return DappRequestUiState(
            loading = false,
            title = if (signIn != null) "Sign-in request" else "Message to sign",
            name = review.request.dapp.name,
            url = review.request.dapp.url,
            accountName = review.account?.label,
            accountAddress = Eip55.format(review.address),
            signIn = signIn?.let(::fields).orEmpty(),
            messageText = text.takeIf { signIn == null },
            messageHex = review.message.hex.takeIf { text == null },
            messageSize = bytesLine(review.message.size),
            warnings = review.warnings.sorted().map(::warningLine),
            refusal = review.refusal?.let(::refusalLine),
            waits = waitsLine(review.request.expiresAtSeconds - clock()),
            unlock = local.unlock,
            busy = local.busy,
            notice = local.notice,
            explaining = local.explaining,
            done = local.done,
        )
    }

    private fun fields(signIn: SignInMessage): List<SignInField> = buildList {
        add(SignInField("Site", signIn.scheme?.let { "$it://${signIn.domain}" } ?: signIn.domain))
        add(SignInField("Account", Eip55.format(signIn.address)))
        signIn.statement?.let { add(SignInField("Statement", it)) }
        add(SignInField("URI", signIn.uri))
        val network = Network.ofChainId(signIn.chainId)
        val networkName = network?.let { "${it.displayName} (${signIn.chainId})" } ?: "Chain ${signIn.chainId}"
        add(SignInField("Network", networkName))
        add(SignInField("Nonce", signIn.nonce))
        add(SignInField("Issued", formatTime(signIn.issuedAtSeconds)))
        signIn.expiresAtSeconds?.let { add(SignInField("Expires", formatTime(it))) }
        signIn.notBeforeSeconds?.let { add(SignInField("Not before", formatTime(it))) }
        signIn.requestId?.let { add(SignInField("Request ID", it)) }
        if (signIn.resources.isNotEmpty()) add(SignInField("Resources", signIn.resources.joinToString("\n")))
    }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = local.update {
        if (it.busy) it else it.copy(unlock = it.unlock.copy(password = WalletPassword(text), error = null))
    }

    /** Signs with the typed wallet password. */
    fun sign() {
        if (explainRefusal()) return
        val password = local.value.unlock.password
        if (local.value.busy || password.text.isEmpty()) return
        sign(Unlock.WithPassword(password))
    }

    /** Signs with a fingerprint or face. */
    fun signWithBiometrics() {
        if (explainRefusal()) return
        val state = uiState.value
        if (local.value.busy || !local.value.unlock.biometrics) return
        // What is signed, said in the prompt: the last thing read before the key signs. Nothing the dApp
        // states goes in it, since the system's prompt would show the dApp's words as the app's own.
        val what = if (state.signIn.isNotEmpty()) "Sign in to a dApp" else "Sign a dApp's message"
        sign(Unlock.WithBiometrics("$what with ${state.accountAddress}"))
    }

    /** Refuses the request: as declined, or with its refusal's error when it cannot be signed. */
    fun refuse() {
        val request = id ?: return
        if (local.value.busy) return
        local.update { it.copy(busy = true, notice = null, unlock = it.unlock.copy(password = WalletPassword.EMPTY)) }
        viewModelScope.launch {
            val answer = try {
                withContext(NonCancellable) { refuseDappRequest(request) }
            } catch (_: Exception) {
                DappAnswer.NOT_SAVED // the use case promises not to throw; the backstop: the request still waits
            }
            local.update { it.copy(busy = false, done = answered(answer), notice = answerLine(answer)) }
        }
    }

    fun dismissExplanation() = local.update { it.copy(explaining = null) }

    /** The screen stopped being shown: the password goes. */
    fun onHidden() = local.update { it.copy(unlock = it.unlock.copy(password = WalletPassword.EMPTY)) }

    override fun onCleared() {
        // The screen is gone, and the password with it.
        onHidden()
    }

    private fun explainRefusal(): Boolean {
        val reason = uiState.value.refusal ?: return false
        local.update { it.copy(explaining = RequestExplanation("Cannot be signed", reason)) }
        return true
    }

    private fun sign(unlock: Unlock) {
        val request = id ?: return
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        local.update {
            it.copy(busy = true, notice = null, unlock = it.unlock.copy(password = WalletPassword.EMPTY, error = null))
        }
        viewModelScope.launch {
            val signing = try {
                withContext(NonCancellable) { signDappMessage(request, unlock) }
            } catch (_: Exception) {
                null // the use case promises not to throw; the backstop: nothing is said to be signed
            }
            local.update { after(it, signing) }
        }
    }

    private fun after(local: Local, signing: DappMessageSigning?): Local = when (signing) {
        is DappMessageSigning.Signed -> local.copy(busy = false, done = true)
        is DappMessageSigning.NotSigned -> local.copy(
            busy = false,
            unlock = local.unlock.copy(
                error = notSignedText(signing.signing, SIGN_FAILED),
                biometrics = local.unlock.biometrics && biometricsStillOffered(signing.signing),
            ),
        )
        is DappMessageSigning.Refused ->
            local.copy(busy = false, explaining = RequestExplanation("Cannot be signed", refusalLine(signing.refusal)))
        DappMessageSigning.Expired -> local.copy(busy = false, notice = EXPIRED)
        DappMessageSigning.Gone -> local.copy(busy = false, notice = GONE)
        DappMessageSigning.NotSaved -> local.copy(busy = false, notice = NOT_SAVED)
        null -> local.copy(busy = false, unlock = local.unlock.copy(error = SIGN_FAILED))
    }

    companion object {
        const val SIGN_FAILED = "Signing failed. Nothing was sent to the dApp."
        const val EXPIRED = "This request has expired: the dApp stopped waiting. Nothing was sent to it."
        const val GONE = "This request is no longer waiting: it was answered, or its connection ended."
        const val NOT_SAVED = "That could not be saved. Nothing was sent to the dApp."

        private fun answered(answer: DappAnswer): Boolean =
            answer == DappAnswer.DELIVERED || answer == DappAnswer.NOT_DELIVERED

        private fun answerLine(answer: DappAnswer): String? = when (answer) {
            DappAnswer.DELIVERED, DappAnswer.NOT_DELIVERED -> null
            DappAnswer.EXPIRED -> EXPIRED
            DappAnswer.NO_SUCH_REQUEST -> GONE
            DappAnswer.NOT_SAVED -> NOT_SAVED
            DappAnswer.OFF -> "This build has dApp connections switched off."
        }

        /** A tick now and every 15 seconds after. */
        fun every15Seconds(): Flow<Unit> = flow {
            while (true) {
                emit(Unit)
                delay(15_000)
            }
        }

        fun bytesLine(size: Int): String = if (size == 1) "1 byte" else "$size bytes"

        fun waitsLine(seconds: Long): String = when {
            seconds <= 0 -> "The dApp has stopped waiting for an answer."
            seconds < 120 -> "The dApp waits for an answer for about a minute more."
            seconds < 3_600 -> "The dApp waits for an answer for ${seconds / 60} more minutes."
            seconds < 2 * 86_400 -> "The dApp waits for an answer for ${seconds / 3_600} more hours."
            else -> "The dApp waits for an answer for ${seconds / 86_400} more days."
        }

        fun warningLine(warning: DappSignWarning): String = when (warning) {
            DappSignWarning.DOMAIN_NOT_DAPP_URL ->
                "This sign-in is for another site than the one the dApp named when it connected. It may be a " +
                    "site posing as another. Both names are the dApp's own claim, so Serotine cannot tell which " +
                    "site is asking."
            DappSignWarning.NOT_A_SIGN_IN ->
                "This is not a sign-in. Serotine cannot tell what signing it commits you to: sign only if you " +
                    "trust this dApp and understand the message."
        }

        fun refusalLine(refusal: DappSignRefusal): String = when (refusal) {
            DappSignRefusal.NOT_SESSION_ACCOUNT -> "It names an account this dApp was not given."
            DappSignRefusal.OTHER_CHAIN -> "It names a network other than Sepolia."
            DappSignRefusal.NOT_IN_WALLET -> "The account is no longer in this wallet."
            DappSignRefusal.ACCOUNTS_UNREADABLE ->
                "Your accounts cannot be read right now, so the account cannot be checked."
            DappSignRefusal.NO_KEY -> "This account is watched: there is no key for it on this device."
            DappSignRefusal.SEPOLIA_OFF -> "Sepolia is off. Turn it on in Settings, under Networks."
            DappSignRefusal.SAFE -> "A Safe cannot sign messages here yet: that comes with Safe messages (EIP-1271)."
            DappSignRefusal.SIGN_IN_OTHER_ADDRESS -> "The sign-in names another account than the one asked to sign it."
            DappSignRefusal.SIGN_IN_OTHER_CHAIN -> "The sign-in names a network other than Sepolia."
            DappSignRefusal.SIGN_IN_EXPIRED -> "The sign-in has expired."
            DappSignRefusal.SIGN_IN_NOT_YET_VALID -> "The sign-in is not valid yet."
        }
    }
}
