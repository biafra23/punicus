package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.ApprovalReading
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.CowVenueReading
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/** [NetworkRoutedPorts]: a call reaches its network's light client, and a network without one is refused. */
class NetworkRoutedPortsTest {

    private val address = Address.of("0x" + "ab".repeat(20))

    @Test
    fun aNetworkWithoutALightClientIsRefusedOnEveryPort() = runTest {
        val routed = NetworkRoutedPorts(emptyMap())
        val network = Network.SEPOLIA
        val noEngine = ReadUnavailable.NO_ENGINE

        assertEquals(BalanceReading.Unavailable(noEngine), routed.nativeBalances.observeBalance(network, address).first())
        assertEquals(KindReading.Unavailable(noEngine), routed.addressKinds.observeKind(network, address).first())
        assertEquals(LightClientStatus.Unavailable, routed.lightClient.observeStatus(network).first())
        assertNull(routed.networkUpgrades.observeUpgrade(network).first())
        assertEquals(emptyMap(), routed.priceFeeds.quotes(network))
        assertEquals(NonceReading.Unavailable(noEngine), routed.transactionInputs.nonce(network, address))
        assertEquals(FeeReading.Unavailable(noEngine), routed.transactionInputs.fees(network))
        assertEquals(
            Simulation.Unavailable(noEngine),
            routed.transactionInputs.simulate(network, address, address, UInt256.ZERO, ByteArray(0)),
        )
        assertEquals(
            Broadcast.Unavailable("no light client for Sepolia on this device"),
            routed.broadcaster.send(network, byteArrayOf(2)),
        )
        assertEquals(NameResolution.Unavailable(noEngine), routed.names.resolve(network, "serotine.eth"))
        assertEquals(
            ApprovalReading.Unavailable(noEngine),
            routed.safeApprovals.approval(network, address, address, checkNotNull(Digest.parse("00".repeat(32)))),
        )
        assertFalse(routed.logIndex.configure(network, emptyList(), emptyList()))
        assertEquals(LogCoverageReading.Unavailable, routed.logIndex.observeCoverage(network).first())
        assertEquals(CowVenueReading.Unavailable(noEngine), routed.cow.venue(network))
        assertEquals(Simulation.Unavailable(noEngine), routed.cow.call(network, address, ByteArray(0)))
    }

    @Test
    fun finalizedBlocksAreDatedOnlyWhenEveryLightClientDatesThem() {
        fun dating(dates: Boolean) = object : LightClient {
            override val datesFinalizedBlocks = dates

            override fun observeStatus(network: Network): Flow<LightClientStatus> = flowOf(LightClientStatus.Starting)
        }
        assertFalse(NetworkRoutedPorts(emptyMap()).lightClient.datesFinalizedBlocks, "no light client dates none")
        val dated = NetworkRoutedPorts(mapOf(Network.SEPOLIA to ports(light = dating(true))))
        assertTrue(dated.lightClient.datesFinalizedBlocks)
        val undated = NetworkRoutedPorts(mapOf(Network.SEPOLIA to ports(light = dating(false))))
        assertFalse(undated.lightClient.datesFinalizedBlocks)
    }

    @Test
    fun aCallReachesTheLightClientOfTheNetworkItNames() = runTest {
        val asked = mutableListOf<Network>()
        val sepolia = object : NativeBalances {
            override fun observeBalance(network: Network, address: Address): Flow<BalanceReading> {
                asked += network
                return flowOf(BalanceReading.Verified(Wei.of("7"), 9))
            }
        }
        val routed = NetworkRoutedPorts(mapOf(Network.SEPOLIA to ports(sepolia)))

        assertEquals(
            BalanceReading.Verified(Wei.of("7"), 9),
            routed.nativeBalances.observeBalance(Network.SEPOLIA, address).first(),
        )
        assertEquals(listOf(Network.SEPOLIA), asked)
    }

    /** A network's ports with [native] as its balances and [light] as its light client, nothing else asked. */
    private fun ports(
        native: NativeBalances = NetworkRoutedPorts(emptyMap()).nativeBalances,
        light: LightClient = NetworkRoutedPorts(emptyMap()).lightClient,
    ): ChainPorts {
        val none = NetworkRoutedPorts(emptyMap())
        return ChainPorts(
            nativeBalances = native,
            tokenBalances = none.tokenBalances,
            tokenMetadata = none.tokenMetadata,
            addressKinds = none.addressKinds,
            lightClient = light,
            networkUpgrades = none.networkUpgrades,
            priceFeeds = none.priceFeeds,
            priceHistory = none.priceHistory,
            transactionInputs = none.transactionInputs,
            broadcaster = none.broadcaster,
            receipts = none.receipts,
            names = none.names,
            safeApprovals = none.safeApprovals,
            uniswap = none.uniswap,
            cow = none.cow,
            logIndex = none.logIndex,
        )
    }
}
