package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.WaitingSwap
import eth.serotine.domain.repository.WaitingSwaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest

class WaitingSwapStepsTest {

    private class MemoryWaitingSwaps : WaitingSwaps {
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

    private val store = MemoryWaitingSwaps()
    private val main = AccountId(Network.SEPOLIA, Address.of("0x" + "11".repeat(20)))
    private val other = AccountId(Network.ETHEREUM, main.address)
    private val usdc = Address.of("0x" + "aa".repeat(20))

    private fun swap(account: AccountId = main, approval: String = "0x" + "01".repeat(32)) = WaitingSwap(
        account = account,
        sell = usdc,
        buy = null,
        amount = UInt256.of(40_000_000),
        tolerance = SlippageTolerance.DEFAULT,
        venue = SwapVenue.UNISWAP,
        approval = approval,
        step = 2,
        steps = 2,
    )

    @Test
    fun eachAccountSeesItsOwnAndContinueFindsItByTheTokenSold() = runTest {
        KeepWaitingSwap(store)(swap())
        KeepWaitingSwap(store)(swap(other))

        assertEquals(listOf(swap()), ObserveWaitingSwaps(store)(main).first())
        assertEquals(swap(other), FindWaitingSwap(store)(other, usdc))
        assertNull(FindWaitingSwap(store)(main, Address.of("0x" + "bb".repeat(20))))
    }

    @Test
    fun aNewerApprovalOfTheSameTokenTakesThePlaceOfTheOlderAndASentSwapLetsItGo() = runTest {
        KeepWaitingSwap(store)(swap())
        val newer = swap(approval = "0x" + "02".repeat(32))
        KeepWaitingSwap(store)(newer)
        assertEquals(listOf(newer), ObserveWaitingSwaps(store)(main).first())

        DropWaitingSwap(store)(main, usdc)
        assertEquals(emptyList(), ObserveWaitingSwaps(store)(main).first())
    }

    @Test
    fun onlyAStepAfterOneThatWasSentWaits() {
        assertFailsWith<IllegalArgumentException> { swap().copy(step = 1) }
        assertFailsWith<IllegalArgumentException> { swap().copy(step = 3, steps = 2) }
    }
}
