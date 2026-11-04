package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.hasValidChecksum
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.CowOrderList
import eth.serotine.domain.repository.CowOrders
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionHistory
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The recipient of a transfer as the user typed it (docs/wallet-ux.md, screen 7): an address,
 * whose EIP-55 checksum must hold when it has one, or an ENS name, which [NameResolver] resolves
 * against finalized state only, on a network that has names ([Network.ensNames]). This is the
 * one use case that takes the typed text, so the checksum is checked here for every screen that
 * sends.
 */
class ResolveRecipient(private val names: NameResolver) {

    /** What [input] names on [network], trimmed: an address as is, anything with a dot as an ENS name. */
    suspend operator fun invoke(network: Network, input: String): RecipientLookup {
        val text = input.trim()
        Address.parse(text)?.let { address ->
            if (!hasValidChecksum(text)) return RecipientLookup.ChecksumMismatch
            if (address == Address.ZERO) return RecipientLookup.ZeroAddress
            return RecipientLookup.Found(address, name = null, blockNumber = null)
        }
        // A name before the 0x test: "0xfoo.eth" is a name.
        if ('.' !in text) return RecipientLookup.NotAnAddress
        if (!network.ensNames) return RecipientLookup.NoNamesOn(network)
        return when (val resolved = names.resolve(network, text)) {
            // ENS's zero address is a name with no address set, not a recipient.
            is NameResolution.Resolved -> if (resolved.address == Address.ZERO) {
                RecipientLookup.NoAddress
            } else {
                RecipientLookup.Found(resolved.address, resolved.name, resolved.blockNumber)
            }
            NameResolution.NoAddress -> RecipientLookup.NoAddress
            NameResolution.NotAName -> RecipientLookup.NotAnAddress
            NameResolution.OffchainOnly -> RecipientLookup.OffchainOnly
            is NameResolution.Unavailable -> RecipientLookup.Unavailable(resolved.reason)
        }
    }
}

/** The outcome of [ResolveRecipient]. */
sealed interface RecipientLookup {

    /** [address] is the recipient; [name] the ENS name it was resolved from, at [blockNumber]. */
    data class Found(val address: Address, val name: String?, val blockNumber: Long?) : RecipientLookup

    /** Neither an address nor an ENS name. */
    data object NotAnAddress : RecipientLookup

    /** An address in mixed case whose EIP-55 checksum fails: a typo, refused. */
    data object ChecksumMismatch : RecipientLookup

    /** The zero address: what is sent to it is lost for good, so it is refused. */
    data object ZeroAddress : RecipientLookup

    /**
     * A name whose address only an off-chain gateway (CCIP-Read) gives: an off-chain source no
     * ruling allows, so it is not resolvable here (docs/wallet-ux.md, screen 7).
     */
    data object OffchainOnly : RecipientLookup

    /** An ENS name without an address. */
    data object NoAddress : RecipientLookup

    /** A name typed on [network], where names are not resolved ([Network.ensNames]): refused, never asked. */
    data class NoNamesOn(val network: Network) : RecipientLookup

    /** The name could not be resolved verified right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : RecipientLookup
}

/**
 * A transfer quoted for review (docs/wallet-ux.md, screen 8): the unsigned [transaction] that
 * moves [amount] of [asset] from [from] to [to] at [level]'s fees, the most its fee can be
 * ([maxFee], gas limit × fee cap), and the balances it leaves — [nativeBefore], what this
 * wallet's own transactions not mined yet may still take of it ([pendingCost]), the least the
 * native balance can be after ([nativeAfterAtLeast]), and for a token its balance before, what
 * this wallet's transfers and swaps of it not mined yet still move ([pendingTokenAmount]), what
 * its CoW orders may still take of it ([heldByOrders]) and its balance after, all of them counted.
 * Every figure comes from verified state, each at its own block: the native balance at
 * [balanceBlock], the token's at [tokenBalanceBlock], the fees from the history up to [feeBlock],
 * and the transfer run as a verified call from the sender at [simulationBlock].
 *
 * Only [QuoteTransfer] makes one, and nothing copies it: what [SendTransfer] signs is its
 * [transaction], so a display field changed beside it would show one transfer and sign another.
 * A quote ages (its nonce, its fees): the screen that shows it quotes again rather than keeping
 * one for long.
 */
@ConsistentCopyVisibility
data class TransferQuote internal constructor(
    val network: Network,
    val from: Address,
    val to: Address,
    val asset: TransferAsset,
    val amount: UInt256,
    val level: FeeLevel,
    val transaction: Eip1559Transaction,
    val maxFee: UInt256,
    val nativeBefore: UInt256,
    val nativeAfterAtLeast: UInt256,
    val pendingCost: UInt256,
    val tokenBefore: UInt256?,
    val pendingTokenAmount: UInt256?,
    val heldByOrders: UInt256?,
    val tokenAfter: UInt256?,
    val balanceBlock: Long?,
    val tokenBalanceBlock: Long?,
    val feeBlock: Long,
    val simulationBlock: Long?,
) {
    /** The quote of the transaction itself, which [SendTransfer] signs. */
    internal val call: CallQuote
        get() = CallQuote(
            from = from,
            level = level,
            transaction = transaction,
            maxFee = maxFee,
            nativeBefore = nativeBefore,
            pendingCost = pendingCost,
            nativeAfterAtLeast = nativeAfterAtLeast,
            balanceBlock = balanceBlock,
            feeBlock = feeBlock,
            simulationBlock = simulationBlock,
        )
}

/**
 * Quotes a transfer: the balances it needs, and the transaction that makes it, quoted as every
 * transaction from an account this wallet holds is ([CallQuoter]: the sender's next nonce
 * behind its own transactions not mined yet, the fees at [FeeLevel], the gas it takes, and the
 * transfer run as a verified call from the sender, which must not revert). Nothing is quoted on
 * anything less than verified state, and an amount is in the asset's smallest unit: prices never
 * reach a transaction (CLAUDE.md, *Data sources*). On [network] only, its chain id signed into
 * the transaction.
 *
 * What the sender's transfers and swaps of the same token not mined yet still move
 * ([SentTransfers]) is not this one's to move either, nor what its CoW orders may still take of it
 * ([CowOrders], until a finalized block is past an order's `validTo`, or the settlement reads the
 * order done at the head, [CowChain]). A record of orders that
 * cannot be read holds nothing back here, as an unreadable record of transfers holds none: a
 * transfer swaps nothing, and an order whose tokens are gone cannot fill. [clock] is in unix
 * seconds.
 */
class QuoteTransfer(
    private val inputs: TransactionInputs,
    private val nativeBalances: NativeBalances,
    private val tokenBalances: TokenBalances,
    private val sentTransfers: SentTransfers,
    private val cowOrders: CowOrders,
    private val cowChain: CowChain,
    private val lightClient: LightClient,
    private val network: Network,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock)

    /** [amount] of [asset] from [from] to [to], at [level]'s fees. */
    suspend operator fun invoke(
        from: Address,
        to: Address,
        asset: TransferAsset,
        amount: UInt256,
        level: FeeLevel,
    ): TransferQuoting = cancellingReads {
        val token = (asset as? TransferAsset.Erc20Token)?.token
        // Recipients that can never give it back, refused here as well as at the typed text: this
        // is the one maker of quotes, whichever way the recipient was chosen.
        if (to == Address.ZERO) return@cancellingReads TransferQuoting.ToZeroAddress
        if (token != null && to == token.address) return@cancellingReads TransferQuoting.ToTokenContract
        // Nothing to move is nothing to pay a fee for.
        if (amount.isZero) return@cancellingReads TransferQuoting.NothingToSend
        val txTo = token?.address ?: to
        val value = if (token == null) amount else UInt256.ZERO
        val data = if (token == null) ByteArray(0) else Erc20.transferData(to, amount)
        // An earlier transfer maybe still on its way: refused before any balance is read.
        quoter.heldBack(from)?.let { return@cancellingReads it.asTransferQuoting() }
        val native = async { nativeBalances.observeBalance(network, from).first() }
        val tokenBalance =
            token?.let { async { tokenBalances.observeBalance(network, from, it.address).first() } }

        val nativeBefore = when (val read = NativeRead.of(native.await())) {
            is NativeRead.Before -> read
            is NativeRead.NotRead -> return@cancellingReads read.refusal.asTransferQuoting()
        }
        var tokenBlock: Long? = null
        val tokenBefore = when (val reading = tokenBalance?.await()) {
            null -> null
            is TokenBalanceReading.Verified -> {
                tokenBlock = reading.blockNumber
                UInt256.parse(reading.units.decimal) ?: return@cancellingReads UNPROVEN
            }
            TokenBalanceReading.Syncing, is TokenBalanceReading.LastKnown ->
                return@cancellingReads TransferQuoting.Syncing
            TokenBalanceReading.NotAToken -> return@cancellingReads TransferQuoting.NotAToken
            is TokenBalanceReading.Unavailable ->
                return@cancellingReads TransferQuoting.Unavailable(reading.reason, QuoteFigure.BALANCE)
        }
        if (tokenBefore != null && tokenBefore < amount) return@cancellingReads TransferQuoting.InsufficientToken
        if (token == null && nativeBefore.wei < amount) return@cancellingReads TransferQuoting.InsufficientFunds
        // A token that returns false refused the transfer as surely as one that reverts.
        val accepts: (String) -> Boolean = { returned ->
            token == null || returned.isEmpty() || returned.any { it != '0' }
        }
        val (call, unmined) = when (val quoting = quoter.quote(from, txTo, value, data, level, nativeBefore, accepts)) {
            is CallQuoting.Quoted -> quoting
            is CallQuoting.Refused -> return@cancellingReads quoting.refusal.asTransferQuoting()
        }
        val pendingToken = token?.let { sending ->
            unmined.mapNotNull { it.tokenAmount(sending.address) }.sumOrNull()
                ?: return@cancellingReads TransferQuoting.InsufficientToken
        }
        val held = token?.let { sending ->
            val orders = (cowOrders.observe().first() as? CowOrderList.Loaded)?.orders.orEmpty()
            val finalized = (lightClient.observeStatus(network).first() as? LightClientStatus.Synced)?.finalized
            orders.holding(network, from, sending.address, finalized, cowChain)
                ?: return@cancellingReads TransferQuoting.InsufficientToken
        }
        // What the token's earlier transfers move is not this one's to move: kept, the later one
        // would pass the run at the head and revert once they are mined, its fee paid all the same.
        val tokenAvailable = tokenBefore?.let { before ->
            before.minusOrNull(pendingToken ?: UInt256.ZERO)
                ?.minusOrNull(held ?: UInt256.ZERO)
                ?.takeIf { it >= amount }
                ?: return@cancellingReads TransferQuoting.InsufficientToken
        }
        TransferQuoting.Quoted(
            TransferQuote(
                network = network,
                from = from,
                to = to,
                asset = asset,
                amount = amount,
                level = level,
                transaction = call.transaction,
                maxFee = call.maxFee,
                nativeBefore = call.nativeBefore,
                nativeAfterAtLeast = call.nativeAfterAtLeast,
                pendingCost = call.pendingCost,
                tokenBefore = tokenBefore,
                pendingTokenAmount = pendingToken,
                heldByOrders = held,
                tokenAfter = tokenAvailable?.let { it - amount },
                balanceBlock = call.balanceBlock,
                tokenBalanceBlock = tokenBlock,
                feeBlock = call.feeBlock,
                simulationBlock = call.simulationBlock,
            ),
        )
    }
}

private val UNPROVEN = TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.BALANCE)

/**
 * What this transaction moves of the token at [token] out of the account: a transfer's amount, or
 * what a swap sells, of that token.
 */
internal fun SentTransfer.tokenAmount(token: Address): UInt256? = when (val action = action) {
    is SentAction.Transfer -> action.amount.takeIf { action.asset.isToken(token) }
    is SentAction.Swap -> action.amountIn.takeIf { action.sell.isToken(token) }
    else -> null
}

private fun TransferAsset.isToken(token: Address): Boolean =
    (this as? TransferAsset.Erc20Token)?.token?.address == token

/** This refusal of the transaction as the transfer's. */
private fun CallRefusal.asTransferQuoting(): TransferQuoting = when (this) {
    CallRefusal.InsufficientFunds -> TransferQuoting.InsufficientFunds
    is CallRefusal.Infeasible -> TransferQuoting.Infeasible(detail)
    is CallRefusal.Reverts -> TransferQuoting.Reverts(blockNumber)
    CallRefusal.Refused -> TransferQuoting.Refused
    CallRefusal.Syncing -> TransferQuoting.Syncing
    is CallRefusal.Unavailable -> TransferQuoting.Unavailable(reason, figure)
    is CallRefusal.EarlierMaybeSent -> TransferQuoting.EarlierMaybeSent(hash)
}

/** The outcome of [QuoteTransfer]. */
sealed interface TransferQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: TransferQuote) : TransferQuoting

    /** The recipient is the zero address: nobody holds its key, so the amount would be lost for good. */
    data object ToZeroAddress : TransferQuoting

    /**
     * A token sent to the token's own contract: most tokens accept it and nobody can move it out
     * again, so it is refused.
     */
    data object ToTokenContract : TransferQuoting

    /** The amount is zero: there is nothing to send, and no fee is worth paying for it. */
    data object NothingToSend : TransferQuoting

    /** The sender's balance of the network's currency cannot pay the amount and the most the fee can be. */
    data object InsufficientFunds : TransferQuoting

    /** The sender holds less of the token than the amount. */
    data object InsufficientToken : TransferQuoting

    /** It cannot succeed for a reason other than the sender's funds ([detail], the EVM's words). */
    data class Infeasible(val detail: String) : TransferQuoting

    /** The transfer reverts, run as a verified call from the sender (at [blockNumber]): nothing is signed. */
    data class Reverts(val blockNumber: Long?) : TransferQuoting

    /** The token's address answers as no token. */
    data object NotAToken : TransferQuoting

    /** The engine will not estimate or run it on this build. */
    data object Refused : TransferQuoting

    /** The light client has no verified head yet. */
    data object Syncing : TransferQuoting

    /** A verified figure could not be had right now, for [reason]; [figure] says which (null: not said). */
    data class Unavailable(val reason: ReadUnavailable, val figure: QuoteFigure? = null) : TransferQuoting

    /**
     * An earlier transaction from the sender, [hash], may still be on its way
     * ([CallRefusal.EarlierMaybeSent]): it is retried from its status, not replaced by this one.
     */
    data class EarlierMaybeSent(val hash: String) : TransferQuoting
}

/**
 * Signs a reviewed [TransferQuote] and hands it to the network: the transaction's EIP-1559
 * signing digest is signed by the sender's key as [unlock] opens it ([Signer]), and the signed
 * bytes are broadcast. What is signed is exactly what was quoted and reviewed. [clock] is in
 * unix seconds.
 */
class SendTransfer(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val clock: () -> Long = ::epochSecondsNow,
) {

    /**
     * Signs and broadcasts [quote]'s transaction. Once a peer took it, it is recorded
     * ([SentTransfers]): what keeps its nonce from a later transfer after a restart, and what its
     * status shows. One no peer took is not recorded, so it holds no nonce: a later quote may take
     * the same one, and only one of the two can ever be mined. The broadcast and the record are one
     * step a cancellation cannot cut in half; a record that cannot be saved does not undo the send.
     */
    suspend operator fun invoke(quote: TransferQuote, unlock: Unlock): TransferSending = signAndSend(
        signer,
        broadcaster,
        sentTransfers,
        quote.network,
        quote.call,
        SentAction.Transfer(quote.to, quote.asset, quote.amount),
        clock,
        unlock,
    )
}

/**
 * Broadcasts a transaction signed but not sent again, byte for byte: what one is retried with,
 * never a new quote, which could take the next nonce while the first one is still on its way
 * and pay twice. A retry refused as rejected may be the first broadcast's own doing: no peer
 * answered then, yet one took it, and a block has since used its nonce. So a rejection is
 * checked against the transaction's verified receipt ([receipts]) before it is believed: a
 * transaction already in a block is sent, one whose receipt cannot be verified right now is
 * [TransferSending.RejectionUnverified]. A transfer's, a Safe transaction's approval or
 * execution, or a Safe's deployment alike. [clock] is in unix seconds.
 *
 * Its record follows the outcome ([SentTransfers]): one a peer took is recorded as sent, and one
 * no peer answered again stays recorded as maybe sent ([SentTransfer.maybeSent]), from this
 * broadcast on. One turned away (rejected with no receipt, or refused) keeps its record as it
 * was: the rejection is the peer's word on this retry, and says nothing sure of the first
 * broadcast ("already known", "replacement underpriced", or a nonce used by a block past the
 * light client's head all come back so), and a deployment's record is the only copy of its Safe's
 * owners and name. Its lifetime and [SentTransfers.prune] bound it.
 */
class RebroadcastTransfer(
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val receipts: ReceiptTracker,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(transfer: SentTransfer): TransferSending = withContext(NonCancellable) {
        when (val broadcast = broadcaster.send(transfer.network, transfer.signed.raw)) {
            is Broadcast.Sent -> sent(sentTransfers, transfer, clock)
            is Broadcast.Rejected -> when (receipts.observe(transfer.network, transfer.hash).first()) {
                is TransactionProgress.Included, is TransactionProgress.Finalized ->
                    sent(sentTransfers, transfer, clock)
                // No receipt at the head: turned away this time, its record kept as it was.
                TransactionProgress.Pending -> TransferSending.NotSent(broadcast, transfer)
                is TransactionProgress.Unavailable -> TransferSending.RejectionUnverified(broadcast, transfer)
            }
            is Broadcast.Unavailable -> maybeSent(sentTransfers, broadcast, transfer, clock)
            is Broadcast.Refused -> TransferSending.NotSent(broadcast, transfer)
        }
    }
}

/**
 * The outcome of [SendTransfer], and of every other transaction this wallet signs and sends
 * ([ApproveSafeTxOnChain], [ExecuteSafeTx], [DeploySafe]).
 */
sealed interface TransferSending {

    /** Broadcast; [hash] is its transaction hash. */
    data class Sent(val hash: String) : TransferSending

    /** Not signed, for the reason [signing] gives: nothing left the device. */
    data class NotSigned(val signing: Signing) : TransferSending

    /**
     * Signed but not broadcast, for the reason [broadcast] gives. [transfer], its signed bytes
     * included, is kept for a retry ([RebroadcastTransfer]); an unavailable broadcast may have
     * reached a peer all the same, so it is that retry, not a new quote, that sends it again.
     */
    data class NotSent(val broadcast: Broadcast, val transfer: SentTransfer) : TransferSending {
        /** The signed bytes a retry sends. */
        val signed: SignedTransfer
            get() = transfer.signed
    }

    /**
     * A retry [rejection] that could not be checked: whether the first broadcast reached a block
     * after all, the transfer's receipt could not be verified right now. Neither verdict is known,
     * so [transfer] may well be in a block: a retry asks both again, and nothing new is to be
     * signed for it meanwhile.
     */
    data class RejectionUnverified(val rejection: Broadcast.Rejected, val transfer: SentTransfer) : TransferSending {
        /** The signed bytes a retry sends. */
        val signed: SignedTransfer
            get() = transfer.signed
    }
}

/**
 * A sent transaction (docs/wallet-ux.md, screen 9): what was sent, from its record, or from the
 * [history] once the record has forgotten it (null when neither has it: an older install, an
 * unreadable record), and where it stands, as its verified receipt says. The engine finds a
 * receipt only while it watches for it, so where the receipt is not found or cannot be verified
 * right now, the history says what was seen of it: one seen in a block reads as included in it
 * (or finalized there), and one whose nonce was used reads as pending with that outcome beside it.
 * The receipt is always asked for, a finalized one's too: what a swap paid comes from its
 * receipt's logs, which the history does not keep, so it is not known of one only the history holds.
 */
class ObserveSentTransfer(
    private val sentTransfers: SentTransfers,
    private val receipts: ReceiptTracker,
    private val history: TransactionHistory,
) {
    operator fun invoke(network: Network, hash: String): Flow<SentTransferStatus> = combine(
        sentTransfers.observe().map { list ->
            (list as? SentTransferList.Loaded)?.transfers?.lastOrNull { it.hash == hash }
        }.distinctUntilChanged(),
        history.observe().map { list ->
            (list as? HistoryList.Loaded)?.entries?.lastOrNull { it.transfer.hash == hash }
        }.distinctUntilChanged(),
        receipts.observe(network, hash),
    ) { record, recorded, read ->
        val transfer = record ?: recorded?.transfer
        val seen = seen(read, recorded?.outcome)
        SentTransferStatus(transfer, seen, receivedOf(transfer, seen), recorded?.outcome)
    }

    /**
     * [read], or where it finds no block or cannot be verified right now, the block the history saw
     * take it: without the receipt's logs, which only a receipt read now has.
     */
    private fun seen(read: TransactionProgress, outcome: SentOutcome?): TransactionProgress {
        if (read != TransactionProgress.Pending && read !is TransactionProgress.Unavailable) return read
        return when (outcome) {
            is SentOutcome.Included -> TransactionProgress.Included(outcome.blockNumber, outcome.succeeded)
            is SentOutcome.Finalized -> TransactionProgress.Finalized(outcome.blockNumber, outcome.succeeded)
            SentOutcome.NotSeen, SentOutcome.NonceUsed, null -> read
        }
    }

    /** What a swap that went through paid the account, by its verified receipt's logs. */
    private fun receivedOf(transfer: SentTransfer?, progress: TransactionProgress): UInt256? {
        val swap = transfer?.action as? SentAction.Swap ?: return null
        val logs = when (progress) {
            is TransactionProgress.Included -> progress.logs.takeIf { progress.succeeded }
            is TransactionProgress.Finalized -> progress.logs.takeIf { progress.succeeded }
            TransactionProgress.Pending, is TransactionProgress.Unavailable -> null
        } ?: return null
        return SwapProceeds.receivedOf(swap, transfer.account, logs)
    }
}

/**
 * What [ObserveSentTransfer] emits: the [transfer] as recorded, and its [progress]; for a swap
 * that went through, what it paid the account ([received], in the bought asset's smallest
 * units), when its receipt's logs say it; and how the history says it ended ([outcome]; null
 * when the history does not hold it).
 */
data class SentTransferStatus(
    val transfer: SentTransfer?,
    val progress: TransactionProgress,
    val received: UInt256? = null,
    val outcome: SentOutcome? = null,
)
