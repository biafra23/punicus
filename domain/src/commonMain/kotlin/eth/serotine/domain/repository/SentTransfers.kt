package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import kotlinx.coroutines.flow.Flow

/**
 * The port for the transactions this wallet sent (signed, and taken by a peer, or maybe taken:
 * [SentTransfer.maybeSent]) — its transfers, its approvals and executions of Safe transactions,
 * its Safes' deployments, and its swaps and the token approvals before them — kept across
 * restarts: what the Send status screen (docs/wallet-ux.md, screen 9) shows, and what keeps a
 * new transaction from reusing the nonce of one not mined yet — the engine's own record of its
 * sends lasts only while the process does.
 * Public data only: a signed transaction's bytes are public once broadcast, and no key is ever in
 * them. :data keeps it in a file. Never throws.
 */
interface SentTransfers {

    /** Every transaction recorded, re-emitted on each change, or that the record cannot be read. */
    fun observe(): Flow<SentTransferList>

    /**
     * Records [transfer], which a peer took, in place of any record of the same hash (one record per
     * transaction: what a Safe's deployment's listing is written back into); false when it could not
     * be saved.
     */
    suspend fun record(transfer: SentTransfer): Boolean

    /**
     * Forgets [account]'s transactions whose nonce is below [minedCount], its verified count of
     * mined transactions (each of those nonces is used on chain, by that transaction or another),
     * but for a Safe's deployment whose Safe is not listed yet ([SentAction.SafeDeployment.listed]
     * null), which is kept until it was taken before [unlistedBefore] (unix seconds): it is the
     * only record of the Safe's owners and name. One no block took stays, past the time it holds
     * its nonce (`RECORD_LIFETIME_SECONDS`), so that its status is still found.
     * [SentTransfer.prunable] is the rule. False when the record could not be saved.
     */
    suspend fun prune(account: Address, minedCount: Long, unlistedBefore: Long): Boolean
}

/**
 * Whether [SentTransfers.prune] of [account] forgets this transaction: one of [account]'s whose
 * nonce is below [minedCount], unless it is a Safe's deployment not listed yet, which is
 * forgotten only once taken before [unlistedBefore].
 */
fun SentTransfer.prunable(account: Address, minedCount: Long, unlistedBefore: Long): Boolean {
    if (this.account != account) return false
    val deployment = action as? SentAction.SafeDeployment
    if (deployment != null && deployment.listed == null) return sentAt < unlistedBefore
    return nonce < minedCount
}

/** How the listing of a deployed Safe ended (`FollowSafeDeployments`). */
enum class SafeListing {
    /** The Safe is in the wallet: listed, or there already. */
    LISTED,

    /** The deployment reverted, and no code is at the address: no Safe was created. */
    NO_SAFE,

    /** The address holds something other than the Safe planned: not listed. */
    NOT_THE_SAFE,
}

/** What [SentTransfers.observe] holds. */
sealed interface SentTransferList {

    /** The [transfers] recorded, oldest first. */
    data class Loaded(val transfers: List<SentTransfer>) : SentTransferList

    /** The record cannot be read right now (a newer format, a file that cannot be read). */
    data object Unreadable : SentTransferList
}

/**
 * A transaction this wallet signed from [account] and a peer took: its [nonce] and [signed]
 * bytes, what it does ([action]), the most it can take of the account's native balance
 * ([maxCost]: its value and the most its fee can be), and when a peer took it ([sentAt], unix
 * seconds): the moment its lifetime in the network's transaction pools runs from. The nonce and
 * the cost are what hold a later transaction back while this one is not mined, whatever its
 * action.
 *
 * [maybeSent] when its broadcast came back unavailable instead (no peer answered, [sentAt] is
 * that broadcast's): it may have reached one all the same, so it is recorded, its receipt watched
 * by its hash, but it holds neither the nonce nor the cost. While it is not mined and within its
 * lifetime, no new transaction is quoted from [account] (`CallRefusal.EarlierMaybeSent`): it is
 * retried byte for byte, never replaced by one that could take the next nonce and pay twice.
 */
data class SentTransfer(
    val account: Address,
    val nonce: Long,
    val signed: SignedTransfer,
    val action: SentAction,
    val maxCost: UInt256,
    val sentAt: Long,
    val maybeSent: Boolean = false,
) {
    /** The transaction hash its receipt is watched by. */
    val hash: String
        get() = signed.hash
}

/**
 * What a [SentTransfer] does: one variant per kind of transaction this wallet sends, each with
 * what its status shows of it (docs/wallet-ux.md, screen 9).
 */
sealed interface SentAction {

    /**
     * [amount] of [asset] to [to]: the transaction's value for the native currency, the token
     * contract's `transfer` for a token, whose amount the account's later transfers of that token
     * cannot move while this one is not mined.
     */
    data class Transfer(val to: Address, val asset: TransferAsset, val amount: UInt256) : SentAction

    /**
     * The account's on-chain approval (`approveHash`) of [request]'s Safe transaction, sent to its
     * Safe: it moves nothing of the account's but the fee.
     */
    data class SafeApproval(val request: SigningRequest) : SentAction

    /**
     * The execution (`execTransaction`) of [request]'s Safe transaction, sent to its Safe: what it
     * moves is the Safe's, and the account pays the fee. [request] is the transaction as it was
     * queued; the confirmations it was executed with (as many as the threshold, approvals among
     * them) are in the signed bytes.
     */
    data class SafeExecution(val request: SigningRequest) : SentAction

    /**
     * The deployment of a new Safe at [safe] through its release's proxy factory
     * (`createProxyWithNonce`): of Safe [version], with [owners] in their order and [threshold],
     * to be listed in the wallet as [label] (blank: named as a watched account is) once the light
     * client proves it, and how that ended ([listed]; null: not yet). It moves nothing of the
     * account's but the fee.
     */
    data class SafeDeployment(
        val safe: Address,
        val owners: List<Address>,
        val threshold: Int,
        val version: String,
        val label: String,
        val listed: SafeListing? = null,
    ) : SentAction

    /**
     * The account's approval of [spender] to take [amount] of [token] (`approve`), its own
     * transaction before a swap whose allowance falls short (docs/swaps.md, *What both venues
     * share*): it sets the allowance to [amount], zero included, and moves nothing but the fee.
     */
    data class TokenApproval(val token: Token, val spender: Address, val amount: UInt256) : SentAction

    /**
     * A swap on Uniswap through the router at [router]: exactly [amountIn] of [sell] for at least
     * [minimumOut] of [buy], paid to the account (docs/swaps.md, *The transaction*). What it sells
     * of a token, the account's later transactions cannot move while this one is not mined; ETH
     * sold is the transaction's value, which its cost already holds. [weth] is the pinned WETH9
     * the router unwraps ETH bought from (its `Withdrawal` says how much); null when the swap
     * buys a token, or the record is older than this field.
     */
    data class Swap(
        val router: Address,
        val sell: TransferAsset,
        val amountIn: UInt256,
        val buy: TransferAsset,
        val minimumOut: UInt256,
        val weth: Address? = null,
    ) : SentAction

    /**
     * ETH into WETH: [amount] wei deposited into the pinned WETH9, [weth] (`deposit`), which
     * credits the account as much WETH (docs/swaps.md, *Wrapping ETH*). What it takes is the
     * transaction's value, which its cost already holds.
     */
    data class Wrap(val weth: Token, val amount: UInt256) : SentAction

    /**
     * WETH back into ETH: [amount] of the pinned WETH9, [weth], withdrawn (`withdraw`), which pays
     * the account as much ETH (docs/swaps.md, *Wrapping ETH*). What it takes of the token, the
     * account's later transactions cannot move while this one is not mined.
     */
    data class Unwrap(val weth: Token, val amount: UInt256) : SentAction

    /**
     * The account's cancellation on chain of its CoW order [uid] (`invalidateOrder`), sent to the
     * settlement contract [settlement] (docs/swaps.md, *Proving an order's end*): once mined and
     * finalized, nothing can fill the order. It moves nothing but the fee.
     */
    data class CowCancellation(val settlement: Address, val uid: CowOrderUid) : SentAction
}
