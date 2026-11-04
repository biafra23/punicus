package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientControl
import eth.serotine.domain.repository.LightClientRun
import eth.serotine.domain.repository.NetworkSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** [FollowNetworks]: what each network's light client is told, as the wallet changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowNetworksTest {

    private val daily = Account(
        Network.SEPOLIA,
        Address.of("0x" + "11".repeat(20)),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )
    private val accounts = ListAccountRepository()
    private val on = MutableStateFlow(setOf(Network.SEPOLIA))
    private val settings = object : NetworkSettings {
        override fun observe(): Flow<Set<Network>> = on

        override suspend fun setEnabled(network: Network, on: Boolean): Boolean = error("not used by this test")
    }
    private val demand = NetworkDemand()

    /** What each network was told, in order. */
    private val toldEach = mutableMapOf<Network, MutableList<LightClientRun>>()

    /** What Sepolia, the network of [daily], was told. */
    private val told: List<LightClientRun>
        get() = toldEach[Network.SEPOLIA].orEmpty()

    private val control = object : LightClientControl {
        override fun set(network: Network, run: LightClientRun) {
            toldEach.getOrPut(network) { mutableListOf() } += run
        }
    }

    private fun TestScope.follow() = backgroundScope.launch {
        FollowNetworks(settings, accounts, demand, control, idleAfter = 30.seconds)()
    }.also { runCurrent() }

    @Test
    fun aNetworkWithAnAccountRunsAndOneWithoutIsIdleAtOnce() = runTest {
        follow()
        assertEquals(listOf(LightClientRun.IDLE), told, "nothing needs it yet")

        accounts.add(daily)
        runCurrent()
        assertEquals(listOf(LightClientRun.IDLE, LightClientRun.RUN), told)
    }

    @Test
    fun aNetworkNoLongerNeededIsIdleOnlyAfterTheDelayAndOneNeededAgainKeepsRunning() = runTest {
        accounts.add(daily)
        follow()
        assertEquals(listOf(LightClientRun.RUN), told)

        accounts.remove(daily.id)
        advanceTimeBy(29.seconds)
        runCurrent()
        assertEquals(listOf(LightClientRun.RUN), told, "not before the delay")

        val hold = demand.hold(Network.SEPOLIA)
        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(listOf(LightClientRun.RUN), told, "a screen opened meanwhile keeps it running")

        hold.release()
        hold.release() // a second release does nothing
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(listOf(LightClientRun.RUN, LightClientRun.IDLE), told)
    }

    @Test
    fun aNetworkTurnedOffIsOffAtOnceWhateverNeedsIt() = runTest {
        accounts.add(daily)
        follow()

        on.value = emptySet()
        runCurrent()
        assertEquals(listOf(LightClientRun.RUN, LightClientRun.OFF), told)

        on.value = setOf(Network.SEPOLIA)
        runCurrent()
        assertEquals(listOf(LightClientRun.RUN, LightClientRun.OFF, LightClientRun.RUN), told)
    }

    @Test
    fun eachNetworkIsToldApart() = runTest {
        accounts.add(daily)
        follow()
        assertEquals<List<LightClientRun>?>(listOf(LightClientRun.OFF), toldEach[Network.ETHEREUM], "Ethereum is off")

        on.value = setOf(Network.ETHEREUM, Network.SEPOLIA)
        runCurrent()
        assertEquals<List<LightClientRun>?>(
            listOf(LightClientRun.OFF, LightClientRun.IDLE),
            toldEach[Network.ETHEREUM],
            "on, no account",
        )
        assertEquals(listOf(LightClientRun.RUN), told, "Sepolia's is untouched")
    }

    @Test
    fun anAccountListThatCannotBeReadKeepsANetworkThatIsOnRunning() = runTest {
        accounts.unreadable = true
        follow()

        assertEquals(listOf(LightClientRun.RUN), told)
    }
}
