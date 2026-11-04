package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.NetworkUpgrades
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * [ObserveUpgradeWarnings]: each network's warning apart, and the update screen only when every
 * network the wallet follows (on, with accounts) can no longer be followed (docs/networks.md).
 */
class ObserveUpgradeWarningsTest {

    private val activated = glamsterdam.copy(phase = NetworkUpgrade.Phase.ACTIVE)
    private val afterIt = glamsterdam.activationTime!! + DAY

    private val reports = Network.entries.associateWith { MutableStateFlow<NetworkUpgrade?>(null) }
    private val statuses = Network.entries.associateWith { MutableStateFlow<LightClientStatus>(synced) }
    private val upgrades = object : NetworkUpgrades {
        override fun observeUpgrade(network: Network): Flow<NetworkUpgrade?> = reports.getValue(network)
    }
    private val known = object : KnownUpgradeStore {
        override fun observe(network: Network): Flow<KnownUpgrade?> = flowOf(null)

        override suspend fun save(network: Network, record: KnownUpgrade): Boolean = error("not used by this test")
    }
    private val lightClient = object : LightClient {
        override val datesFinalizedBlocks = true

        override fun observeStatus(network: Network): Flow<LightClientStatus> = statuses.getValue(network)
    }
    private val on = MutableStateFlow(setOf(Network.ETHEREUM, Network.SEPOLIA))
    private val settings = object : NetworkSettings {
        override fun observe(): Flow<Set<Network>> = on

        override suspend fun setEnabled(network: Network, on: Boolean): Boolean = error("not used by this test")
    }
    private val address = Address.of("0x" + "11".repeat(20))
    private val accounts = ListAccountRepository(
        Account(Network.ETHEREUM, address, AccountKind.EOA, "Main", AccountCustody.InKeyStore),
        Account(Network.SEPOLIA, address, AccountKind.EOA, "Test", AccountCustody.InKeyStore),
    )

    private suspend fun warnings() =
        ObserveUpgradeWarnings(upgrades, known, lightClient, settings, accounts) { afterIt }().first()

    /** [network] upgraded under this version, and its light client is not verifying. */
    private fun block(network: Network) {
        reports.getValue(network).value = activated
        statuses.getValue(network).value = LightClientStatus.CatchingUp(1, 3)
    }

    @Test
    fun aNetworkBlockedAloneIsItsOwnWarningAndNoUpdateScreen() = runTest {
        block(Network.SEPOLIA)

        val read = warnings()
        assertIs<UpgradeWarning.UpdateRequired>(read.of(Network.SEPOLIA))
        assertEquals(UpgradeWarning.None, read.of(Network.ETHEREUM))
        assertTrue(read.updateRequired.isEmpty(), "Ethereum is still followed")
    }

    @Test
    fun everyNetworkFollowedBlockedIsTheUpdateScreen() = runTest {
        block(Network.SEPOLIA)
        block(Network.ETHEREUM)

        assertEquals(setOf(Network.ETHEREUM, Network.SEPOLIA), warnings().updateRequired.keys)
    }

    @Test
    fun aNetworkThatIsOffOrHasNoAccountNeverCountsTowardsIt() = runTest {
        block(Network.SEPOLIA)
        on.value = setOf(Network.SEPOLIA)
        assertEquals(setOf(Network.SEPOLIA), warnings().updateRequired.keys, "Ethereum is off")

        on.value = setOf(Network.ETHEREUM, Network.SEPOLIA)
        accounts.accounts.value = accounts.accounts.value.filter { it.network == Network.SEPOLIA }
        assertEquals(setOf(Network.SEPOLIA), warnings().updateRequired.keys, "Ethereum has no account")

        // A wallet with no account follows what is on: Sepolia alone here, blocked.
        on.value = setOf(Network.SEPOLIA)
        accounts.accounts.value = emptyList()
        assertEquals(setOf(Network.SEPOLIA), warnings().updateRequired.keys)
        on.value = setOf(Network.ETHEREUM, Network.SEPOLIA)
        assertTrue(warnings().updateRequired.isEmpty(), "Ethereum, on, still follows")
    }

    private companion object {
        val synced = LightClientStatus.Synced(9, glamsterdam.activationTime!! + DAY - 8, finalized = null)
    }
}
