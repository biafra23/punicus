package eth.serotine.ui

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.CowApi
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderLife
import eth.serotine.domain.repository.CowOrderList
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.CowOrders
import eth.serotine.domain.repository.CowPlacement
import eth.serotine.domain.repository.CowQuoteReading
import eth.serotine.domain.repository.CowUnavailable
import eth.serotine.domain.repository.CowVenueReading
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeQuote
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.HistoryStartList
import eth.serotine.domain.repository.HistoryStarts
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatch
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.SwapVenueSetting
import eth.serotine.domain.repository.VenueChoice
import eth.serotine.domain.repository.WaitingSwap
import eth.serotine.domain.repository.WaitingSwaps
import eth.serotine.domain.repository.prunable
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TransactionHistory
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.repository.prunable
import eth.serotine.domain.repository.yieldsTo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** One gwei, in wei. */
internal const val GWEI = 1_000_000_000L

/**
 * [TransactionInputs] answering what a test sets: the nonce 7, a base fee of 10 gwei with tips of
 * 1, 2 and 3 gwei, 21000 gas and a simulation that goes through at block 100 until it says other.
 */
internal class FakeTransactionInputs : TransactionInputs {
    var nonce = 7L
    var mined = 7L
    var fees: FeeReading = FeeReading.Quoted(
        FeeQuote(
            nextBaseFee = UInt256.of(10 * GWEI),
            tips = mapOf(
                FeeLevel.SLOW to UInt256.of(1 * GWEI),
                FeeLevel.NORMAL to UInt256.of(2 * GWEI),
                FeeLevel.FAST to UInt256.of(3 * GWEI),
            ),
            blockNumber = 99,
        ),
    )
    var estimate: GasEstimate = GasEstimate.Estimated(21_000)
    var simulation: Simulation = Simulation.Succeeded("", 100)

    /** What a run of a contract answers instead of [simulation]: a token's allowance, a router's swap. */
    val runs = mutableMapOf<Address, Simulation>()

    /** What a run of one call answers, before [runs]: null where [runs] or [simulation] answer it. */
    var runOf: (to: Address, data: ByteArray) -> Simulation? = { _, _ -> null }

    /** Each transfer estimated: from, to and value. */
    val estimated = mutableListOf<Triple<Address, Address, UInt256>>()

    override suspend fun nonce(network: Network, account: Address): NonceReading = NonceReading.Read(nonce)

    override suspend fun minedNonce(network: Network, account: Address): NonceReading = NonceReading.Read(mined)

    /** While set, the fees and every run wait for it: a quote in flight, for as long as a test needs. */
    var gate: CompletableDeferred<Unit>? = null

    /** How often the fees were asked. */
    var feeReads = 0

    override suspend fun fees(network: Network): FeeReading {
        feeReads++
        gate?.await()
        return fees
    }

    override suspend fun estimateGas(
        network: Network,
        from: Address,
        to: Address,
        value: UInt256,
        data: ByteArray,
    ): GasEstimate {
        estimated += Triple(from, to, value)
        return estimate
    }

    override suspend fun simulate(
        network: Network,
        from: Address,
        to: Address,
        value: UInt256,
        data: ByteArray,
    ): Simulation {
        gate?.await()
        return runOf(to, data) ?: runs[to] ?: simulation
    }
}

/**
 * A [Signer] that answers [answer], or, while [pending] is set, waits for it; each request is
 * kept in [asked], and each password it was given in [passwords]. With a [recovery], what it signs
 * is that recovery's made-up signature of the account, which recovers to it, as a Safe owner's must.
 */
internal class FakeSigner(private val recovery: FakeRecovery? = null) : Signer {
    var answer: Signing = Signing.Signed(SIGNATURE)
    var pending: CompletableDeferred<Signing>? = null
    val asked = mutableListOf<Pair<Address, Unlock>>()
    val passwords = mutableListOf<String>()

    override suspend fun sign(account: Address, digest: Digest, unlock: Unlock): Signing {
        asked += account to unlock
        if (unlock is Unlock.WithPassword) passwords += unlock.password.text
        val answered = pending?.await() ?: answer
        return if (answered is Signing.Signed && recovery != null) {
            Signing.Signed(recovery.sign(account, digest))
        } else {
            answered
        }
    }

    companion object {
        /** A well-formed signature: what the fake signs everything with. */
        val SIGNATURE = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), recoveryId = 0)
    }
}

/** A [Broadcaster] answering each send from [answers] in turn, then [Broadcast.Sent]; the bytes it got in [sent]. */
internal class FakeBroadcaster : Broadcaster {
    val answers = ArrayDeque<Broadcast>()
    val sent = mutableListOf<ByteArray>()

    override suspend fun send(network: Network, raw: ByteArray): Broadcast {
        sent += raw
        return answers.removeFirstOrNull() ?: Broadcast.Sent("0x" + "00".repeat(32))
    }
}

/** [SentTransfers] in memory. */
internal class MemorySentTransfers : SentTransfers {
    val transfers = MutableStateFlow<List<SentTransfer>>(emptyList())

    override fun observe(): Flow<SentTransferList> = transfers.map { SentTransferList.Loaded(it) }

    override suspend fun record(transfer: SentTransfer): Boolean {
        transfers.value = transfers.value.filterNot { it.hash == transfer.hash } + transfer
        return true
    }


    override suspend fun prune(account: AccountId, minedCount: Long, unlistedBefore: Long): Boolean {
        transfers.value = transfers.value.filterNot { it.prunable(account, minedCount, unlistedBefore) }
        return true
    }
}

/**
 * A [NameResolver] answering from [on] for a name on one registry, else from [names], and
 * [NameResolution.NoAddress] otherwise; each name asked in [asked].
 */
internal class FakeNames : NameResolver {
    val names = mutableMapOf<String, NameResolution>()

    /** A registry's own answers, by its network and the name: where two registries differ. */
    val on = mutableMapOf<Pair<Network, String>, NameResolution>()
    val asked = mutableListOf<String>()

    /** The network of each name asked, in order. */
    val networks = mutableListOf<Network>()

    override suspend fun resolve(network: Network, name: String): NameResolution {
        asked += name
        networks += network
        return on[network to name] ?: names[name] ?: NameResolution.NoAddress
    }
}

/** A [ReceiptTracker] whose progress per hash a test sets; Pending until then. */
internal class FakeReceipts : ReceiptTracker {
    private val progress = mutableMapOf<String, MutableStateFlow<TransactionProgress>>()

    fun of(hash: String) = progress.getOrPut(hash) { MutableStateFlow(TransactionProgress.Pending) }

    override fun observe(network: Network, hash: String): Flow<TransactionProgress> = of(hash)
}

/** A [TransactionHistory] in memory, as the port's contract says one behaves; [unreadable] reads as such. */
internal class MemoryTransactionHistory : TransactionHistory {
    val entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val unreadable = MutableStateFlow(false)

    override fun observe(): Flow<HistoryList> = combine(entries, unreadable) { list, broken ->
        if (broken) HistoryList.Unreadable else HistoryList.Loaded(list)
    }

    override suspend fun record(transfer: SentTransfer): Boolean {
        val outcome = entries.value.lastOrNull { it.transfer.hash == transfer.hash }?.outcome ?: SentOutcome.NotSeen
        entries.value = entries.value.filterNot { it.transfer.hash == transfer.hash } + HistoryEntry(transfer, outcome)
        return true
    }

    override suspend fun settle(hash: String, outcome: SentOutcome): Boolean {
        val current = entries.value.lastOrNull { it.transfer.hash == hash }?.outcome ?: return false
        if (!current.yieldsTo(outcome)) return false
        entries.value = entries.value.map { if (it.transfer.hash == hash) it.copy(outcome = outcome) else it }
        return true
    }
}

/** [HistoryStarts] in memory: the [starts] a test sets. */
internal class MemoryHistoryStarts : HistoryStarts {
    val starts = MutableStateFlow<Map<Address, HistoryStart>>(emptyMap())

    // One network's starts, by address: these tests are all Sepolia's.
    override fun observe(): Flow<HistoryStartList> =
        starts.map {
            held -> HistoryStartList.Loaded(held.mapKeys { (address, _) -> AccountId(Network.SEPOLIA, address) }) }

    override suspend fun note(id: AccountId, atSeconds: Long): Boolean {
        val address = id.address
        starts.value += address to HistoryStart.Noted(atSeconds)
        return true
    }

    override suspend fun fix(id: AccountId, block: Long): Boolean {
        val address = id.address
        if (starts.value[address] is HistoryStart.Fixed) return false
        starts.value += address to HistoryStart.Fixed(block)
        return true
    }

    override suspend fun forget(id: AccountId, start: HistoryStart): Boolean {
        val address = id.address
        if (starts.value[address] == start) starts.value -= address
        return true
    }
}

/** A [LogIndex] whose [coverage] and [logs] a test sets; it takes every change asked of it. */
internal class FakeLogIndex : LogIndex {
    val coverage = MutableStateFlow<LogCoverageReading>(LogCoverageReading.Unavailable)
    var logs: List<IndexedLog> = emptyList()

    override suspend fun configure(network: Network, watch: List<LogWatch>, unwatch: List<LogWatchKey>): Boolean = true

    override fun observeCoverage(network: Network): Flow<LogCoverageReading> = coverage

    /** The [logs] in the query's range that it selects, as the engine selects them. */
    override suspend fun logs(network: Network, query: LogQuery): LogsReading = LogsReading.Logs(
        logs.filter { log ->
            val topics = log.log.topics
            log.blockNumber in query.fromBlock..query.toBlock &&
                (query.topic0s.isEmpty() || topics.firstOrNull() in query.topic0s) &&
                when (val key = query.key) {
                    is LogWatchKey.Emitter -> log.log.address == key.address
                    is LogWatchKey.Mention -> topics.getOrNull(query.position ?: 0) == topicOf(key.address)
                }
        },
    )
}

/** [address] as a 32-byte log topic: left-padded with zeros. */
internal fun topicOf(address: Address): String = "0x" + "00".repeat(12) + address.hex.removePrefix("0x")

/** The record of CoW orders in memory, as the port's contract says one behaves; empty until a test adds one. */
internal class MemoryCowOrders : CowOrders {
    val orders = MutableStateFlow<List<CowOrderRecord>>(emptyList())

    override fun observe(): Flow<CowOrderList> = orders.map { CowOrderList.Loaded(it) }

    override suspend fun record(order: CowOrderRecord): Boolean {
        orders.value = if (orders.value.any { it.uid == order.uid }) {
            orders.value.map { if (it.uid == order.uid) order else it }
        } else {
            orders.value + order
        }
        return true
    }

    override suspend fun end(uid: CowOrderUid, end: CowOrderEnd): Boolean {
        if (orders.value.none { it.uid == uid }) return false
        orders.value = orders.value.map { if (it.uid == uid) it.copy(end = end) else it }
        return true
    }

    override suspend fun prune(signedBefore: Long): Boolean {
        orders.value = orders.value.filterNot { !it.live && it.signedAt < signedBefore }
        return true
    }
}

/** CoW's contracts as a chain: [venue] (none by default), and no answer to a call unless [answers] has one. */
internal class FakeCowChain : CowChain {
    var venue: CowVenueReading = CowVenueReading.NotPinned
    val answers = mutableMapOf<Pair<Address, String>, Simulation>()

    override suspend fun venue(network: Network): CowVenueReading = venue

    override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation =
        answers[to to data.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }]
            ?: Simulation.Unavailable(ReadUnavailable.UNPROVEN)
}

/**
 * CoW's order API as a fake: [quote] for every quote, [placement] (CoW's acceptance by default) for
 * every order, and [life] for every order asked about, once [lifePending] completes when it is set.
 */
internal class FakeCowApi : CowApi {
    var quote: CowQuoteReading = CowQuoteReading.Unavailable(CowUnavailable.NO_CONNECTION)
    var placement: CowPlacement? = null
    val quotes = mutableListOf<Address>()
    val placed = mutableListOf<CowOrderUid>()

    override suspend fun quote(
        network: Network,
        sellToken: Address,
        buyToken: Address,
        sellAmount: UInt256,
        from: Address,
    ): CowQuoteReading {
        quotes += sellToken
        return quote
    }

    override suspend fun place(
        network: Network,
        order: CowOrder,
        owner: Address,
        signature: Signature,
        uid: CowOrderUid,
    ): CowPlacement {
        placed += uid
        return placement ?: CowPlacement.Accepted(uid)
    }

    var life: CowOrderLife = CowOrderLife.Open
    val lives = mutableListOf<CowOrderUid>()

    /** While set, an order's life waits for it: CoW slow to answer, for as long as a test needs. */
    var lifePending: CompletableDeferred<CowOrderLife>? = null

    override suspend fun life(network: Network, uid: CowOrderUid): CowOrderLife {
        lives += uid
        return lifePending?.await() ?: life
    }
}

/** The swaps whose next step waits, in memory: one per account and token sold, as the file keeps them. */
internal class MemoryWaitingSwaps : WaitingSwaps {
    val swaps = MutableStateFlow<List<WaitingSwap>>(emptyList())

    override fun observe(): Flow<List<WaitingSwap>> = swaps

    override suspend fun keep(swap: WaitingSwap): Boolean {
        swaps.update { list -> list.filterNot { it.account == swap.account && it.sell == swap.sell } + swap }
        return true
    }

    override suspend fun drop(account: AccountId, sell: Address): Boolean {
        swaps.update { list -> list.filterNot { it.account == account && it.sell == sell } }
        return true
    }
}

/** The venue last chosen, in memory: none until a test or a choice sets one. */
internal class MemorySwapVenue : SwapVenueSetting {
    val choice = MutableStateFlow<VenueChoice>(VenueChoice.Chosen(null))

    override fun observe(): Flow<VenueChoice> = choice

    override suspend fun choose(venue: SwapVenue): Boolean {
        choice.value = VenueChoice.Chosen(venue)
        return true
    }
}
