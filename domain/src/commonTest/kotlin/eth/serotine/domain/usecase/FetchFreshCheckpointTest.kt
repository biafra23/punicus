package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.FreshCheckpoint
import eth.serotine.domain.repository.FreshCheckpoints
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/** [FetchFreshCheckpoint]: the servers asked for a light client parked on a stale anchor, and for no other. */
class FetchFreshCheckpointTest {

    private val status = MutableStateFlow<LightClientStatus>(LightClientStatus.StaleAnchor(6, 3))
    private val lightClient = object : LightClient {
        override fun observeStatus(network: Network): Flow<LightClientStatus> = status

        override val datesFinalizedBlocks: Boolean = true
    }
    private val asked = mutableListOf<Pair<Network, Boolean>>()
    private val applied =
        FreshCheckpoint.Applied(epoch = 1_234, operators = listOf("a.io", "b.org"), confirmedBy = listOf("peer"))
    private val checkpoints = object : FreshCheckpoints {
        override suspend fun fetchFreshCheckpoint(network: Network, useUnconfirmed: Boolean): FreshCheckpoint {
            asked += network to useUnconfirmed
            return applied
        }
    }

    @Test
    fun aLightClientParkedOnAStaleAnchorAsksTheServers() = runTest {
        assertEquals(applied, FetchFreshCheckpoint(lightClient, checkpoints)(Network.GNOSIS))
        assertEquals(listOf(Network.GNOSIS to false), asked)
    }

    @Test
    fun theUsersUseItAnywayIsPassedOn() = runTest {
        FetchFreshCheckpoint(lightClient, checkpoints)(Network.ETHEREUM, useUnconfirmed = true)

        assertEquals(listOf(Network.ETHEREUM to true), asked)
    }

    @Test
    fun oneThatIsNotRefusingIsLeftAloneAndNoServerIsAsked() = runTest {
        status.value = LightClientStatus.CatchingUp(done = 2, total = 6)

        assertEquals(FreshCheckpoint.NotRefused, FetchFreshCheckpoint(lightClient, checkpoints)(Network.GNOSIS))
        assertEquals(emptyList(), asked)
    }
}
