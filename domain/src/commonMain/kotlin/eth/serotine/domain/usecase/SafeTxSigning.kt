package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PinnedFallbackHandlers
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeSettingsChange
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SafeTxGasTerms
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.SafeTxQueue
import eth.serotine.domain.repository.SafeTxQueueList
import eth.serotine.domain.repository.SignatureRecovery
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.SigningRequestCodec
import eth.serotine.domain.repository.SigningRequestDecoding
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Why a Safe transaction was not imported, signed, approved or executed (docs/wallet-ux.md,
 * screen 17): each use case says which of these it answers. Nothing was signed or sent.
 */
sealed interface SafeTxRefusal {

    /** The text is no signing request ([SigningRequestDecoding.Malformed]). */
    data object Malformed : SafeTxRefusal

    /** A signing request of [version], which this build does not read. */
    data class UnknownVersion(val version: Int) : SafeTxRefusal

    /**
     * A request for the chain [chainId]: a network this wallet does not support, or not the
     * network of the Safe it was brought to (the same address on another chain is another Safe).
     */
    data class WrongChain(val chainId: Long) : SafeTxRefusal

    /** The address holds no Safe this app recognises, as its verified code and storage say. */
    data object NotASafe : SafeTxRefusal

    /**
     * A request for the Safe at [safe], not the one it was imported for: it is imported from that
     * Safe's own screen, so that a transaction never appears under a Safe it cannot touch.
     */
    data class OtherSafe(val safe: Address) : SafeTxRefusal

    /**
     * The transaction's nonce is below the Safe's verified nonce, [safeNonce]: that nonce is used
     * on chain, by this transaction or another, so it can never run.
     */
    data class NonceUsed(val safeNonce: Long) : SafeTxRefusal

    /**
     * The transaction's nonce is above the Safe's verified nonce, [safeNonce]: the transactions
     * queued before it run first, and only the next one can be executed.
     */
    data class NotNext(val safeNonce: Long) : SafeTxRefusal

    /** [address] is not one of the Safe's current owners, as its verified storage says. */
    data class NotAnOwner(val address: Address) : SafeTxRefusal

    /** The signature said to be [owner]'s does not recover to [owner] over the hash computed here. */
    data class BadSignature(val owner: Address) : SafeTxRefusal

    /**
     * A delegate call: the Safe would run the target's code as itself, and the code could do
     * anything to it, so this wallet neither signs, approves nor executes one (docs/wallet-ux.md,
     * screen 17). It is shown, decoded as such, and nothing more. A batch through the pinned
     * MultiSendCallOnly 1.4.1, each inner call decoded and shown, is to be co-signed (owner ruling,
     * 2026-10-18, #57); that is not built yet, so every delegate call is refused until it is.
     */
    data object DelegateCall : SafeTxRefusal

    /** [owner] has already signed it: there is nothing to add. */
    data class AlreadySigned(val owner: Address) : SafeTxRefusal

    /** The owner has already approved its hash on chain. */
    data object AlreadyApproved : SafeTxRefusal

    /**
     * This account's approval or execution of it was sent already and no block has it yet
     * ([eth.serotine.domain.repository.SentTransfers]): a second one would only pay a fee to
     * revert, or to approve twice.
     */
    data object AlreadySent : SafeTxRefusal

    /** [confirmations] of the Safe's owners confirm it, fewer than its [threshold]. */
    data class ThresholdNotMet(val confirmations: Int, val threshold: Int) : SafeTxRefusal

    /**
     * It carries gas or refund terms ([eth.serotine.domain.model.SafeTxGasTerms]): a refund can
     * pay the Safe's funds to whoever it names, bounded only by what the Safe holds, and a
     * `safeTxGas` lets the call fail and still use up the nonce. This wallet proposes none, and
     * signs, approves and executes none either (docs/wallet-ux.md, screen 17); it is shown,
     * with its terms, and nothing more.
     */
    data object GasTerms : SafeTxRefusal

    /**
     * It sets [handler] as the Safe's fallback handler, which is not the pinned
     * CompatibilityFallbackHandler ([PinnedFallbackHandlers]; owner ruling, 2026-10-18, #63): the
     * handler answers the EIP-1271 signature checks other contracts make for the Safe, and one that
     * accepts every signature lets anyone spend what the Safe has approved. This wallet signs,
     * approves and executes none; it is shown, decoded as such, and nothing more.
     */
    data class UnpinnedFallbackHandler(val handler: Address) : SafeTxRefusal

    /**
     * A call of the Safe itself that is none of the actions decoded exactly here: a change of its
     * settings, whose data is exactly that function's encoding, or the empty, zero-value
     * rejection. Solidity ignores call data past the arguments and the Safe's ABI decoder masks an
     * address's upper bytes, so data that misses the strict decoding by a trailing byte or a dirty
     * word can still change a setting on chain, the fallback handler included. Whatever its
     * selector, this wallet signs, approves and executes none; it is shown, and nothing more.
     */
    data object UndecodedSelfCall : SafeTxRefusal

    /**
     * It enables [module] as a module of the Safe: a module moves the Safe's funds and changes its
     * settings without its owners' signatures. Whatever the module, this wallet signs, approves and
     * executes none (owner ruling, 2026-10-18, on #54: play it safe); which modules may be enabled
     * is still open (#54), and a temporary, warned way past it is planned (#78). Disabling a module
     * is not refused: it carries a warning.
     */
    data class ModuleEnabled(val module: Address) : SafeTxRefusal

    /**
     * It would leave the Safe's [owners] owners all needed (a threshold of [owners], more than
     * one): with one of them lost, the rest could never reach the threshold again, not even to
     * swap the lost one out. That shape only comes with a recovery module (owner ruling,
     * 2026-10-18, #54, its change half), and none is recognised here yet, so a threshold change,
     * an owner added or an owner removed that ends there is refused.
     */
    data class NeedsRecoveryModule(val owners: Int) : SafeTxRefusal

    /** The light client has no verified head yet. */
    data object Syncing : SafeTxRefusal

    /**
     * A swap, or the token approval a swap needs, while the light client catches up: neither is
     * drafted on what was verified last, also with *Sign while syncing* on (docs/lenient-mode.md,
     * *What stays strict*): a swap's minimum is judged against a fresh quote, and an approval's
     * spender against the router's code proven now, which the review shows before anything is
     * signed. It waits for the light client.
     */
    data object SwapNeedsFreshCheck : SafeTxRefusal

    /** A verified figure could not be had right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : SafeTxRefusal

    /**
     * The Safe itself — its owners, threshold and nonce, as its code and storage prove them — could
     * not be verified right now, for [reason]: a one-shot check ([ObserveSafe.check]) that waited
     * out its budget for an unproven reading, or met a light client that cannot verify at all.
     */
    data class SafeUnavailable(val reason: ReadUnavailable) : SafeTxRefusal

    /** The transaction the owner account would send was not quoted: [refusal]. */
    data class NotQuoted(val refusal: CallRefusal) : SafeTxRefusal

    /**
     * The Safe is still on its [version] singleton, from before 1.3.0's EIP-712 domain
     * (`EIP712Domain(uint256 chainId,address verifyingContract)`, computed fresh for every call):
     * 0.0.2, 0.1.0, 1.0.0, 1.1.0, 1.1.1 and 1.2.0 each compute their `domainSeparator` once at
     * setup and store it, with no chain id anywhere in it ([SafeTx.hash]'s KDoc); 0.0.2 and 0.1.0
     * hash another `SafeTx` struct type besides. [SafeTx.hash] would compute a digest that
     * Safe never checks, so nothing here is signed, approved or executed against it. Shown only
     * (`SafeDeployments` recognises these releases for display so a Safe left there, or
     * one still deciding whether to migrate, is not shown as a plain contract). A Safe since moved
     * to a pinned ≥1.3.0 singleton by its owners (`changeMasterCopy`) is unaffected:
     * [eth.serotine.domain.repository.SafeState.version] is the singleton it is on now, not the
     * one its proxy was deployed with.
     */
    data class TooOldToSign(val version: String) : SafeTxRefusal
}

/**
 * A Safe transaction as this wallet shows it: [request], what it does as decoded here from its
 * raw fields ([action]), and the gas and refund terms it carries beside its call ([gasTerms],
 * null: none). A [delegateCall] is marked as such. [refusal] says why this wallet will not sign,
 * approve or execute it, before anyone asks ([unsignable]): a delegate call, gas terms, a call of
 * the Safe itself not decoded exactly as one of its settings changes or the rejection, a fallback
 * handler set that is not pinned, or a module enabled (null: none of them). Made only by [of], so
 * what is shown is always what the request's fields decode to.
 */
@ConsistentCopyVisibility
data class DecodedSafeTx private constructor(
    val request: SigningRequest,
    val action: SafeTxAction,
    val gasTerms: SafeTxGasTerms?,
) {

    /** Whether the Safe would run the target's code as itself. */
    val delegateCall: Boolean
        get() = request.tx.operation == SafeOperation.DELEGATECALL

    /** Why this wallet signs, approves and executes none of it, or null when it may. */
    val refusal: SafeTxRefusal?
        get() = request.unsignable()

    companion object {
        /** [request], decoded. */
        fun of(request: SigningRequest) =
            DecodedSafeTx(request, SafeTxAction.of(request.safe, request.tx), SafeTxGasTerms.of(request.tx))
    }
}

/**
 * Why this wallet signs, approves and executes none of [this], whatever its signatures and the
 * Safe say: a delegate call, gas or refund terms, a call of the Safe itself that is not decoded
 * exactly as one of its settings changes or the rejection, a module enabled (owner ruling,
 * 2026-10-18, on #54), or a fallback handler set that is not pinned ([PinnedFallbackHandlers];
 * owner ruling, 2026-10-18, #63) (docs/wallet-ux.md, screen 17). Removing the handler, removing
 * the guard and disabling a module are not refused: they carry a warning. Null when it may be
 * signed.
 */
internal fun SigningRequest.unsignable(): SafeTxRefusal? {
    if (tx.operation == SafeOperation.DELEGATECALL) return SafeTxRefusal.DelegateCall
    if (SafeTxGasTerms.of(tx) != null) return SafeTxRefusal.GasTerms
    val action = SafeTxAction.of(safe, tx)
    // On chain a self-call need not match the strict decoding to change a setting: only the exact ones pass.
    if (tx.to == safe && action !is SafeTxAction.SettingsChange && action != SafeTxAction.Rejection) {
        return SafeTxRefusal.UndecodedSelfCall
    }
    val change = (action as? SafeTxAction.SettingsChange)?.change
    if (change is SafeSettingsChange.ModuleEnabled) return SafeTxRefusal.ModuleEnabled(change.module)
    val handler = (change as? SafeSettingsChange.FallbackHandlerSet)?.handler
    if (handler != null && !PinnedFallbackHandlers.allowed(network, handler)) {
        return SafeTxRefusal.UnpinnedFallbackHandler(handler)
    }
    return null
}

/**
 * [SafeTxRefusal.NeedsRecoveryModule] when [this] would leave the Safe, now as [state] says, an
 * n-of-n with n above one: a threshold changed to the owner count or above, an owner removed or
 * added with the threshold set to the count that leaves or above (owner ruling, 2026-10-18, #54).
 * A threshold above the owner count reverts on chain anyway, so refusing it costs nothing, and it
 * catches one queued behind an owner added. No recovery module is recognised yet, so the Safe's
 * modules do not lift it. Null otherwise.
 *
 * Only the Safe's current owners and threshold are seen, not the changes queued at earlier
 * nonces: a threshold change signed behind an owner's removal can end as an n-of-n once both
 * run. Punicus's own execution quote checks the state the Safe is in then, and refuses it; but
 * another wallet holding enough signatures could still execute it. Closing that fully is part of
 * how far ahead of a Safe's nonce a request may be signed, the owner's open question (#59).
 */
internal fun SigningRequest.withoutRecovery(state: SafeState): SafeTxRefusal? = withoutRecovery(state.owners.size)

/** [withoutRecovery] for a Safe of [owners] owners now. */
internal fun SigningRequest.withoutRecovery(owners: Int): SafeTxRefusal? =
    (SafeTxAction.of(safe, tx) as? SafeTxAction.SettingsChange)?.change?.withoutRecovery(owners)

/**
 * [SafeTxRefusal.TooOldToSign] when this Safe is still on one of the singletons from before
 * 1.3.0's EIP-712 domain and `SafeTx` struct type ([SafeTxRefusal.TooOldToSign]'s KDoc); null
 * for a pinned ≥1.3.0 singleton, migrated to or deployed on.
 *
 * Fail-closed: only the versions [SafeTx.hash] computes the digest of
 * ([EIP712_CHAIN_ID_DOMAIN_VERSIONS]) may be signed; every other version a Safe is recognised
 * on is refused, so pinning another old singleton for display (0.0.2, 0.1.0 and 1.1.0 were,
 * after 1.0.0, 1.1.1 and 1.2.0) can never let a digest be signed that the Safe does not check.
 * A release after 1.5.0 is refused too until it is added there, once [SafeTx.hash] is checked
 * against it: pinning it in `SafeDeployments` alone shows it and signs nothing.
 */
internal fun SafeState.tooOldToSign(): SafeTxRefusal? =
    SafeTxRefusal.TooOldToSign(version).takeIf { version !in EIP712_CHAIN_ID_DOMAIN_VERSIONS }

/**
 * The versions [SafeState.tooOldToSign] lets through: Safe's releases from 1.3.0 on, whose
 * `EIP712Domain(uint256 chainId,address verifyingContract)` and `SafeTx` type [SafeTx.hash] computes.
 */
private val EIP712_CHAIN_ID_DOMAIN_VERSIONS = setOf("1.3.0", "1.4.1", "1.5.0")

/** [SigningRequest.withoutRecovery] for [this] change of a Safe of [owners] owners now. */
internal fun SafeSettingsChange.withoutRecovery(owners: Int): SafeTxRefusal.NeedsRecoveryModule? {
    val (after, threshold) = when (this) {
        is SafeSettingsChange.ThresholdChanged -> owners to threshold
        is SafeSettingsChange.OwnerRemoved -> owners - 1 to threshold
        is SafeSettingsChange.OwnerAdded -> owners + 1 to threshold
        else -> return null
    }
    return SafeTxRefusal.NeedsRecoveryModule(after).takeIf { after > 1 && threshold >= after.toLong() }
}

/** The Safe's verified state for a one-shot check, or the refusal there is instead. */
internal sealed interface SafeCheck {
    data class Verified(val reading: SafeReading.Verified) : SafeCheck
    data class Refused(val refusal: SafeTxRefusal) : SafeCheck
}

/**
 * How long a one-shot check of a Safe ([check]) waits for a reading that could not be verified
 * to be made again. The engine adapter retries a failed proof and a read the head moved under
 * for up to 20 s itself (`VerifiedAddressKinds.SAFE_READ_BUDGET`, the code's proof included), and
 * the reading is made again at the next poll (4 s on): this wait outlasts both with room for that
 * next read, so it normally does not cut off the very read it is waiting on. Normally, not
 * always: the adapter checks its 20 s only between blocking engine calls, so a slow call runs past
 * it; the poll reads the engine's state before it reads the Safe, outside those 20 s; and this
 * wait cannot end before a blocked engine (JNA) call returns. A caller with less time than this
 * passes its own budget. Public so that the engine adapter's tests hold its budget against this.
 */
val SAFE_CHECK_BUDGET: Duration = 45.seconds

/**
 * The Safe at [safe] as the light client proves it now, for a one-shot check: an import, the
 * checks before Sign, Approve, Execute and a proposal, and the owners' stand on a transaction its
 * screen reads ([ReadSafeTxOwners], on opening, on Check again and after a signature, which so
 * shows "Reading the owners…" for as long). A reading that could not be verified this
 * time ([ReadUnavailable.UNPROVEN]: a proof a peer did not deliver, a read the head kept moving
 * under) is waited out for the next one the reading makes, for up to [budget]; the first other
 * answer stands, the light client's syncing included. One that cannot change by asking again — no
 * engine ([ReadUnavailable.NO_ENGINE]), an anchor it refuses ([ReadUnavailable.STALE_ANCHOR]), a
 * read this engine will not make ([ReadUnavailable.REFUSED]) — is answered at once. Still
 * unproven after [budget], it is [SafeTxRefusal.SafeUnavailable]. Only verified readings are ever
 * taken: the wait changes how often the chain is asked, never what is believed.
 */
internal suspend fun ObserveSafe.check(safe: AccountId, budget: Duration = SAFE_CHECK_BUDGET): SafeCheck {
    val reading = withTimeoutOrNull(budget) { this@check(safe).firstOrNull { !it.unproven } } ?: UNPROVEN_SAFE
    return when (reading) {
        is SafeReading.Verified -> SafeCheck.Verified(reading)
        SafeReading.Checking -> SafeCheck.Refused(SafeTxRefusal.Syncing)
        SafeReading.NotASafe -> SafeCheck.Refused(SafeTxRefusal.NotASafe)
        is SafeReading.Unavailable -> SafeCheck.Refused(SafeTxRefusal.SafeUnavailable(reading.reason))
    }
}

private val UNPROVEN_SAFE = SafeReading.Unavailable(ReadUnavailable.UNPROVEN)

/** Whether this reading could not be verified this time, and the next one may be. */
private val SafeReading.unproven: Boolean
    get() = this == UNPROVEN_SAFE

/**
 * [request]'s signatures that are current owners' (of [owners]) and recover to them over the
 * hash computed here; or the first that is not, as a refusal ([SafeTxRefusal.NotAnOwner],
 * [SafeTxRefusal.BadSignature]).
 */
internal fun SignatureRecovery.check(request: SigningRequest, owners: List<Address>): SafeTxRefusal? {
    for (signature in request.signatures) {
        if (signature.owner !in owners) return SafeTxRefusal.NotAnOwner(signature.owner)
        if (recover(request.hash, signature.signature) != signature.owner) {
            return SafeTxRefusal.BadSignature(signature.owner)
        }
    }
    return null
}

/** [request]'s signatures that are [owners]' and recover to them: those a Safe accepts now. */
internal fun SignatureRecovery.valid(request: SigningRequest, owners: List<Address>): List<SafeSignature.Ecdsa> =
    request.signatures.filter { it.owner in owners && recover(request.hash, it.signature) == it.owner }

/**
 * [request] with only the signatures [valid] keeps: what is shown and shared of it, so that a
 * signature of an owner removed since it was queued never makes another device refuse it.
 */
internal fun SignatureRecovery.validOnly(request: SigningRequest, owners: List<Address>): SigningRequest =
    SigningRequest.of(request.network, request.safe, request.tx, valid(request, owners))

/**
 * Imports a signing request that came from another device as text (CLAUDE.md, *Data sources*:
 * no off-chain service), and checks it against the chain before anything of it is believed:
 * the text decodes strictly ([SigningRequestCodec]) for a network the app knows; the Safe
 * is one this app recognises, as its verified code and storage say ([ObserveSafe]); the
 * request's nonce is not below the Safe's verified nonce (equal: it is next; above: it is
 * queued behind the ones before it); its hash is computed here from the raw fields; and every
 * signature it carries is a current owner's and recovers to that owner over that hash
 * ([SignatureRecovery]). Anything else is refused with the reason, and nothing of it is kept.
 * The Safe is checked once, as a one-shot check ([check]): a reading that could not be verified
 * this time is waited out for the next one, up to [SAFE_CHECK_BUDGET], and one still unproven then
 * is [SafeTxRefusal.SafeUnavailable].
 *
 * What it does is decoded here from the fields ([DecodedSafeTx]); a delegate call or gas terms
 * are accepted for display, marked as such, and never signed here. A request that passes is
 * saved to the queue ([SafeTxQueue]), its signatures merged into the same transaction's there.
 * The text, up to the codec's limit, is decoded on [decoding], never on the caller's thread.
 */
class ImportSigningRequest(
    private val codec: SigningRequestCodec,
    private val observeSafe: ObserveSafe,
    private val recovery: SignatureRecovery,
    private val queue: SafeTxQueue,
    private val decoding: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Imports the request [text] carries. With [safe] set, only a request for that Safe, on its
     * network, is imported: one for another chain is [SafeTxRefusal.WrongChain] and one for any
     * other Safe is [SafeTxRefusal.OtherSafe], before the chain is asked or anything is kept.
     */
    suspend operator fun invoke(text: String, safe: AccountId? = null): SigningRequestImport {
        val request = when (val decoded = withContext(decoding) { codec.decode(text) }) {
            is SigningRequestDecoding.Decoded -> decoded.request
            SigningRequestDecoding.Malformed -> return SigningRequestImport.Refused(SafeTxRefusal.Malformed)
            is SigningRequestDecoding.UnknownVersion ->
                return SigningRequestImport.Refused(SafeTxRefusal.UnknownVersion(decoded.version))
            is SigningRequestDecoding.UnknownChain ->
                return SigningRequestImport.Refused(SafeTxRefusal.WrongChain(decoded.chainId))
        }
        if (safe != null && request.network != safe.network) {
            return SigningRequestImport.Refused(SafeTxRefusal.WrongChain(request.network.chainId))
        }
        if (safe != null && request.safe != safe.address) {
            return SigningRequestImport.Refused(SafeTxRefusal.OtherSafe(request.safe))
        }
        val reading = when (val check = observeSafe.check(AccountId(request.network, request.safe))) {
            is SafeCheck.Verified -> check.reading
            is SafeCheck.Refused -> return SigningRequestImport.Refused(check.refusal)
        }
        val state = reading.state
        state.tooOldToSign()?.let { return SigningRequestImport.Refused(it) }
        if (request.nonce < state.nonce) return SigningRequestImport.Refused(SafeTxRefusal.NonceUsed(state.nonce))
        recovery.check(request, state.owners)?.let { return SigningRequestImport.Refused(it) }
        queue.prune(AccountId(request.network, request.safe), state.nonce)
        val saved = queue.save(request)
        // Shown and shared with what the Safe accepts now of what was queued here before.
        val queued = recovery.validOnly(saved ?: request, state.owners)
        return SigningRequestImport.Imported(DecodedSafeTx.of(queued), reading, saved = saved != null)
    }
}

/** The outcome of [ImportSigningRequest]. */
sealed interface SigningRequestImport {

    /**
     * [tx] passed every check against [safe], the Safe's verified state (its owners, threshold
     * and nonce, at its block), and is queued with the signatures it carried and those it had
     * here already; [saved] false when the queue could not be saved, so it is shown but not kept.
     */
    data class Imported(val tx: DecodedSafeTx, val safe: SafeReading.Verified, val saved: Boolean) :
        SigningRequestImport {
        /** How many of the Safe's transactions must run before this one: 0 when it is next. */
        val queuedBehind: Long
            get() = tx.request.nonce - safe.state.nonce
    }

    /** Not imported: [refusal]. */
    data class Refused(val refusal: SafeTxRefusal) : SigningRequestImport
}

/**
 * A signing request as the text it travels in to another device ([SigningRequestCodec]): the
 * raw fields and the signatures collected so far, never a description of them — Share request
 * on docs/wallet-ux.md's screen 17.
 */
class EncodeSigningRequest(private val codec: SigningRequestCodec) {
    operator fun invoke(request: SigningRequest): String = codec.encode(request)
}

/**
 * Signs a Safe transaction with an owner key held here: [Signer] signs its hash — the EIP-712
 * digest computed here from the raw fields, as given, with no `eth_sign` prefix — with
 * [owner]'s key as [unlock] opens it. Only a current owner of the Safe, as its verified storage
 * says, signs, and only a transaction whose nonce the Safe has not passed; a delegate call is
 * refused (docs/wallet-ux.md, screen 17). The signature is checked to recover to [owner] before
 * it is kept, and saved to the queue with the request's other signatures that are current
 * owners' and recover to them ([SafeTxQueue]). On [network] only.
 *
 * Two steps, so that a caller can let the first be cancelled and not the second: [check] asks
 * the chain (it may wait out an unproven reading of the Safe, [ObserveSafe.check]) and asks for
 * no key; [sign] signs and queues against exactly the state that check verified, and is the step
 * a caller runs to its end. [invoke] is both, one after the other.
 */
class SignSafeTx(
    private val signer: Signer,
    private val observeSafe: ObserveSafe,
    private val recovery: SignatureRecovery,
    private val queue: SafeTxQueue,
    private val network: Network,
) {
    suspend operator fun invoke(request: SigningRequest, owner: Address, unlock: Unlock): SafeTxSigning =
        when (val checked = check(request, owner)) {
            is SafeTxSignCheck.Ready -> sign(checked, unlock)
            is SafeTxSignCheck.Refused -> SafeTxSigning.Refused(checked.refusal)
        }

    /**
     * Whether [owner] may sign [request] now, as the Safe's verified state says: the network, what
     * it does, the Safe's nonce and owners, the threshold it would leave, and whether [owner]'s
     * signature is among its valid ones already. Nothing is signed, and no key is asked for. The
     * Safe's check waits out an unproven reading for up to [budget] (at most [SAFE_CHECK_BUDGET]):
     * a caller whose signature has a deadline (a review's minute, a prompt's) passes what is left
     * of it, and an unproven Safe is then [SafeTxRefusal.SafeUnavailable] in time.
     */
    suspend fun check(
        request: SigningRequest,
        owner: Address,
        budget: Duration = SAFE_CHECK_BUDGET,
    ): SafeTxSignCheck {
        fun refused(refusal: SafeTxRefusal) = SafeTxSignCheck.Refused(refusal)
        if (request.network != network) return refused(SafeTxRefusal.WrongChain(request.network.chainId))
        request.unsignable()?.let { return refused(it) }
        val state = when (
            val check = observeSafe.check(
                AccountId(request.network, request.safe),
                budget.coerceAtMost(SAFE_CHECK_BUDGET),
            )
        ) {
            is SafeCheck.Verified -> check.reading.state
            is SafeCheck.Refused -> return refused(check.refusal)
        }
        state.tooOldToSign()?.let { return refused(it) }
        if (request.nonce < state.nonce) return refused(SafeTxRefusal.NonceUsed(state.nonce))
        if (owner !in state.owners) return refused(SafeTxRefusal.NotAnOwner(owner))
        request.withoutRecovery(state)?.let { return refused(it) }
        // Of the signatures the request carries, only the checked ones count: a forged one in the
        // owner's name neither stands for it nor keeps it from signing.
        val valid = recovery.valid(request, state.owners)
        if (valid.any { it.owner == owner }) return refused(SafeTxRefusal.AlreadySigned(owner))
        return SafeTxSignCheck.Ready(request, owner, state, valid)
    }

    /**
     * Signs [checked]'s request as its owner with [unlock], and queues it with the valid
     * signatures that check found: against the Safe's state it verified, not read again.
     */
    suspend fun sign(checked: SafeTxSignCheck.Ready, unlock: Unlock): SafeTxSigning =
        when (val made = signature(checked, unlock)) {
            is SafeTxSignature.Made -> queue(checked, made.request)
            is SafeTxSignature.NotMade -> made.outcome
        }

    /** [sign]'s first half: the request with [checked]'s owner's signature, checked, not queued yet. */
    internal suspend fun signature(checked: SafeTxSignCheck.Ready, unlock: Unlock): SafeTxSignature {
        val request = checked.request
        val owner = checked.owner
        val signature = when (val signing = signer.sign(owner, request.hash, unlock)) {
            is Signing.Signed -> signing.signature
            else -> return SafeTxSignature.NotMade(SafeTxSigning.NotSigned(signing))
        }
        // The key store's signature is checked like a co-signer's: one that recovers to another
        // address is never kept, nor shared.
        if (recovery.recover(request.hash, signature) != owner) {
            return SafeTxSignature.NotMade(SafeTxSigning.Refused(SafeTxRefusal.BadSignature(owner)))
        }
        // What is queued is checked: the carried signatures that count, with this one.
        return SafeTxSignature.Made(
            SigningRequest.of(request.network, request.safe, request.tx, checked.valid)
                .withSignatures(listOf(SafeSignature.Ecdsa(owner, signature))),
        )
    }

    /** [sign]'s second half: [signed], [signature]'s, queued. */
    internal suspend fun queue(checked: SafeTxSignCheck.Ready, signed: SigningRequest): SafeTxSigning.Signed {
        queue.prune(AccountId(signed.network, signed.safe), checked.state.nonce)
        val saved = queue.save(signed)
        // Shown and shared with what the Safe accepts now of what was queued here before.
        return SafeTxSigning.Signed(recovery.validOnly(saved ?: signed, checked.state.owners), saved = saved != null)
    }
}

/** What [SignSafeTx.signature] came to: the signed request, or the outcome that ends the signing. */
internal sealed interface SafeTxSignature {
    data class Made(val request: SigningRequest) : SafeTxSignature
    data class NotMade(val outcome: SafeTxSigning) : SafeTxSignature
}

/** What [SignSafeTx.check] came to. */
sealed interface SafeTxSignCheck {

    /**
     * [owner] may sign [request]: the Safe's [state] as just verified, and the request's
     * signatures that are valid against it ([valid]). Made only by [SignSafeTx.check].
     */
    @ConsistentCopyVisibility
    data class Ready internal constructor(
        val request: SigningRequest,
        val owner: Address,
        val state: SafeState,
        val valid: List<SafeSignature.Ecdsa>,
    ) : SafeTxSignCheck

    /** Not to be signed: [refusal]. */
    data class Refused(val refusal: SafeTxRefusal) : SafeTxSignCheck
}

/** The outcome of [SignSafeTx]. */
sealed interface SafeTxSigning {

    /**
     * Signed: [request] carries the signature now, with those queued already; [saved] false when
     * the queue could not be saved (the request can still be shared, but is not kept).
     */
    data class Signed(val request: SigningRequest, val saved: Boolean) : SafeTxSigning

    /** Not signed, for the reason [signing] gives: nothing left the key store. */
    data class NotSigned(val signing: Signing) : SafeTxSigning

    /** Not signed: [refusal]. */
    data class Refused(val refusal: SafeTxRefusal) : SafeTxSigning
}

/**
 * Proposes a transfer from a Safe (docs/wallet-ux.md, screen 17): [amount] of [asset] to a
 * recipient, as a plain call with no refund ([SafeTx.call]) at the Safe's next nonce — its
 * verified nonce, or the first after the transactions queued for it from that nonce on without a
 * gap ([SafeTxQueue]): one queued further ahead, which cannot run before the gap is filled, moves
 * nothing, so a request imported at an absurd nonce cannot push every proposal behind it. Nothing
 * is proposed on less than verified state: the Safe's state ([ObserveSafe]) and its balance of
 * the asset, of which what the transactions queued before it may move (decoded from their raw
 * fields) is not this one's to move.
 *
 * Simulated where the engine allows: the Safe's call itself is run as a verified call from the
 * Safe's address at the head ([TransactionInputs.simulate]), and a revert, or a token's
 * `transfer` that returns false, stops it here. What is not simulated: the Safe's own checks
 * (its owners' signatures, which do not exist yet, and its nonce) and its guard, if it has one;
 * those run when its execution is quoted ([QuoteSafeExecution]), as `execTransaction` itself.
 * An engine that will not run a call from a contract's address leaves it not simulated, and the
 * proposal says so. No state override is used.
 *
 * The signatures held in lenient mode until their check ([Outbox]; docs/lenient-mode.md, *Safe
 * transactions in lenient mode*) take their nonces and their moves as the queued ones do: a held
 * proposal is in no queue until it checks out, and a proposal made meanwhile never takes its nonce
 * (one per Safe and nonce). An outbox that cannot be read holds none, as an unreadable queue does.
 *
 * Nothing is saved: signing it ([SignSafeTx]) queues it. On [network] only.
 */
class ProposeSafeTx(
    private val observeSafe: ObserveSafe,
    private val nativeBalances: NativeBalances,
    private val tokenBalances: TokenBalances,
    private val inputs: TransactionInputs,
    private val queue: SafeTxQueue,
    private val network: Network,
    private val outbox: Outbox,
) {
    /**
     * Proposes [amount] of [asset] from [safe] to [to]. [safeChecked] is called once the Safe is
     * verified ([ObserveSafe.check], which may wait out an unproven reading), before its balance,
     * its queue and the run are read: where a review measures the proposal's age from.
     */
    suspend operator fun invoke(
        safe: Address,
        to: Address,
        asset: TransferAsset,
        amount: UInt256,
        safeChecked: () -> Unit = {},
    ): SafeTxProposing =
        cancellingReads {
            val token = (asset as? TransferAsset.Erc20Token)?.token
            if (to == Address.ZERO) return@cancellingReads SafeTxProposing.ToZeroAddress
            if (token != null && to == token.address) return@cancellingReads SafeTxProposing.ToTokenContract
            if (to == safe) return@cancellingReads SafeTxProposing.ToSafeItself
            if (amount.isZero) return@cancellingReads SafeTxProposing.NothingToSend
            val txTo = token?.address ?: to
            val value = if (token == null) amount else UInt256.ZERO
            val data = if (token == null) ByteArray(0) else Erc20.transferData(to, amount)
            // The Safe first: its check may wait a while, and the balance and queue read after it are
            // as old as the proposal is said to be.
            val reading = when (val check = observeSafe.check(AccountId(network, safe))) {
                is SafeCheck.Verified -> check.reading
                is SafeCheck.Refused -> return@cancellingReads SafeTxProposing.Refused(check.refusal)
            }
            safeChecked()
            val balance = async { safeBalanceOf(network, nativeBalances, tokenBalances, safe, token?.address) }
            val queued = async { queue.observe(AccountId(network, safe)).first() }
            val held = async { outbox.observe().first() }

            val (before, balanceBlock) = when (val read = balance.await()) {
                is SafeBalanceRead.Read -> read.amount to read.blockNumber
                SafeBalanceRead.NotAToken -> return@cancellingReads SafeTxProposing.NotAToken
                is SafeBalanceRead.Refused -> return@cancellingReads SafeTxProposing.Refused(read.refusal)
            }
            if (before < amount) return@cancellingReads insufficient(token != null)
            val safeNonce = reading.state.nonce
            val pending = pendingOf(queued.await(), held.await(), safeNonce, AccountId(network, safe))
            val nonce = nextNonce(pending, safeNonce)
            val ahead = pending.filter { it.nonce < nonce }
            val queuedAmount = ahead.queuedMoves(token?.address) ?: return@cancellingReads insufficient(token != null)
            val available = before.minusOrNull(queuedAmount)?.takeIf { it >= amount }
                ?: return@cancellingReads insufficient(token != null)
            val simulation = when (val run = inputs.simulate(network, safe, txTo, value, data)) {
                is Simulation.Succeeded -> {
                    val refusedByToken = token != null && Erc20.returnedFalse(run.returnHex)
                    if (refusedByToken) return@cancellingReads SafeTxProposing.Reverts(run.blockNumber)
                    SafeCallSimulation.Ran(run.blockNumber)
                }
                is Simulation.Reverted -> return@cancellingReads SafeTxProposing.Reverts(run.blockNumber)
                Simulation.Refused -> SafeCallSimulation.NotRun
                is Simulation.Unavailable ->
                    return@cancellingReads SafeTxProposing.Refused(SafeTxRefusal.Unavailable(run.reason))
            }
            queue.prune(AccountId(network, safe), safeNonce)
            val request = SigningRequest.of(network, safe, SafeTx.call(txTo, value, data, nonce))
            SafeTxProposing.Proposed(
                SafeTxProposal(
                    tx = DecodedSafeTx.of(request),
                    safe = reading,
                    queuedAhead = (nonce - safeNonce).toInt(),
                    balanceBefore = before,
                    queuedAmount = queuedAmount,
                    balanceAfter = available - amount,
                    balanceBlock = balanceBlock,
                    simulation = simulation,
                ),
            )
        }

    private fun insufficient(token: Boolean) =
        if (token) SafeTxProposing.InsufficientToken else SafeTxProposing.InsufficientFunds
}

/**
 * The requests queued for a Safe on [network] that its nonce [safeNonce] has not passed. A queue
 * that cannot be read holds none: two transactions at one nonce are two proposals, only one of
 * which can ever run.
 */
internal fun pendingOf(queued: SafeTxQueueList, safeNonce: Long, network: Network): List<SigningRequest> =
    (queued as? SafeTxQueueList.Loaded)?.requests.orEmpty()
        .filter { it.network == network && it.nonce >= safeNonce }

/**
 * The requests of the signatures [list] holds for the Safe [safe] in lenient mode, each without its
 * signature: what takes a nonce and moves funds before a new proposal. Not a conflicted one: its
 * signature is deleted already, so it takes no nonce, and a signature held at its nonce takes its
 * place. None from an outbox that cannot be read.
 */
internal fun heldOf(list: OutboxList, safe: AccountId): List<SigningRequest> =
    (list as? OutboxList.Loaded)?.held.orEmpty().filter { it.safe == safe && it.signature != null }.map { it.request }

/**
 * The requests that run before a new proposal from the Safe [safe] at its nonce [safeNonce]: those
 * of its queue ([pendingOf]) and those of the signatures held for it in lenient mode ([heldOf]), each
 * at a nonce the Safe has not passed. Every proposer takes its nonce and its funds after them, so a
 * proposal never lands at the nonce of one held.
 */
internal fun pendingOf(
    queued: SafeTxQueueList,
    held: OutboxList,
    safeNonce: Long,
    safe: AccountId,
): List<SigningRequest> =
    pendingOf(queued, safeNonce, safe.network) + heldOf(held, safe).filter { it.nonce >= safeNonce }

/**
 * A Safe's next nonce: its own, or the first after the requests [pending] from it without a gap.
 * One queued further ahead, which cannot run before the gap is filled, does not push it back.
 */
internal fun nextNonce(pending: List<SigningRequest>, safeNonce: Long): Long {
    val taken = pending.mapTo(HashSet()) { it.nonce }
    var nonce = safeNonce
    while (nonce in taken) nonce++
    return nonce
}

/**
 * What these queued requests may move of the token at [token] (null: of the network's currency):
 * per nonce the most any of its alternatives moves, since only one transaction at a nonce ever
 * runs. Null when that sum overflows, which no balance covers.
 */
internal fun List<SigningRequest>.queuedMoves(token: Address?): UInt256? =
    groupBy { it.nonce }.values
        .map { alternatives -> alternatives.maxOf { it.moves(token) } }
        .sumOrNull()

/** What [safeBalanceOf] read: the balance, or why there is none to propose on. */
internal sealed interface SafeBalanceRead {
    data class Read(val amount: UInt256, val blockNumber: Long?) : SafeBalanceRead

    /** The token's address answers as no token. */
    data object NotAToken : SafeBalanceRead

    data class Refused(val refusal: SafeTxRefusal) : SafeBalanceRead
}

/**
 * [safe]'s verified balance of the token at [token], or of the network's currency (null). A
 * last-known balance is no verified one: nothing is proposed on it.
 */
internal suspend fun safeBalanceOf(
    network: Network,
    nativeBalances: NativeBalances,
    tokenBalances: TokenBalances,
    safe: Address,
    token: Address?,
): SafeBalanceRead {
    if (token == null) return safeNativeBalanceOf(network, nativeBalances, safe)
    val unproven = SafeBalanceRead.Refused(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN))
    return when (val reading = tokenBalances.observeBalance(network, safe, token).first()) {
        is TokenBalanceReading.Verified ->
            UInt256.parse(reading.units.decimal)?.let { SafeBalanceRead.Read(it, reading.blockNumber) } ?: unproven
        TokenBalanceReading.Syncing, is TokenBalanceReading.LastKnown -> SafeBalanceRead.Refused(SafeTxRefusal.Syncing)
        TokenBalanceReading.NotAToken -> SafeBalanceRead.NotAToken
        is TokenBalanceReading.Unavailable -> SafeBalanceRead.Refused(SafeTxRefusal.Unavailable(reading.reason))
    }
}

/** [safe]'s verified balance of the network's currency, as [safeBalanceOf] reads it. */
internal suspend fun safeNativeBalanceOf(
    network: Network,
    nativeBalances: NativeBalances,
    safe: Address,
): SafeBalanceRead {
    val unproven = SafeBalanceRead.Refused(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN))
    return when (val reading = nativeBalances.observeBalance(network, safe).first()) {
        is BalanceReading.Verified ->
            reading.wei.toUInt256OrNull()?.let { SafeBalanceRead.Read(it, reading.blockNumber) } ?: unproven
        BalanceReading.Syncing, is BalanceReading.LastKnown -> SafeBalanceRead.Refused(SafeTxRefusal.Syncing)
        is BalanceReading.Unavailable -> SafeBalanceRead.Refused(SafeTxRefusal.Unavailable(reading.reason))
    }
}

/**
 * What this queued request may move of the token at [token] (null: of the network's currency),
 * as decoded from its fields: a token's transfers of it, a swap's sale of it (the router takes
 * it), or for the currency the value of whatever it calls. A delegate call's value is not
 * sent, and what its code may do is not known here.
 */
internal fun SigningRequest.moves(token: Address?): UInt256 = when (val action = SafeTxAction.of(safe, tx)) {
    is SafeTxAction.TokenTransfer -> if (action.token == token) action.amount else UInt256.ZERO
    is SafeTxAction.Swap -> {
        val swap = action.swap
        // The router takes the route's first token unless it is the WETH9 it wraps the value into:
        // a sale of ETH moves the value, never the Safe's own WETH.
        when {
            !swap.sellsEther && swap.route.tokenIn == token -> swap.amountIn
            token == null -> tx.value
            else -> UInt256.ZERO
        }
    }
    is SafeTxAction.DelegateCall -> UInt256.ZERO
    else -> if (token == null) tx.value else UInt256.ZERO
}

/**
 * A Safe transfer proposed for review: [tx] at the Safe's next nonce, after [queuedAhead] nonces queued before it (two
 * transactions at one nonce, of which one runs, count once); the Safe's verified state ([safe]); its balance of the
 * asset ([balanceBefore], at [balanceBlock]), what those queued before it may move of it ([queuedAmount]: per nonce the
 * most of its alternatives, as only one of them runs) and what this one leaves ([balanceAfter]); and whether the Safe's
 * call was run as a verified call ([simulation]). Only [ProposeSafeTx] makes one.
 */
@ConsistentCopyVisibility
data class SafeTxProposal internal constructor(
    val tx: DecodedSafeTx,
    val safe: SafeReading.Verified,
    val queuedAhead: Int,
    val balanceBefore: UInt256,
    val queuedAmount: UInt256,
    val balanceAfter: UInt256,
    val balanceBlock: Long?,
    val simulation: SafeCallSimulation,
) {
    /** The request its owners sign. */
    val request: SigningRequest
        get() = tx.request
}

/** Whether a Safe's call was simulated as a verified call from the Safe. */
sealed interface SafeCallSimulation {

    /** It ran without reverting against the state of [blockNumber] (null: not said). */
    data class Ran(val blockNumber: Long?) : SafeCallSimulation

    /** The engine will not run a call from the Safe's address on this build: not simulated. */
    data object NotRun : SafeCallSimulation
}

/** The outcome of [ProposeSafeTx]. */
sealed interface SafeTxProposing {

    /** [proposal] is ready for review. */
    data class Proposed(val proposal: SafeTxProposal) : SafeTxProposing

    /** The recipient is the zero address: nobody holds its key, so the amount would be lost for good. */
    data object ToZeroAddress : SafeTxProposing

    /** A token sent to the token's own contract, from which nobody can move it out: refused. */
    data object ToTokenContract : SafeTxProposing

    /**
     * The recipient is the Safe itself: nothing would leave it, and the network's currency sent to
     * it would be a call to the Safe itself, which signing refuses ([SafeTxRefusal.UndecodedSelfCall]).
     * Refused here, before the review, for either asset.
     */
    data object ToSafeItself : SafeTxProposing

    /** The amount is zero: there is nothing to send. */
    data object NothingToSend : SafeTxProposing

    /** The Safe's balance of the network's currency, less what those queued before it may move, is below the amount. */
    data object InsufficientFunds : SafeTxProposing

    /** The Safe holds less of the token than the amount, less what those queued before it may move. */
    data object InsufficientToken : SafeTxProposing

    /** The token's address answers as no token. */
    data object NotAToken : SafeTxProposing

    /** The Safe's call reverts (or the token's `transfer` returns false), run from the Safe at [blockNumber]. */
    data class Reverts(val blockNumber: Long?) : SafeTxProposing

    /** Not proposed: the Safe is none, or its state or balance cannot be verified now ([refusal]). */
    data class Refused(val refusal: SafeTxRefusal) : SafeTxProposing
}

/**
 * The transactions queued for the Safe at [safe] (docs/wallet-ux.md, screen 16, Queue), each
 * decoded here from its raw fields, in nonce order. Once the Safe's state is verified, those whose
 * nonce it has passed are left out, so are the signatures that are not its current owners' or
 * do not recover to them ([SignatureRecovery]: what is shared from here is then what another
 * device accepts, whatever the queue file holds), and [SafeTxQueueReading.Loaded.safeNonce] says
 * its nonce. On [network] only.
 */
class ObserveSafeTxQueue(
    private val queue: SafeTxQueue,
    private val observeSafe: ObserveSafe,
    private val recovery: SignatureRecovery,
    private val network: Network,
) {
    operator fun invoke(safe: Address): Flow<SafeTxQueueReading> =
        combine(queue.observe(AccountId(network, safe)), observeSafe(AccountId(network, safe))) { list, reading ->
            when (list) {
                SafeTxQueueList.Unreadable -> SafeTxQueueReading.Unreadable
                is SafeTxQueueList.Loaded -> {
                    val state = (reading as? SafeReading.Verified)?.state
                    val pending = list.requests
                        .filter { it.network == network && (state == null || it.nonce >= state.nonce) }
                        .sortedBy { it.nonce }
                        // Checked again: an owner removed since, or a file changed under it, counts no more.
                        .map { request -> state?.let { recovery.validOnly(request, it.owners) } ?: request }
                    SafeTxQueueReading.Loaded(pending.map(DecodedSafeTx::of), state?.nonce)
                }
            }
        }
}

/** One answer of [ObserveSafeTxQueue]. */
sealed interface SafeTxQueueReading {

    /** The [transactions] queued, in nonce order; [safeNonce] the Safe's verified nonce (null: not verified now). */
    data class Loaded(val transactions: List<DecodedSafeTx>, val safeNonce: Long?) : SafeTxQueueReading

    /** The queue cannot be read right now. */
    data object Unreadable : SafeTxQueueReading
}

/**
 * Takes the transaction whose hash is [hash] out of this device's queue: a proposal given up.
 * Only here: a signature already shared stays valid elsewhere until the Safe's nonce passes it.
 * A signature of it held in lenient mode ([Outbox]) goes with it, both under [lock], so a check
 * running meanwhile never saves it back into the queue: it waits, and what it saved is removed.
 * Once asked, it runs to its end, its caller cancelled or not: the user confirmed it, and it may
 * wait for a check.
 */
class RemoveQueuedSafeTx(
    private val queue: SafeTxQueue,
    private val outbox: Outbox,
    private val lock: OutboxLock,
) {
    suspend operator fun invoke(hash: Digest): Boolean = withContext(NonCancellable) {
        lock.mutex.withLock {
            if (!queue.remove(hash)) return@withLock false
            // One that cannot be released now goes at its next check, which finds its request gone.
            (outbox.observe().first() as? OutboxList.Loaded)?.held
                ?.filter { it.hash == hash }
                ?.forEach { outbox.release(it.safe, hash) }
            true
        }
    }
}
