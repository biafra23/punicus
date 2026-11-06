package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SyncPolicy
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.SyncPolicySetting
import eth.serotine.domain.repository.TransactionInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class KeepTransactionInputsTest {

    private val held = account("aa", AccountKind.EOA, AccountCustody.InKeyStore)
    private val watched = account("bb", AccountKind.EOA, AccountCustody.Watched)
    private val safe = account("cc", AccountKind.SAFE, AccountCustody.Watched)
    private val elsewhere = account("dd", AccountKind.EOA, AccountCustody.InKeyStore, Network.ETHEREUM)

    private val policy = FakePolicy()
    private val lightClient = FakeLightClient()
    private val inputs = AskedInputs()
    private val accounts = ListAccountRepository(held, watched, safe, elsewhere)

    private fun TestScope.keeping() = KeepTransactionInputs(
        policy,
        accounts,
        lightClient,
        inputs,
        networks = listOf(Network.SEPOLIA),
        interval = 10.minutes,
        timeSource = testTimeSource,
    )

    @Test
    fun inStrictModeNothingIsAsked() = runTest {
        lightClient.status.value = synced()
        val job = launch { keeping()() }
        runCurrent()

        assertEquals(emptyList(), inputs.asked)
        job.cancel()
    }

    @Test
    fun inLenientModeTheFeesAndEachHeldEoasMinedCountAreReadOnceVerifiedAndThenEveryInterval() = runTest {
        policy.policy.value = SyncPolicy.LENIENT
        lightClient.status.value = LightClientStatus.CatchingUp(done = 1, total = 3)
        val job = launch { keeping()() }
        runCurrent()
        assertEquals(emptyList(), inputs.asked, "nothing is asked before the light client verifies")

        lightClient.status.value = synced()
        runCurrent()
        // Only the EOA whose key is here, on this network: not a watched one, a Safe, or another network's.
        assertEquals(listOf("fees", "nonce ${held.address.hex}"), inputs.asked)

        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(4, inputs.asked.size, "read again after the interval")

        // Catching up again stops the rounds; strict mode stops them too.
        lightClient.status.value = LightClientStatus.Sleeping
        runCurrent()
        advanceTimeBy(30.minutes)
        assertEquals(4, inputs.asked.size)
        job.cancel()
    }

    @Test
    fun turningLenientModeOffStopsTheReads() = runTest {
        policy.policy.value = SyncPolicy.LENIENT
        lightClient.status.value = synced()
        val job = launch { keeping()() }
        runCurrent()
        assertEquals(2, inputs.asked.size)

        policy.policy.value = SyncPolicy.STRICT
        runCurrent()
        advanceTimeBy(30.minutes)
        assertEquals(2, inputs.asked.size)
        job.cancel()
    }

    @Test
    fun aLightClientThatStopsAndStartsVerifyingWithinTheIntervalWaitsItOut() = runTest {
        policy.policy.value = SyncPolicy.LENIENT
        lightClient.status.value = synced()
        val job = launch { keeping()() }
        runCurrent()
        assertEquals(2, inputs.asked.size)

        // Two minutes later it loses its head, and finds a fresh one a minute after that.
        advanceTimeBy(2.minutes)
        lightClient.status.value = LightClientStatus.WaitingForHead(headAgeSeconds = 50)
        runCurrent()
        advanceTimeBy(1.minutes)
        lightClient.status.value = synced()
        runCurrent()
        assertEquals(2, inputs.asked.size, "no round before the interval is out")

        advanceTimeBy(7.minutes)
        runCurrent()
        assertEquals(4, inputs.asked.size, "the next round ten minutes after the first")
        job.cancel()
    }

    @Test
    fun aNetworkWithNoAccountToDraftFromAsksNothing() = runTest {
        accounts.accounts.value = listOf(watched, safe, elsewhere)
        policy.policy.value = SyncPolicy.LENIENT
        lightClient.status.value = synced()
        val job = launch { keeping()() }
        runCurrent()

        assertEquals(emptyList(), inputs.asked)
        job.cancel()
    }

    private fun synced() = LightClientStatus.Synced(headBlock = 100, headTimestamp = null, finalized = null)

    private fun account(byte: String, kind: AccountKind, custody: AccountCustody, network: Network = Network.SEPOLIA) =
        Account(network, Address.of("0x" + byte.repeat(20)), kind, "Account $byte", custody)

    private class FakePolicy : SyncPolicySetting {
        val policy = MutableStateFlow(SyncPolicy.STRICT)

        override fun observe(): Flow<SyncPolicy> = policy

        override suspend fun set(policy: SyncPolicy): Boolean {
            this.policy.value = policy
            return true
        }
    }

    /** Records what is asked; answers nothing verified, which this use case does not look at. */
    private class AskedInputs : TransactionInputs {
        val asked = mutableListOf<String>()

        override suspend fun nonce(network: Network, account: Address): NonceReading =
            NonceReading.Unavailable(ReadUnavailable.SYNCING)

        override suspend fun minedNonce(network: Network, account: Address): NonceReading {
            asked += "nonce ${account.hex}"
            return NonceReading.Read(3)
        }

        override suspend fun fees(network: Network): FeeReading {
            asked += "fees"
            return FeeReading.Unavailable(ReadUnavailable.SYNCING)
        }

        override suspend fun estimateGas(
            network: Network,
            from: Address,
            to: Address,
            value: UInt256,
            data: ByteArray,
        ): GasEstimate = GasEstimate.Unavailable(ReadUnavailable.SYNCING)

        override suspend fun simulate(
            network: Network,
            from: Address,
            to: Address,
            value: UInt256,
            data: ByteArray,
        ): Simulation = Simulation.Unavailable(ReadUnavailable.SYNCING)
    }
}
