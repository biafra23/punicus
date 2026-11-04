package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowDeployment
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CowApi
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderLife
import eth.serotine.domain.repository.CowOrderList
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.CowOrders
import eth.serotine.domain.repository.CowPlacement
import eth.serotine.domain.repository.CowQuoteReading
import eth.serotine.domain.repository.CowVenueReading
import eth.serotine.domain.repository.Simulation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** CoW's contracts on Sepolia, as pinned. */
internal val COW_SEPOLIA = CowDeployment(
    settlement = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41"),
    vaultRelayer = Address.of("0xc92e8bdf79f0507f65a392b0ab4667716bfe0110"),
)

/**
 * [CowOrders] in memory, as the port's contract says one behaves; [failSaves] makes every write
 * fail, and [unreadable] makes the record unreadable.
 */
internal class MemoryCowOrders(vararg initial: CowOrderRecord) : CowOrders {
    val orders = MutableStateFlow(initial.toList())
    var failSaves = false
    var unreadable = false

    override fun observe(): Flow<CowOrderList> =
        orders.map { if (unreadable) CowOrderList.Unreadable else CowOrderList.Loaded(it) }

    override suspend fun record(order: CowOrderRecord): Boolean {
        if (failSaves) return false
        orders.value = if (orders.value.any { it.uid == order.uid }) {
            orders.value.map { if (it.uid == order.uid) order else it }
        } else {
            orders.value + order
        }
        return true
    }

    override suspend fun end(uid: CowOrderUid, end: CowOrderEnd): Boolean {
        if (failSaves || orders.value.none { it.uid == uid }) return false
        orders.value = orders.value.map { if (it.uid == uid) it.copy(end = end) else it }
        return true
    }

    override suspend fun prune(signedBefore: Long): Boolean {
        if (failSaves) return false
        orders.value = orders.value.filterNot { !it.live && it.signedAt < signedBefore }
        return true
    }
}

/**
 * CoW's API as a test sets it: its [quote] and [placement] answers, and every call made ([quotes],
 * [placed]); [onPlace] sees the record of orders as it stands when an order is posted.
 */
internal class FakeCowApi : CowApi {
    var quote: CowQuoteReading = CowQuoteReading.Quoted(UInt256.ONE, UInt256.ZERO)
    var placement: CowPlacement? = null
    val quotes = mutableListOf<List<Any>>()
    val placed = mutableListOf<Pair<CowOrder, CowOrderUid>>()
    var onPlace: () -> Unit = {}

    override suspend fun quote(
        network: Network,
        sellToken: Address,
        buyToken: Address,
        sellAmount: UInt256,
        from: Address,
    ): CowQuoteReading {
        quotes += listOf(network, sellToken, buyToken, sellAmount, from)
        return quote
    }

    override suspend fun place(
        network: Network,
        order: CowOrder,
        owner: Address,
        signature: Signature,
        uid: CowOrderUid,
    ): CowPlacement {
        onPlace()
        placed += order to uid
        return placement ?: CowPlacement.Accepted(uid)
    }

    var life: CowOrderLife = CowOrderLife.Open
    val lives = mutableListOf<CowOrderUid>()

    override suspend fun life(network: Network, uid: CowOrderUid): CowOrderLife {
        lives += uid
        return life
    }
}

/** CoW's contracts on chain: [venue], and each call's answer from [answers] (a revert otherwise). */
internal class FakeCowChain : CowChain {
    var venue: CowVenueReading = CowVenueReading.Verified(COW_SEPOLIA)
    val answers = mutableMapOf<Pair<Address, String>, Simulation>()

    override suspend fun venue(network: Network): CowVenueReading = venue

    override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation =
        answers[to to data.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }]
            ?: Simulation.Reverted(120)
}
