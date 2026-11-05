package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PaymentLinks
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.hexToBytes
import eth.serotine.domain.repository.BlockReading
import eth.serotine.domain.repository.BlockTransactions
import eth.serotine.domain.repository.ChainTransaction
import eth.serotine.domain.repository.HandoffAvailability
import eth.serotine.domain.repository.HandoffOpening
import eth.serotine.domain.repository.HeadReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.repository.WalletHandoff
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

// Send from another wallet (issue #255; owner rulings, 2026-11-05): a transfer from an EOA whose key
// this device does not hold, handed to another wallet app as a payment link, which that wallet signs
// and broadcasts. Serotine signs nothing, and nothing comes back through the link: what became of the
// transfer is read from verified blocks alone.

/** How long the outcome is watched for after the handoff (decision 4 of #255): after that, the account's history. */
val HANDOFF_WINDOW: Duration = 10.minutes

/** Whether Send from another wallet is offered for an account, and how. */
sealed interface HandoffOffer {

    /** It is offered: a wallet app on this device takes payment links. */
    data object Offered : HandoffOffer

    /** It is shown greyed out: no app on this device takes payment links (owner ruling, 2026-10-18, #55). */
    data object NoWallet : HandoffOffer

    /**
     * It does not apply, and is left out: a Safe (a link cannot sign a Safe transaction), an account
     * whose key is held here (Send signs for it), or a platform that hands no link over.
     */
    data object LeftOut : HandoffOffer
}

/**
 * Whether [account] gets Send from another wallet ([HandoffOffer]): an EOA whose key this device
 * does not hold, on a device that hands payment links to other wallets. Watched still means
 * Serotine signs nothing (decision 5 of #255): the other wallet must hold the key. Asks [wallets]
 * anew on each call; never throws.
 */
class OfferHandoff(private val wallets: WalletHandoff) {
    operator fun invoke(account: Account): HandoffOffer {
        if (account.kind != AccountKind.EOA || account.custody == AccountCustody.InKeyStore) return HandoffOffer.LeftOut
        return when (availability()) {
            HandoffAvailability.AVAILABLE -> HandoffOffer.Offered
            HandoffAvailability.NO_WALLET -> HandoffOffer.NoWallet
            HandoffAvailability.NOT_ON_THIS_DEVICE -> HandoffOffer.LeftOut
        }
    }

    /** Whether this device can hand a link over at all, whatever the account. */
    fun availability(): HandoffAvailability = try {
        wallets.availability()
    } catch (e: Exception) {
        HandoffAvailability.NO_WALLET // the port promises not to throw; the backstop
    }
}

/**
 * A transfer handed to another wallet: [amount] (wei, or [asset]'s token units) of [asset] from
 * [account] to [to], asked by [link], and the newest verified block when it was handed over
 * ([afterBlock]): the transfer is looked for in the blocks above it.
 */
data class Handoff(
    val account: AccountId,
    val asset: TransferAsset,
    val to: Address,
    val amount: UInt256,
    val afterBlock: Long,
    val link: String,
)

/**
 * Hands a transfer to another wallet app ([WalletHandoff]) as a payment link ([PaymentLinks]),
 * after the same refusals Send makes: the zero address and the token's own contract, from which
 * nothing ever comes back, and an amount of zero. The verified head is read first, so that what is
 * then watched for ([WatchHandoff]) is in a later block: a light client that cannot read one now
 * hands nothing over. Never throws.
 */
class HandOffTransfer(private val wallets: WalletHandoff, private val blocks: BlockTransactions) {
    suspend operator fun invoke(account: AccountId, asset: TransferAsset, to: Address, amount: UInt256): HandoffStart {
        if (to == Address.ZERO) return HandoffStart.ToZeroAddress
        if (asset is TransferAsset.Erc20Token && to == asset.token.address) return HandoffStart.ToTokenContract
        if (amount.isZero) return HandoffStart.NothingToSend
        val head = when (val reading = ask { blocks.head(account.network) } ?: HeadReading.Unavailable(UNPROVEN)) {
            is HeadReading.At -> reading.number
            is HeadReading.Unavailable -> return HandoffStart.Unavailable(reading.reason)
        }
        val link = PaymentLinks.of(account.network, asset, to, amount)
        return when (ask { wallets.open(link) } ?: HandoffOpening.NO_WALLET) {
            HandoffOpening.OPENED -> HandoffStart.Opened(Handoff(account, asset, to, amount, head, link))
            HandoffOpening.NO_WALLET -> HandoffStart.NoWallet
        }
    }
}

/** What [HandOffTransfer] did. */
sealed interface HandoffStart {

    /** The link was handed over: [handoff] is what to watch for. */
    data class Opened(val handoff: Handoff) : HandoffStart

    /** No app on this device took the link. Nothing was handed over. */
    data object NoWallet : HandoffStart

    /** To the zero address: nobody can ever move out what is sent there. Nothing was handed over. */
    data object ToZeroAddress : HandoffStart

    /** To the token's own contract: nothing sent there can ever be moved out. Nothing was handed over. */
    data object ToTokenContract : HandoffStart

    /** An amount of zero. Nothing was handed over. */
    data object NothingToSend : HandoffStart

    /** The light client cannot read a verified block now, for [reason]. Nothing was handed over. */
    data class Unavailable(val reason: ReadUnavailable) : HandoffStart
}

/**
 * What [transaction] is to [handoff], or null when it is not the transfer asked for:
 * - [HandoffMatch.Exact]: from the account, to the recipient, that amount of that asset;
 * - [HandoffMatch.OtherAmount]: from the account to the recipient, in that asset, another amount
 *   (a wallet that read the link differently, or whose sheet the user edited);
 * - [HandoffMatch.OtherSender]: that amount of that asset to the recipient, from another address
 *   (the wallet sent from another of its accounts).
 *
 * A payment of the network's currency is a plain transfer: to the recipient, with no call data. A
 * token's is a call of the token's contract that is exactly `transfer(recipient, amount)`.
 */
fun matchHandoff(handoff: Handoff, transaction: ChainTransaction): HandoffMatch? {
    val sent: UInt256 = when (val asset = handoff.asset) {
        TransferAsset.Native -> {
            if (transaction.to != handoff.to || transaction.input != "0x") return null
            transaction.value
        }
        is TransferAsset.Erc20Token -> {
            if (transaction.to != asset.token.address || !transaction.value.isZero) return null
            val call = callData(transaction.input) ?: return null
            val transfer = Erc20.transferOf(call) ?: return null
            if (transfer.to != handoff.to) return null
            transfer.amount
        }
    }
    val fromAccount = transaction.from == handoff.account.address
    return when {
        fromAccount && sent == handoff.amount -> HandoffMatch.Exact
        fromAccount -> HandoffMatch.OtherAmount(sent)
        sent == handoff.amount -> HandoffMatch.OtherSender(transaction.from)
        else -> null
    }
}

private fun callData(input: String): ByteArray? = try {
    input.removePrefix("0x").hexToBytes()
} catch (e: IllegalArgumentException) {
    null
}

/** What a transaction found is to the handoff ([matchHandoff]). */
sealed interface HandoffMatch {

    /** The transfer asked for: from the account, to the recipient, that amount. */
    data object Exact : HandoffMatch

    /** From the account to the recipient, but [amount] (wei or token units) and not the amount asked for. */
    data class OtherAmount(val amount: UInt256) : HandoffMatch

    /** The amount asked for, to the recipient, but from [sender] and not from the account. */
    data class OtherSender(val sender: Address) : HandoffMatch
}

/**
 * Follows [handoff] on the chain until [deadline]: every [pollInterval], each verified block above
 * [Handoff.afterBlock] up to the verified head is read ([BlockTransactions]), and the first
 * transaction that is the transfer, or nearly ([matchHandoff]), is the answer: within one block an
 * exact match wins. Its verified receipt then says whether it succeeded ([ReceiptTracker]).
 *
 * Emits [HandoffWatch.Watching] while it looks (with the block it has read up to, and why a block
 * cannot be read now, when that is so); [HandoffWatch.Found] once a block has the transaction and
 * its receipt is awaited; then [HandoffWatch.Seen], and ends. Past [deadline] with every block up
 * to the head read and nothing found, [HandoffWatch.NotSeen], and ends: the deadline never ends a
 * watch that is still behind the head, since the blocks it has not read may hold the transfer.
 * Never throws.
 */
class WatchHandoff(
    private val blocks: BlockTransactions,
    private val receipts: ReceiptTracker,
    private val pollInterval: Duration = 4.seconds,
) {
    operator fun invoke(handoff: Handoff, deadline: TimeMark): Flow<HandoffWatch> = flow {
        val network = handoff.account.network
        var readUpTo = handoff.afterBlock
        emit(HandoffWatch.Watching(readUpTo))
        while (true) {
            val head = ask { blocks.head(network) } ?: HeadReading.Unavailable(UNPROVEN)
            var unavailable: ReadUnavailable? = (head as? HeadReading.Unavailable)?.reason
            if (head is HeadReading.At) {
                while (readUpTo < head.number) {
                    val number = readUpTo + 1
                    val block = ask { blocks.transactions(network, number) } ?: BlockReading.Unavailable(UNPROVEN)
                    when (block) {
                        is BlockReading.Read -> {
                            val found = firstMatch(handoff, block.transactions)
                            if (found != null) {
                                val (match, transaction) = found
                                emit(HandoffWatch.Found(match, transaction.hash, number))
                                val succeeded = succeeded(network, transaction.hash)
                                emit(HandoffWatch.Seen(match, transaction.hash, number, succeeded))
                                return@flow
                            }
                            readUpTo = number
                        }
                        BlockReading.NotYet -> break // the head moved back under it: asked again next time
                        is BlockReading.Unavailable -> {
                            unavailable = block.reason
                            break
                        }
                    }
                }
                if (readUpTo >= head.number && deadline.hasPassedNow()) {
                    emit(HandoffWatch.NotSeen(readUpTo))
                    return@flow
                }
            }
            emit(HandoffWatch.Watching(readUpTo, unavailable))
            delay(pollInterval)
        }
    }

    /** The first transaction of [transactions] that is [handoff]'s transfer, an exact one before any near one. */
    private fun firstMatch(
        handoff: Handoff,
        transactions: List<ChainTransaction>,
    ): Pair<HandoffMatch, ChainTransaction>? {
        val matches = transactions.mapNotNull { transaction ->
            matchHandoff(handoff, transaction)?.let { it to transaction }
        }
        return matches.firstOrNull { it.first == HandoffMatch.Exact } ?: matches.firstOrNull()
    }

    /** Whether the transaction [hash], which a verified block includes, succeeded: its verified receipt's word. */
    private suspend fun succeeded(network: Network, hash: String): Boolean =
        receipts.observe(network, hash)
            .filter { it is TransactionProgress.Included || it is TransactionProgress.Finalized }
            .first()
            .let { (it as? TransactionProgress.Included)?.succeeded ?: (it as TransactionProgress.Finalized).succeeded }
}

/** What [WatchHandoff] has found so far. */
sealed interface HandoffWatch {

    /**
     * Looking: every block up to [readUpTo] was read and holds no such transfer; [unavailable] why
     * the next one cannot be read now, when that is so.
     */
    data class Watching(val readUpTo: Long, val unavailable: ReadUnavailable? = null) : HandoffWatch

    /** Block [block] includes transaction [hash], which is [match]; its receipt is being read. */
    data class Found(val match: HandoffMatch, val hash: String, val block: Long) : HandoffWatch

    /** Block [block] includes transaction [hash], which is [match]; its verified receipt says if it [succeeded]. */
    data class Seen(val match: HandoffMatch, val hash: String, val block: Long, val succeeded: Boolean) : HandoffWatch

    /** The window passed with every block up to [readUpTo] read and no such transfer in any. */
    data class NotSeen(val readUpTo: Long) : HandoffWatch
}

private val UNPROVEN = ReadUnavailable.UNPROVEN

/** [read], or null when the port broke its promise not to throw; a cancellation stays one. */
private suspend fun <T> ask(read: suspend () -> T): T? = try {
    read()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
