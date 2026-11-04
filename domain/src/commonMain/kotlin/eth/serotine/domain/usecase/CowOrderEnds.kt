package eth.serotine.domain.usecase

import eth.serotine.domain.model.GPv2Settlement
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderList
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.CowOrders
import eth.serotine.domain.repository.FinalizedBlock
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** How long an ended CoW order stays in the record, for its status: a week. */
const val COW_ORDERS_KEPT_SECONDS: Long = 7 * 24 * 60 * 60

/**
 * Proves the end of each CoW order this wallet signed that is still live (docs/swaps.md, *Proving
 * an order's end*), from the account's own entry in the engine's log index, never from CoW's API,
 * and records it ([CowOrders.end]). Run by the hosts while the engine runs, as `FollowAccountLogs`
 * is, so that an order's end is proven whatever screen is open, and again after a restart.
 *
 * The proof reads no past state: a verified `getLogs` of the account's `Mention` entry, at topic 1
 * (the owner), for `Trade` and `OrderInvalidated`, from the block finalized when the order was
 * signed to the newest finalized block. Only logs the order's own settlement contract emitted
 * count, and only those carrying its UID ([GPv2Settlement]). The index answers only a range it
 * covers in full, and the adapter refuses one reaching below the entry's start
 * ([LogsReading.BeforeStart]), so an empty answer is a proven absence.
 *
 * - A `Trade` for the order: filled ([CowOrderEnd.Filled]).
 * - Else its `OrderInvalidated`, in a block at or below the finalized one: never filled, cancelled
 *   ([CowOrderEnd.Unfilled]); nothing settles an order after it, and a settlement earlier in the
 *   same block would be a `Trade` in the range.
 * - Else, once the finalized block's own timestamp is past the order's `validTo`: never filled.
 * - A settlement log of either event that does not decode proves nothing, and is tried again:
 *   skipped, it could hide this order's fill.
 * - Two cases can never be proven ([CowOrderEnd.NotProvable]), and are said so only once a finalized
 *   block is past `validTo`: until then the order may still fill, and its tokens stay held back.
 *   - A range below the entry's start.
 *   - An entry no longer followed because its account is no longer in the wallet ([accounts]). Its
 *     entry and logs were dropped with it, and an account added again starts its history after the
 *     order.
 * - A range not covered yet, no answer right now, or an entry not followed yet for an account the
 *   wallet holds (as at every start, until the index is configured) is tried again. So is every
 *   entry not followed while the account list cannot be read.
 */
class ProveCowOrderEnds(
    private val cowOrders: CowOrders,
    private val logIndex: LogIndex,
    private val lightClient: LightClient,
    private val accounts: AccountRepository,
    /** The network whose orders this proves, at its finalized blocks, in its log index (docs/networks.md). */
    private val network: Network,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    /**
     * Runs until cancelled: first drops the orders whose end is known and that were signed over
     * [COW_ORDERS_KEPT_SECONDS] ago (kept until then for their status), then, each time the record
     * of orders, the finalized block, the wallet's accounts or the index's coverage changes, proves
     * what it can of every live order.
     */
    suspend operator fun invoke() {
        cowOrders.prune(signedBefore = clock() - COW_ORDERS_KEPT_SECONDS)
        val finalized = lightClient.observeStatus(network)
            .map { (it as? LightClientStatus.Synced)?.finalized }
            .distinctUntilChanged()
        // The wallet's addresses on this network; null while the list cannot be read, when no account
        // counts as removed.
        val held = accounts.observeAccounts()
            .map { list ->
                (list as? AccountList.Loaded)?.accounts?.filter { it.network == network }?.map { it.address }?.toSet()
            }
            .distinctUntilChanged()
        combine(
            cowOrders.observe(),
            finalized,
            held,
            logIndex.observeCoverage(network),
        ) { orders, final, addresses, _ ->
            Triple(orders, final, addresses)
        }.collectLatest { (orders, final, addresses) ->
            val live = (orders as? CowOrderList.Loaded)?.orders.orEmpty().filter { it.live && it.network == network }
            for (order in live) {
                val removed = addresses != null && order.account !in addresses
                endOf(order, final, removed)?.let { cowOrders.end(order.uid, it) }
            }
        }
    }

    /**
     * What the chain proves of [order] with [finalized] the newest finalized block, its account
     * [removed] from the wallet or not; null: nothing yet.
     */
    internal suspend fun endOf(
        order: CowOrderRecord,
        finalized: FinalizedBlock?,
        removed: Boolean = false,
    ): CowOrderEnd? {
        if (finalized == null || finalized.number < order.rangeStart) return null
        val query = LogQuery(
            key = LogWatchKey.Mention(order.account),
            fromBlock = order.rangeStart,
            toBlock = finalized.number,
            topic0s = listOf(GPv2Settlement.TRADE_TOPIC, GPv2Settlement.ORDER_INVALIDATED_TOPIC),
            position = 1,
        )
        val logs = when (val reading = logIndex.logs(order.network, query)) {
            is LogsReading.Logs -> reading.logs
            is LogsReading.BeforeStart ->
                return CowOrderEnd.NotProvable.takeIf { finalized.timestamp > order.order.validTo }
            // Not followed: for good once its account is gone, not yet while the wallet holds it.
            LogsReading.NotWatched ->
                return CowOrderEnd.NotProvable.takeIf { removed && finalized.timestamp > order.order.validTo }
            is LogsReading.NotCovered, is LogsReading.Unavailable -> return null
        }
        val settlementLogs = logs.filter { it.log.address == order.settlement }
        val undecodable = settlementLogs.any { indexed ->
            val log = indexed.log
            when (log.topics.firstOrNull()) {
                GPv2Settlement.TRADE_TOPIC ->
                    GPv2Settlement.tradeOf(order.settlement, log.address, log.topics, log.data) == null
                GPv2Settlement.ORDER_INVALIDATED_TOPIC ->
                    GPv2Settlement.invalidationOf(order.settlement, log.address, log.topics, log.data) == null
                else -> false
            }
        }
        if (undecodable) return null
        settlementLogs.firstNotNullOfOrNull { indexed ->
            val log = indexed.log
            GPv2Settlement.tradeOf(order.settlement, log.address, log.topics, log.data)
                ?.takeIf { it.uid == order.uid }
                ?.let { CowOrderEnd.Filled(it, indexed.blockNumber, indexed.transactionHash) }
        }?.let { return it }
        settlementLogs.firstOrNull { indexed ->
            val log = indexed.log
            GPv2Settlement.invalidationOf(order.settlement, log.address, log.topics, log.data) == order.uid
        }?.let { return CowOrderEnd.Unfilled(endBlock = it.blockNumber, cancelled = true) }
        return CowOrderEnd.Unfilled(endBlock = finalized.number, cancelled = false)
            .takeIf { finalized.timestamp > order.order.validTo }
    }
}
