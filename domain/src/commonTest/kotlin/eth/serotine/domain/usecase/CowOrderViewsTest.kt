package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderLife
import eth.serotine.domain.repository.CowOrderRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CowOrderViewsTest {

    private val usdc = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USD Coin", 6, icon = null)
    private val alice = Address.of("0x" + "ac".repeat(20))
    private val bob = Address.of("0x" + "b0".repeat(20))

    private fun order(account: Address, signedAt: Long): CowOrderRecord {
        val order =
            CowOrder(usdc.address, CowOrder.NATIVE_BUY_TOKEN, account, UInt256.of(1_000_000), UInt256.ONE, signedAt + 300)
        return CowOrderRecord(
            account = account,
            network = Network.SEPOLIA,
            settlement = COW_SEPOLIA.settlement,
            order = order,
            uid = order.uid(Network.SEPOLIA.chainId, COW_SEPOLIA.settlement, account),
            signature = Signature("0".repeat(63) + "1", "0".repeat(63) + "1", 0),
            sell = TransferAsset.Erc20Token(usdc),
            buy = TransferAsset.Native,
            signedAt = signedAt,
            rangeStart = 100,
        )
    }

    private val first = order(alice, 1_792_000_000)
    private val second = order(alice, 1_792_000_600)
    private val bobs = order(bob, 1_792_000_300)
    private val orders = MemoryCowOrders(first, bobs, second)

    @Test
    fun anOrderIsReadByItsUidWithItsEndAsProven() = runTest {
        val observe = ObserveCowOrder(orders, Network.SEPOLIA)
        assertEquals(CowOrderReading.Found(second), observe(second.uid).first())
        val ended = second.copy(end = CowOrderEnd.Unfilled(120, cancelled = false))
        orders.orders.value = listOf(first, ended)
        assertEquals(CowOrderReading.Found(ended), observe(second.uid).first())
        assertEquals(CowOrderReading.NotHere, observe(bobs.uid).first())
        orders.unreadable = true
        assertEquals(CowOrderReading.Unreadable, observe(first.uid).first())
    }

    @Test
    fun anAccountsOrdersAreItsOwnNewestFirst() = runTest {
        val observe = ObserveAccountCowOrders(orders)
        assertEquals(listOf(second, first), observe(AccountId(Network.SEPOLIA, alice)).first())
        assertEquals(listOf(bobs), observe(AccountId(Network.SEPOLIA, bob)).first())
        orders.unreadable = true
        assertEquals(emptyList(), observe(AccountId(Network.SEPOLIA, alice)).first())
    }

    @Test
    fun cowsWordOnAnOrderIsAskedWhenCollectedAndAgainEveryInterval() = runTest {
        val api = FakeCowApi()
        val lives = mutableListOf<CowOrderLife>()
        val observe = ObserveCowOrderLife(api, Network.SEPOLIA)
        val collecting = launch { observe(first.uid, every = 30.seconds).collect { lives += it } }
        runCurrent()
        assertEquals(listOf<CowOrderLife>(CowOrderLife.Open), lives)
        api.life = CowOrderLife.Expired
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(listOf(CowOrderLife.Open, CowOrderLife.Expired), lives)
        assertEquals(listOf(first.uid, first.uid), api.lives)
        collecting.cancel()
    }
}
