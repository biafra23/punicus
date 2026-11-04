package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.SafeListing
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** [ObserveNetworkOverview]: each network as Settings → Networks shows it. */
class ObserveNetworkOverviewTest {

    private val now = 1_800_000_000L
    private val main = Account(
        Network.SEPOLIA,
        Address.of("0x" + "11".repeat(20)),
        AccountKind.EOA,
        "Main",
        AccountCustody.InKeyStore,
    )
    private val accounts = ListAccountRepository(main)
    private val on = MutableStateFlow(setOf(Network.SEPOLIA))
    private val settings = object : NetworkSettings {
        override fun observe(): Flow<Set<Network>> = on

        override suspend fun setEnabled(network: Network, on: Boolean): Boolean = error("not used by this test")
    }
    private val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Synced(9, now - 8, finalized = null))
    private val lightClient = object : LightClient {
        override val datesFinalizedBlocks = true

        override fun observeStatus(network: Network): Flow<LightClientStatus> = status
    }
    private val history = MemoryTransactionHistory()

    private fun overview() = ObserveNetworkOverview(settings, accounts, lightClient, history, clock = { now })

    private fun sent(nonce: Long, sentAt: Long = now - 60, action: SentAction = transfer) = SentTransfer(
        network = Network.SEPOLIA,
        account = main.address,
        nonce = nonce,
        signed = SignedTransfer(byteArrayOf(2, nonce.toByte())),
        action = action,
        maxCost = UInt256.of(1000),
        sentAt = sentAt,
    )

    private val transfer = SentAction.Transfer(Address.of("0x" + "22".repeat(20)), TransferAsset.Native, UInt256.ONE)

    private fun deployment(listed: SafeListing?) =
        SentAction.SafeDeployment(Address.of("0x" + "33".repeat(20)), listOf(main.address), 1, "1.4.1", "", listed)

    @Test
    fun aNetworkIsShownWithItsSwitchAccountsAndLightClient() = runTest {
        val sepolia = overview()().first().single { it.network == Network.SEPOLIA }

        assertEquals(Network.SEPOLIA, sepolia.network)
        assertEquals(true, sepolia.on)
        assertEquals(listOf(main), sepolia.accounts)
        assertEquals(LightClientStatus.Synced(9, now - 8, finalized = null), sepolia.status)
        assertEquals(0, sepolia.unconfirmed)
        assertEquals(0, sepolia.unlistedSafes)

        on.value = emptySet()
        assertEquals(false, overview()().first().single { it.network == Network.SEPOLIA }.on)
    }

    @Test
    fun anUnreadableAccountListIsNoAccountCount() = runTest {
        accounts.unreadable = true
        assertNull(overview()().first().single { it.network == Network.SEPOLIA }.accounts)
    }

    @Test
    fun whatWaitsIsTheTransactionsStillFollowedAndTheSafesNotListedYet() = runTest {
        history.entries.value = listOf(
            HistoryEntry(sent(1)),
            HistoryEntry(sent(2, sentAt = now - FOLLOW_SECONDS - 1)),
            HistoryEntry(sent(3), SentOutcome.Included(120, succeeded = true)),
            HistoryEntry(sent(4, action = deployment(listed = null)), SentOutcome.Included(121, succeeded = true)),
            HistoryEntry(sent(5, action = deployment(SafeListing.LISTED)), SentOutcome.Included(122, succeeded = true)),
            // Past its lifetime FollowSafeDeployments no longer follows it: nothing waits for it.
            HistoryEntry(
                sent(6, sentAt = now - DEPLOYMENT_LIFETIME_SECONDS - 1, action = deployment(listed = null)),
                SentOutcome.Included(90, succeeded = true),
            ),
        )

        val sepolia = overview()().first().single { it.network == Network.SEPOLIA }

        assertEquals(1, sepolia.unconfirmed, "one no longer followed, one included")
        assertEquals(1, sepolia.unlistedSafes, "one listed, one no longer followed")
    }
}
