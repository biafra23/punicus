package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.CowApi
import eth.serotine.domain.repository.CowOrderList
import eth.serotine.domain.repository.CowOrderLife
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.CowOrders
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One CoW order signed on this device on [network], by its UID, as the record of orders has it, its
 * end once proven from the chain (docs/swaps.md, *Proving an order's end*): what its status screen
 * shows. An order of another network is not this one (docs/networks.md: never answered from another).
 */
class ObserveCowOrder(private val cowOrders: CowOrders, private val network: Network) {
    operator fun invoke(uid: CowOrderUid): Flow<CowOrderReading> = cowOrders.observe().map { list ->
        when (list) {
            is CowOrderList.Loaded ->
                list.orders.firstOrNull { it.uid == uid && it.network == network }?.let(CowOrderReading::Found)
                    ?: CowOrderReading.NotHere
            CowOrderList.Unreadable -> CowOrderReading.Unreadable
        }
    }.distinctUntilChanged()
}

/** What [ObserveCowOrder] emits. */
sealed interface CowOrderReading {

    /** The order, as recorded. */
    data class Found(val record: CowOrderRecord) : CowOrderReading

    /** No such order is recorded here: never signed on this device, or ended and dropped since. */
    data object NotHere : CowOrderReading

    /** The record of orders cannot be read right now. */
    data object Unreadable : CowOrderReading
}

/**
 * The CoW orders the account signed on this device, on its network, newest first, for its
 * Activity; none while the record cannot be read (Activity then lists what else it has).
 */
class ObserveAccountCowOrders(private val cowOrders: CowOrders) {
    operator fun invoke(account: AccountId): Flow<List<CowOrderRecord>> = cowOrders.observe().map { list ->
        (list as? CowOrderList.Loaded)?.orders.orEmpty()
            .filter { it.account == account.address && it.network == account.network }
            .sortedByDescending { it.signedAt }
    }.distinctUntilChanged()
}

/**
 * CoW's own word on an order's off-chain life, asked of CoW's API on [network] when collected and
 * again every [every] while it is: of CoW's answers about an order, the one thing shown, labelled
 * as CoW's (owner ruling, 2026-10-22; docs/swaps.md, *What CoW learns*). Never a fill: whether it
 * filled, and what it bought, is the chain's to say ([ObserveCowOrder]). CoW already holds the
 * order it is asked about, so asking tells it nothing new.
 */
class ObserveCowOrderLife(private val cowApi: CowApi, private val network: Network) {
    operator fun invoke(uid: CowOrderUid, every: Duration = 30.seconds): Flow<CowOrderLife> = flow {
        while (true) {
            emit(cowApi.life(network, uid))
            delay(every)
        }
    }
}
