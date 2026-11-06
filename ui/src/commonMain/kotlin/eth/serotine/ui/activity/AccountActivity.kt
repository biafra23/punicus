package eth.serotine.ui.activity

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeEvent
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.WaitingSwap
import eth.serotine.domain.usecase.IndexedEvent
import eth.serotine.domain.usecase.IndexedHistory
import eth.serotine.domain.usecase.KnownToken
import eth.serotine.domain.usecase.ObserveAccountCowOrders
import eth.serotine.domain.usecase.ObserveAccountHistory
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveOutbox
import eth.serotine.domain.usecase.ObserveSafeHistory
import eth.serotine.domain.usecase.ObserveSentToAddresses
import eth.serotine.domain.usecase.ObserveTokenTransfers
import eth.serotine.domain.usecase.ObserveWaitingSwaps
import eth.serotine.domain.usecase.SafeChainEvent
import eth.serotine.domain.usecase.TokenTransferEvent
import eth.serotine.ui.components.grouped
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * What an account's Activity shows, its detail's Activity tab (docs/wallet-ux.md, screen 25;
 * issue #85), for the account detail's ViewModel to read while the tab is shown: the transactions
 * this device sent for the account ([ObserveAccountHistory]) and, for an account of the wallet,
 * what the engine's verified log index holds of it: every token transfer to or from it
 * ([ObserveTokenTransfers]) and, for a Safe, its own events ([ObserveSafeHistory]). The names
 * the wallet gives the addresses they concern come from [ObserveAccounts], the tokens' symbols
 * and decimals from the tokens it knows ([ObserveKnownTokens]); a transfer received from an
 * address the wallet never sent to ([ObserveSentToAddresses]) and that is none of its accounts
 * is cautioned. A log of a transaction listed as sent, in a block, is left out when its row says
 * the same. The CoW orders the account signed here ([ObserveAccountCowOrders]) are listed among
 * the transactions, and open their status. Every row goes by when it happened, newest first
 * ([merged]). Above them, each swap whose next step waits for the user ([ObserveWaitingSwaps]),
 * said from its approval's outcome in the account's history; and before everything, what the
 * account has queued in lenient mode ([ObserveOutbox]; docs/lenient-mode.md, *Where it shows*).
 * [formatTime] dates each in the user's own time zone. A row opens its detail, which is
 * navigation; a queued one, its status.
 */
class AccountActivity(
    private val observeAccountHistory: ObserveAccountHistory,
    private val observeAccounts: ObserveAccounts,
    private val observeSafeHistory: ObserveSafeHistory,
    private val observeTokenTransfers: ObserveTokenTransfers,
    private val observeKnownTokens: ObserveKnownTokens,
    private val observeSentTo: ObserveSentToAddresses,
    private val observeCowOrders: ObserveAccountCowOrders,
    private val observeWaitingSwaps: ObserveWaitingSwaps,
    private val observeOutbox: ObserveOutbox,
    private val formatTime: (Long) -> String = ::formatDateTime,
) {

    /**
     * The Activity of the [account], as it changes, with every amount masked while [hideAmounts] says
     * so (docs/wallet-ux.md, *Hiding amounts*); nothing is read until it is collected.
     */
    fun of(account: AccountId, hideAmounts: Flow<Boolean>): Flow<ActivityUiState> =
        Listing(account, hideAmounts).state

    /** One account's Activity: the [account] its rows and sources are of, and whether its amounts are hidden. */
    private inner class Listing(private val account: AccountId, private val hideAmounts: Flow<Boolean>) {

        private val address: Address = account.address

        private val sources = ActivitySources(
            account,
            observeAccounts,
            observeSafeHistory,
            observeTokenTransfers,
            observeKnownTokens,
            observeSentTo,
        )

        /** What the account has queued in lenient mode; an outbox that cannot be read lists none. */
        private val queued: Flow<List<QueuedTransaction>> = observeOutbox()
            .map { list -> (list as? OutboxList.Loaded)?.queued.orEmpty().filter { it.accountId == account } }
            // Nothing queued until the outbox is read: the Activity never waits for it.
            .onStart { emit(emptyList()) }
            .distinctUntilChanged()

        val state: Flow<ActivityUiState> = combine(
            combine(
                observeAccountHistory(account),
                observeCowOrders(account),
                observeWaitingSwaps(account),
                hideAmounts,
                queued,
            ) { history, orders, waiting, hidden, queued -> SentHere(history, orders, waiting, hidden, queued) },
            sources.parties,
            sources.safeHistory,
            sources.transfers,
            sources.tokens,
            ::render,
        )

        private fun render(
            sentHere: SentHere,
            parties: Parties,
            safe: IndexedHistory<SafeChainEvent>?,
            transfers: IndexedHistory<TokenTransferEvent>?,
            tokens: Map<Address, KnownToken>?,
        ): ActivityUiState {
            val names = parties.names
            val (history, orders) = sentHere
            val entries = when (history) {
                HistoryList.Unreadable -> return ActivityUiState.Unavailable(UNREADABLE)
                is HistoryList.Loaded -> history.entries
            }
            // Built per change, for the amounts it says to follow the eye. A Safe transaction is said
            // as what it does, in the units of the tokens known so far, and an execution or approval
            // on chain by the request this device holds of it.
            val rows = ActivityRows(
                address,
                account.network,
                formatTime,
                sentHere.hidden,
                decodingTokens = ActivityRows.decodingTokens(tokens),
                requests = ActivityRows.requestsIn(entries),
            )
            val waiting = sentHere.waiting.map { waitingStep(it, entries, tokens, rows) }
            val queued = sentHere.queued.map { rows.queuedRow(it, names) }
            val safeEvents = (safe as? IndexedHistory.Read)?.events.orEmpty()
            val tokenEvents = (transfers as? IndexedHistory.Read)?.events.orEmpty()
            // When each block was produced, as the engine reported it with any event in it: what a
            // transaction seen in that block goes by, before when a peer took it.
            val allEvents: List<IndexedEvent> = safeEvents + tokenEvents
            val blockTimes = allEvents.mapNotNull { event -> event.blockTimestamp?.let { event.blockNumber to it } }
                .toMap()
            // The CoW orders signed here among them: a live one waits for its end as a transaction
            // waits for its block, a filled or cancelled one goes by the block of its fill or its
            // cancellation, and one never filled by when it was signed (the block that proved it
            // came later).
            val sent = entries.map {
                val block = it.outcome.blockNumberOrNull
                Listed(
                    rows.row(it, names),
                    block,
                    pending = it.outcome == SentOutcome.NotSeen,
                    moment = block?.let(blockTimes::get) ?: it.transfer.sentAt,
                )
            } + orders.map {
                val block = it.end?.placedAt
                Listed(rows.orderRow(it), block, pending = it.live, moment = block?.let(blockTimes::get) ?: it.signedAt)
            }
            if (transfers == null) {
                return ActivityUiState.Shown(merged(sent, emptyList()), COVERAGE, waiting = waiting, queued = queued)
            }
            // Only a row that names its block says what its logs say: one no block was seen for (a
            // transaction mined while the app was closed) gets the verified log beside it.
            val seenInBlocks = entries.filter { it.outcome.blockNumberOrNull != null }.associateBy { it.transfer.hash }
            // A CoW order's fill says what it sold and bought: its own transfers of them, in the fill, are
            // not listed again, nor is the settlement paying the account called an unfamiliar sender. One
            // settlement can fill several of the account's orders, each of its own token.
            val fills = orders.filter { it.end is CowOrderEnd.Filled }
                .groupBy { (it.end as CowOrderEnd.Filled).transactionHash.lowercase() }
            val safeRows = safeEvents
                .filterNot { it.transactionHash in seenInBlocks && it.event.saidBySentRow() }
                .map { rows.eventRow(it, parties) to it }
            // Until the tokens are known, a transfer's amount cannot be said: none is listed yet.
            val tokenRows = tokenEvents
                .takeIf { tokens != null }.orEmpty()
                .filterNot { it.transfer.movesNothing() }
                .filterNot { event -> seenInBlocks[event.transactionHash]?.let { event.transfer.saidBy(it) } == true }
                .filterNot { event ->
                    val fill = fills[event.transactionHash.lowercase()].orEmpty()
                    fill.any { event.transfer.soldIn(it) || event.transfer.boughtIn(it) }
                }
                .map { rows.transferRow(it, parties, tokens.orEmpty()) to it }
            val events = (safeRows + tokenRows)
                .sortedWith(
                    compareByDescending<Pair<ActivityRow, IndexedEvent>> { it.second.blockNumber }
                    .thenByDescending { it.second.logIndex },
                )
            // An event the engine dated goes by its time like a sent row; one it did not by its block.
            val dated = events.mapNotNull { (row, event) ->
                event.blockTimestamp?.let { Listed(row, event.blockNumber, pending = false, moment = it, event = true) }
            }
            val undated = events.filter { it.second.blockTimestamp == null }
                .map { (row, event) -> row to event.blockNumber }
            val deployedHere = entries.any { (it.transfer.action as? SentAction.SafeDeployment)?.safe == address }
            val since = if (deployedHere) "when it was deployed from this device" else "when it was added here"
            val parts = listOfNotNull(safe?.let { "its events" to it }, "its token transfers" to transfers)
            val covered = listOfNotNull(
                "Transactions sent from this device.",
                onChainCoverage(parts, since),
                if (safe == null) notListedYet(account.network) else null,
            )
            // Simple mode's words for the same, with no block number (docs/simple-ui.md, H5): every
            // gap the detailed coverage names is named here too.
            val histories = parts.map { it.second }
            val sinceWhen = if (deployedHere) "since it was deployed from this device" else "since you added it"
            val simple = listOfNotNull(
                "Activity $sinceWhen.",
                "Older activity is still being checked.".takeIf {
                    histories.any { it is IndexedHistory.Checking || (it as? IndexedHistory.Read)?.complete == false }
                },
                "Activity on chain can't be read right now."
                    .takeIf { histories.any { it == IndexedHistory.Unavailable } },
                "Activity on chain is listed once the light client is synced."
                    .takeIf { histories.any { it == IndexedHistory.NotFollowed } },
                if (safe == null) notListedYet(account.network) else null,
            ).joinToString(" ")
            return ActivityUiState.Shown(
                rows = merged(sent + dated, undated),
                coverage = covered.joinToString(" "),
                empty = "Nothing listed yet.",
                simpleCoverage = simple,
                waiting = waiting,
                queued = queued,
            )
        }

        /**
         * [swap] as its card: what it swaps, once the tokens are known, and where it stands, from its
         * approval's outcome in [entries]. Step 1 is said done only on a verified receipt that it succeeded.
         */
        private fun waitingStep(
            swap: WaitingSwap,
            entries: List<HistoryEntry>,
            tokens: Map<Address, KnownToken>?,
            rows: ActivityRows,
        ) =
            WaitingStep(
                title = tokens?.get(swap.sell)?.token?.let { sold ->
                    // Only no token bought is the network's currency: one no longer listed is not named.
                    val bought = if (swap.buy == null) account.network.nativeSymbol else tokens[swap.buy]?.token?.symbol
                    bought?.let { "Swap ${rows.figure(swap.amount, sold.decimals, sold.symbol)} → $it" }
                } ?: "A swap",
                message = waitingText(swap, entries.firstOrNull { it.transfer.hash.equals(swap.approval, true) }),
                sell = swap.sell.hex,
            )

        private fun waitingText(swap: WaitingSwap, approval: HistoryEntry?): String {
            val done = swap.step - 1
            val succeeded = when (val outcome = approval?.outcome) {
                is SentOutcome.Included -> outcome.succeeded
                is SentOutcome.Finalized -> outcome.succeeded
                SentOutcome.NotSeen -> return "Step $done, the approval, is on its way. Once a block has it, step " +
                    "${swap.step} is yours to confirm."
                SentOutcome.NonceUsed, null -> return "Step ${swap.step} is waiting for you. Continue checks " +
                    "where step $done stands."
            }
            return if (succeeded) {
                "Step $done done. Step ${swap.step} is waiting for you."
            } else {
                "Step $done, the approval, failed in its block: nothing was swapped. Continue quotes it again."
            }
        }
    }

    /**
     * What this device sent for the account: its [history], its CoW [orders], and its swaps [waiting]
     * for a step; whether amounts are [hidden]; and what it has [queued] in lenient mode.
     */
    private data class SentHere(
        val history: HistoryList,
        val orders: List<CowOrderRecord>,
        val waiting: List<WaitingSwap>,
        val hidden: Boolean,
        val queued: List<QueuedTransaction>,
    )

    /**
     * What the sent row of a transaction that logged this event says once it names the block: its
     * execution, its approval, its deployment. A failure, and any change of the Safe's settings,
     * it does not.
     */
    private fun SafeEvent.saidBySentRow(): Boolean = when (this) {
        is SafeEvent.Executed -> succeeded
        is SafeEvent.Approved, is SafeEvent.SetUp -> true
        else -> false
    }

    /**
     * Whether [entry]'s row, which names its block, says this transfer: the token, the amount and
     * the recipient the account sent in a transfer, or the token and the amount it sold in a swap.
     * What it received, and any other move of a token (a fee, a share to a third address), it does
     * not.
     */
    private fun TokenTransfer.saidBy(entry: HistoryEntry): Boolean {
        if (this !is TokenTransfer.Fungible || from != entry.transfer.account) return false
        val (asset, sent) = when (val action = entry.transfer.action) {
            is SentAction.Transfer -> if (action.to == to) action.asset to action.amount else return false
            is SentAction.Swap -> action.sell to action.amountIn
            else -> return false
        }
        return (asset as? TransferAsset.Erc20Token)?.token?.address == token && sent == amount
    }

    /**
     * Every row in one list, newest first: the [dated] rows by when each happened (a sent
     * transaction by when its block was produced, where an event in that block says it, else by
     * when a peer took it; a CoW order alike, by its fill's or cancellation's block, else by when
     * it was signed; an event by when its block was produced), the sent ones before the events of
     * the same moment; and the [undated] events (newest block first), from an engine that does not
     * date a block, each among them by its block. A sent row still waiting for its block goes by
     * when a peer took it (a CoW order that may still fill, by when it was signed), and counts as
     * newer than every block: no undated event goes above it. One with no block that is not
     * waiting (a nonce used with no block seen, a CoW order never filled) is dated by when it was
     * sent, and for the undated events counts as just newer than the newest dated row below it
     * that has a block (a sent row or an event), so it goes before the undated events of that
     * row's block; when none has, it counts as the block of the nearest dated row above it that
     * has one, and as newer than every block when none has.
     */
    private fun merged(dated: List<Listed>, undated: List<Pair<ActivityRow, Long>>): List<ActivityRow> {
        val sorted = dated.sortedWith(
            compareByDescending<Listed> { it.moment }
                .thenBy { it.event }
                .thenByDescending { it.block ?: Long.MAX_VALUE },
        )
        val blocks = ArrayList<Long>(sorted.size)
        for ((at, listed) in sorted.withIndex()) {
            blocks += listed.block
                ?: Long.MAX_VALUE.takeIf { listed.pending }
                ?: sorted.drop(at + 1).firstNotNullOfOrNull { it.block }?.let { it + 1 }
                // None sent before it has a block: just below the newest sent after it that has one.
                ?: blocks.lastOrNull { it != Long.MAX_VALUE }
                ?: Long.MAX_VALUE
        }
        val rows = ArrayList<ActivityRow>(sorted.size + undated.size)
        var next = 0
        for ((at, listed) in sorted.withIndex()) {
            // The undated events of newer blocks than this row's go above it; of its own block, below.
            while (next < undated.size && undated[next].second > blocks[at]) rows += undated[next++].first
            rows += listed.row
        }
        while (next < undated.size) rows += undated[next++].first
        return rows
    }

    /**
     * What the list covers on chain: from which block on ([since] says why from there) each of the
     * [parts] ("its events" and its history) is listed, and how far each is checked; or why none is.
     */
    private fun onChainCoverage(parts: List<Pair<String, IndexedHistory<*>>>, since: String): String {
        val start = parts.firstNotNullOfOrNull { (_, history) ->
            (history as? IndexedHistory.Read)?.start ?: (history as? IndexedHistory.Checking)?.start
        }
        val all = parts.joinToString(" and ") { it.first }.replaceFirstChar { it.uppercase() }
        if (start == null) {
            return if (parts.all { it.second == IndexedHistory.NotFollowed }) {
                "$all on chain are listed once the light client is synced."
            } else {
                "$all on chain cannot be read right now."
            }
        }
        val said = parts.joinToString("; ") { (what, history) ->
            when (history) {
                is IndexedHistory.Read -> if (history.complete) {
                    what
                } else {
                    "$what, checked from block ${grouped(history.checked.first)} so far"
                }
                is IndexedHistory.Checking -> "$what, being checked"
                IndexedHistory.Unavailable -> "$what, which cannot be read right now"
                IndexedHistory.NotFollowed -> "$what, once the light client is synced"
            }
        }
        return "On chain from block ${grouped(start)} on, $since: $said."
    }

    /**
     * A row that is dated: a sent transaction's, the [block] it was seen in (null: none seen),
     * whether it is [pending] (no block seen yet, nor its nonce used) and its [moment] (unix
     * seconds), when its block was produced where that is known, else when a peer took it; or an
     * [event]'s, in its block, dated by the engine.
     */
    private class Listed(
        val row: ActivityRow,
        val block: Long?,
        val pending: Boolean,
        val moment: Long,
        val event: Boolean = false,
    )

    /**
     * The block an ended order's row goes by: its fill's, or its cancellation's. One never filled has
     * none: the block that proved it is the finalized one when the proof ran, which can be days after
     * it was signed, so it goes where it was signed, as a transaction without a block does.
     */
    private val CowOrderEnd.placedAt: Long?
        get() = when (this) {
            is CowOrderEnd.Filled -> block
            is CowOrderEnd.Unfilled -> endBlock.takeIf { cancelled }
            CowOrderEnd.NotProvable -> null
        }

    /** Whether this transfer is what the filled [order] sold: of its token, from its account, its whole sale. */
    private fun TokenTransfer.soldIn(order: CowOrderRecord): Boolean =
        this is TokenTransfer.Fungible && from == order.account && token == order.order.sellToken &&
            amount == order.order.sellAmount

    /** Whether this transfer is what the filled [order] bought: its trade's buy, from its settlement to its account. */
    private fun TokenTransfer.boughtIn(order: CowOrderRecord): Boolean {
        val trade = (order.end as? CowOrderEnd.Filled)?.trade ?: return false
        return this is TokenTransfer.Fungible && from == order.settlement && to == order.account &&
            token == trade.buyToken && amount == trade.buyAmount
    }

    private companion object {
        /** What an account's Activity does not list yet: the network's own currency it received, among others. */
        fun notListedYet(network: Network) =
            "${network.nativeSymbol} it received, and transactions sent from it elsewhere, are not listed yet."

        const val COVERAGE = "Transactions sent from this device. Transactions received, and those sent " +
            "from this account elsewhere, are not listed yet."
        const val UNREADABLE = "The history of your transactions cannot be read right now. Serotine keeps trying."
    }
}
