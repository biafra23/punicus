package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.StaleAnchorConsent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/** [SyncPastStaleAnchor]: the consent asked of a light client parked on a stale anchor, and of no other. */
class SyncPastStaleAnchorTest {

    private val status = MutableStateFlow<LightClientStatus>(LightClientStatus.StaleAnchor(6, 3))
    private val lightClient = object : LightClient {
        override fun observeStatus(network: Network): Flow<LightClientStatus> = status

        override val datesFinalizedBlocks: Boolean = true
    }
    private val consented = mutableListOf<Network>()
    private val consent = object : StaleAnchorConsent {
        override fun syncPastStaleAnchor(network: Network) {
            consented += network
        }
    }

    @Test
    fun aLightClientParkedOnAStaleAnchorIsLetSyncPastIt() = runTest {
        assertEquals(StaleAnchorSync.Consented, SyncPastStaleAnchor(lightClient, consent)(Network.GNOSIS))
        assertEquals(listOf(Network.GNOSIS), consented)
    }

    @Test
    fun oneThatIsNotRefusingIsLeftAlone() = runTest {
        status.value = LightClientStatus.CatchingUp(done = 2, total = 6)

        assertEquals(StaleAnchorSync.NotRefused, SyncPastStaleAnchor(lightClient, consent)(Network.GNOSIS))
        assertEquals(emptyList(), consented, "the engine arms the consent unconditionally")
    }
}
