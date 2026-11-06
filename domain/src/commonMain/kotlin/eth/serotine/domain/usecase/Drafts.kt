package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.CowOrderList
import eth.serotine.domain.repository.CowOrders
import eth.serotine.domain.repository.DraftBasis
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.KeptTransactionInputs
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * A transfer drafted in lenient mode for review (docs/lenient-mode.md, *The draft's review*): the
 * unsigned [transaction] that moves [amount] of [asset] from [from] to [to] at [level]'s fees, built
 * on the state the light client verified last ([builtOn]), each figure as old as it says. The most
 * its fee can be is [maxFee] (the pinned gas ceiling times the fee cap); [nativeAfterAtLeast] and
 * [tokenAfter] are what the balances would be after it if nothing changed since, the account's own
 * transactions not mined yet ([pendingCost], [pendingTokenAmount]) and its CoW orders
 * ([heldByOrders]) counted. Nothing in it was simulated: the check before sending runs it.
 *
 * Only [DraftTransfer] makes one, and nothing copies it: what [QueueTransfer] signs is its
 * [transaction], so a display field changed beside it would show one transfer and sign another.
 */
@ConsistentCopyVisibility
data class TransferDraft internal constructor(
    val network: Network,
    val from: Address,
    val to: Address,
    val asset: TransferAsset,
    val amount: UInt256,
    val level: FeeLevel,
    val transaction: Eip1559Transaction,
    val maxFee: UInt256,
    val nativeBefore: UInt256,
    val pendingCost: UInt256,
    val nativeAfterAtLeast: UInt256,
    val tokenBefore: UInt256?,
    val pendingTokenAmount: UInt256?,
    val heldByOrders: UInt256?,
    val tokenAfter: UInt256?,
    val builtOn: DraftBasis,
) {
    /** The most it can take of the native balance: its value and the most its fee can be. */
    val maxCost: UInt256
        get() = maxFee.plusOrNull(transaction.value) ?: maxFee
}

/** The outcome of [DraftTransfer]. */
sealed interface TransferDrafting {

    /** [draft] is ready for review. */
    data class Drafted(val draft: TransferDraft) : TransferDrafting

    /** Refused as a strict quote refuses it ([quoting]: one of [TransferQuoting]'s refusals, never Quoted). */
    data class Refused(val quoting: TransferQuoting) : TransferDrafting

    /**
     * The draft waits for [missing], which the light client has never verified on this device:
     * the account's balance, its nonce or the network's fees. It is said, as a strict quote says it
     * syncs, and the review waits for the light client.
     */
    data class Waits(val missing: DraftInput) : TransferDrafting

    /**
     * Something is queued from the account already ([queued]: its [QueuedTransaction.id]): one per
     * account (docs/lenient-mode.md, decision 6). It is sent or discarded first.
     */
    data class AccountBusy(val queued: String) : TransferDrafting
}

/** What a draft needs that the light client never verified on this device ([TransferDrafting.Waits]). */
enum class DraftInput {
    /** The balance of the network's currency, or of the token sent. */
    BALANCE,

    /** The account's count of mined transactions. */
    NONCE,

    /** The network's fees. */
    FEES,
}

/**
 * The gas a lenient draft signs for, in place of an estimate, which needs fresh state
 * (docs/lenient-mode.md, *What a draft is built on*): one ceiling per kind of call, the same on
 * every network. Only the gas used is paid; the ceiling shows in the most the fee can be, and the
 * fresh estimate must fit within it at the check, or the user signs again.
 *
 * Pinned from the EVM's gas schedule with room to spare, not measured on a network: a transfer of
 * the network's currency to an account without code takes 21000; to a Safe proxy, whose fallback
 * delegates to its singleton (a cold storage read and a cold call, 2100 + 2600) and logs
 * `SafeReceived`, about 27000. An ERC-20 `transfer` to a holder without a balance yet, its costliest
 * common case, takes about 65000 on the tokens the wallet lists, a proxied one (USDC) included.
 */
object DraftGas {
    /** For the network's currency: room for a Safe or another contract with a light `receive` as the recipient. */
    const val NATIVE: Long = 50_000

    /** For a token's `transfer`: room for a new holder's balance slot and a proxied token. */
    const val TOKEN: Long = 100_000

    /** The ceiling for [asset]. */
    fun ceiling(asset: TransferAsset): Long = if (asset is TransferAsset.Erc20Token) TOKEN else NATIVE
}

/**
 * Drafts a transfer in lenient mode (docs/lenient-mode.md, *What a draft is built on*): on the
 * balances, the mined count and the fee quote the light client verified last on this device, never
 * on anything it did not verify. A balance verified now is used as it is; one last verified, with
 * its age. The account's own transactions not mined yet ([SentTransfers]) count as in a strict
 * quote: the nonce is past them, and what they may still take is not this one's. So do its CoW
 * orders, every one not proven done (none can be while the light client catches up).
 *
 * The recipient is an address: no name is resolved for a draft (decision 8). Refused before
 * anything is read: the zero address, the token's own contract, nothing to send, and an account
 * that has something queued already (one per account, decision 6), every account while the outbox
 * cannot be read (it may hold one), or an earlier transaction
 * maybe sent within its lifetime, past the kept mined count. A draft is never simulated and never
 * signed here: [QueueTransfer] signs it; the check before sending reads everything again. [clock]
 * is in unix seconds.
 */
class DraftTransfer(
    private val kept: KeptTransactionInputs,
    private val nativeBalances: NativeBalances,
    private val tokenBalances: TokenBalances,
    private val sentTransfers: SentTransfers,
    private val cowOrders: CowOrders,
    private val cowChain: CowChain,
    private val outbox: Outbox,
    private val network: Network,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    /** [amount] of [asset] from [from] to [to], at [level]'s fees. */
    suspend operator fun invoke(
        from: Address,
        to: Address,
        asset: TransferAsset,
        amount: UInt256,
        level: FeeLevel,
    ): TransferDrafting {
        val token = (asset as? TransferAsset.Erc20Token)?.token
        if (to == Address.ZERO) return refused(TransferQuoting.ToZeroAddress)
        if (token != null && to == token.address) return refused(TransferQuoting.ToTokenContract)
        if (amount.isZero) return refused(TransferQuoting.NothingToSend)
        when (val list = outbox.observe().first()) {
            // It may hold a transaction already broadcast whose record is not written yet.
            OutboxList.Unreadable ->
                return refused(TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.NONCE))
            is OutboxList.Loaded -> list.queued.firstOrNull { it.network == network && it.account == from }
                ?.let { return TransferDrafting.AccountBusy(it.id) }
        }

        val nonce = kept.minedNonce(network, from) ?: return TransferDrafting.Waits(DraftInput.NONCE)
        val fees = kept.fees(network) ?: return TransferDrafting.Waits(DraftInput.FEES)
        val since = clock() - RECORD_LIFETIME_SECONDS
        val records = (sentTransfers.observe().first() as? SentTransferList.Loaded)?.transfers.orEmpty()
            .filter { it.network == network && it.account == from && it.sentAt >= since && it.nonce >= nonce.count }
        records.firstOrNull { it.maybeSent }?.let { return refused(TransferQuoting.EarlierMaybeSent(it.hash)) }
        val unmined = records.filter { !it.maybeSent }

        val native = when (val reading = nativeBalances.observeBalance(network, from).first()) {
            is BalanceReading.Verified -> Read(reading.wei.toUInt256OrNull(), reading.blockNumber, reading.provenAt)
            is BalanceReading.LastKnown -> Read(reading.wei.toUInt256OrNull(), reading.blockNumber, reading.asOf)
            BalanceReading.Syncing -> return TransferDrafting.Waits(DraftInput.BALANCE)
            is BalanceReading.Unavailable ->
                return refused(TransferQuoting.Unavailable(reading.reason, QuoteFigure.BALANCE))
        }
        val nativeBefore = native.amount ?: return refused(UNPROVEN)
        val tokenRead = token?.let {
            when (val reading = tokenBalances.observeBalance(network, from, it.address).first()) {
                is TokenBalanceReading.Verified ->
                    Read(UInt256.parse(reading.units.decimal), reading.blockNumber, reading.provenAt)
                is TokenBalanceReading.LastKnown ->
                    Read(UInt256.parse(reading.units.decimal), reading.blockNumber, reading.asOf)
                TokenBalanceReading.Syncing -> return TransferDrafting.Waits(DraftInput.BALANCE)
                TokenBalanceReading.NotAToken -> return refused(TransferQuoting.NotAToken)
                is TokenBalanceReading.Unavailable ->
                    return refused(TransferQuoting.Unavailable(reading.reason, QuoteFigure.BALANCE))
            }
        }
        val tokenBefore = tokenRead?.let { it.amount ?: return refused(UNPROVEN) }
        if (tokenBefore != null && tokenBefore < amount) return refused(TransferQuoting.InsufficientToken)

        val gas = DraftGas.ceiling(asset)
        val cap = fees.quote.maxFeePerGasOrNull(level) ?: return refused(FEES_UNPROVEN)
        val maxFee = cap.timesOrNull(UInt256.of(gas)) ?: return refused(FEES_UNPROVEN)
        val value = if (token == null) amount else UInt256.ZERO
        val cost = maxFee.plusOrNull(value) ?: return refused(TransferQuoting.InsufficientFunds)
        val pendingCost = unmined.map { it.maxCost }.sumOrNull() ?: return refused(TransferQuoting.InsufficientFunds)
        val available = nativeBefore.minusOrNull(pendingCost) ?: return refused(TransferQuoting.InsufficientFunds)
        if (available < cost) return refused(TransferQuoting.InsufficientFunds)

        val pendingToken = token?.let { sending ->
            unmined.mapNotNull { it.tokenAmount(sending.address) }.sumOrNull()
                ?: return refused(TransferQuoting.InsufficientToken)
        }
        val held = token?.let { sending ->
            val orders = (cowOrders.observe().first() as? CowOrderList.Loaded)?.orders.orEmpty()
            // No finalized block is read for a draft: every order not proven done is held.
            orders.holding(network, from, sending.address, finalized = null, cowChain)
                ?: return refused(TransferQuoting.InsufficientToken)
        }
        val tokenAvailable = tokenBefore?.let { before ->
            before.minusOrNull(pendingToken ?: UInt256.ZERO)
                ?.minusOrNull(held ?: UInt256.ZERO)
                ?.takeIf { it >= amount }
                ?: return refused(TransferQuoting.InsufficientToken)
        }
        val next = maxOf(nonce.count, (unmined.maxOfOrNull { it.nonce } ?: -1) + 1)
        val transaction = Eip1559Transaction(
            chainId = network.chainId,
            nonce = next,
            maxPriorityFeePerGas = fees.quote.tip(level),
            maxFeePerGas = cap,
            gasLimit = gas,
            to = token?.address ?: to,
            value = value,
            data = if (token == null) ByteArray(0) else Erc20.transferData(to, amount),
        )
        return TransferDrafting.Drafted(
            TransferDraft(
                network = network,
                from = from,
                to = to,
                asset = asset,
                amount = amount,
                level = level,
                transaction = transaction,
                maxFee = maxFee,
                nativeBefore = nativeBefore,
                pendingCost = pendingCost,
                nativeAfterAtLeast = available - cost,
                tokenBefore = tokenBefore,
                pendingTokenAmount = pendingToken,
                heldByOrders = held,
                tokenAfter = tokenAvailable?.let { it - amount },
                builtOn = DraftBasis(
                    balanceBlock = native.block,
                    balanceAsOf = native.asOf,
                    tokenBalanceBlock = tokenRead?.block,
                    tokenBalanceAsOf = tokenRead?.asOf,
                    nonceBlock = nonce.blockNumber,
                    nonceAsOf = nonce.asOf,
                    feeBlock = fees.quote.blockNumber,
                    feeAsOf = fees.asOf,
                ),
            ),
        )
    }

    /**
     * The most of the network's currency a draft from [from] at [level] can send (lenient Max,
     * docs/lenient-mode.md, *What a draft is built on*): the balance last verified, less the most
     * the fee can be at the pinned gas ceiling and the kept fee cap, less what the account's
     * transactions not mined yet may still take. Zero when that leaves nothing; null when it
     * cannot be told (no balance, nonce or fee quote verified on this device, or one that does
     * not fit 256 bits). Strict Max is not this: it is the whole verified balance.
     */
    suspend fun mostNative(from: Address, level: FeeLevel): UInt256? {
        val nonce = kept.minedNonce(network, from) ?: return null
        val fees = kept.fees(network) ?: return null
        val balance = when (val reading = nativeBalances.observeBalance(network, from).first()) {
            is BalanceReading.Verified -> reading.wei.toUInt256OrNull()
            is BalanceReading.LastKnown -> reading.wei.toUInt256OrNull()
            BalanceReading.Syncing, is BalanceReading.Unavailable -> null
        } ?: return null
        val since = clock() - RECORD_LIFETIME_SECONDS
        val pending = (sentTransfers.observe().first() as? SentTransferList.Loaded)?.transfers.orEmpty()
            .filter { it.network == network && it.account == from && it.sentAt >= since && it.nonce >= nonce.count }
            .filter { !it.maybeSent }
            .map { it.maxCost }
            .sumOrNull() ?: return null
        val maxFee = fees.quote.maxFeePerGasOrNull(level)?.timesOrNull(UInt256.of(DraftGas.NATIVE)) ?: return null
        val reserved = maxFee.plusOrNull(pending) ?: return null
        return balance.minusOrNull(reserved) ?: UInt256.ZERO
    }


    /** A balance as read: [amount] (null: not a uint256), at [block], a head of [asOf]. */
    private class Read(val amount: UInt256?, val block: Long?, val asOf: Long?)

    private fun refused(quoting: TransferQuoting) = TransferDrafting.Refused(quoting)

    private companion object {
        val UNPROVEN = TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.BALANCE)
        val FEES_UNPROVEN = TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.FEES)
    }
}

/**
 * Signs a reviewed [TransferDraft] and queues it in the outbox (docs/lenient-mode.md, *The
 * outbox*): its EIP-1559 signing digest signed by the sender's key as [unlock] opens it
 * ([Signer]), exactly what was drafted and reviewed. Nothing is broadcast: the outbox sends it once
 * the check at a fresh head passes ([CheckQueued]). Queued in place of nothing: an account with
 * something queued already is refused before anything is signed, and again under [lock] before it
 * is put, so nothing the follower or a discard does meanwhile is overwritten. [clock] is in unix
 * seconds.
 */
class QueueTransfer(
    private val signer: Signer,
    private val outbox: Outbox,
    private val lock: OutboxLock,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    /** Signs [draft] and queues it. */
    suspend operator fun invoke(draft: TransferDraft, unlock: Unlock): TransferQueuing {
        busy(draft)?.let { return TransferQueuing.AccountBusy(it.id) }
        val transaction = draft.transaction
        val signature = when (val signed = signer.sign(draft.from, transaction.signingDigest(), unlock)) {
            is Signing.Signed -> signed.signature
            else -> return TransferQueuing.NotSigned(signed)
        }
        val signed = SignedTransfer(transaction.encodeSigned(signature))
        val queued = QueuedTransaction(
            network = draft.network,
            account = draft.from,
            id = signed.hash,
            action = SentAction.Transfer(draft.to, draft.asset, draft.amount),
            level = draft.level,
            transaction = transaction,
            signed = signed,
            maxCost = draft.maxCost,
            signedAt = clock(),
            builtOn = draft.builtOn,
        )
        return lock.mutex.withLock {
            busy(draft)?.let { return@withLock TransferQueuing.AccountBusy(it.id) }
            if (outbox.put(queued)) TransferQueuing.Queued(queued) else TransferQueuing.NotSaved
        }
    }

    private suspend fun busy(draft: TransferDraft): QueuedTransaction? =
        (outbox.observe().first() as? OutboxList.Loaded)?.queued
            ?.firstOrNull { it.network == draft.network && it.account == draft.from }
}

/** The outcome of [QueueTransfer]. */
sealed interface TransferQueuing {

    /** Signed and queued as [queued]; the outbox sends it once its check passes. */
    data class Queued(val queued: QueuedTransaction) : TransferQueuing

    /** Not signed, for the reason [signing] gives: nothing was queued. */
    data class NotSigned(val signing: Signing) : TransferQueuing

    /** Something from the account was queued meanwhile ([queued]: its [QueuedTransaction.id]). */
    data class AccountBusy(val queued: String) : TransferQueuing

    /** Signed, but the outbox could not save it: nothing was queued, and the signature is dropped. */
    data object NotSaved : TransferQueuing
}
