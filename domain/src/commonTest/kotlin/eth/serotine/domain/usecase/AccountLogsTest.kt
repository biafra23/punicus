package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.LogWatch
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [HistoryNotingAccounts] and [FollowAccountLogs] over fake ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountLogsTest {

    private val alice = Account(
        Network.SEPOLIA,
        Address.of("0x" + "11".repeat(20)),
        AccountKind.EOA,
        "Alice",
        AccountCustody.InKeyStore,
    )
    private val vault = Account(
        Network.SEPOLIA,
        Address.of("0x" + "5a".repeat(20)),
        AccountKind.SAFE,
        "Vault",
        AccountCustody.Watched,
    )
    private val accounts = ListAccountRepository()
    private val starts = MemoryHistoryStarts()
    private val history = MemoryTransactionHistory()
    private val lightClient = FakeLightClient()
    private val index = RecordingLogIndex()

    /** The verified head the tests sync to, and its time. */
    private val head = LightClientStatus.Synced(headBlock = 10_000, headTimestamp = HEAD_TIME, finalized = null)

    private fun TestScope.follow() {
        val follower = FollowAccountLogs(accounts, starts, history, lightClient, index, Network.SEPOLIA)
        backgroundScope.launch { follower() }
        runCurrent()
    }

    private fun sync(status: LightClientStatus = head) {
        lightClient.status.value = status
    }

    @Test
    fun anAccountAddedIsNotedWithTheTimeInPlaceOfAStartLeftFromBefore() = runTest {
        starts.starts.value = mapOf(alice.address to HistoryStart.Fixed(1_000))
        val noting = HistoryNotingAccounts(accounts, starts, clock = { HEAD_TIME - 60 })

        assertEquals(AccountAdd.Added, noting.add(alice))
        assertEquals(mapOf(alice.address to HistoryStart.Noted(HEAD_TIME - 60)), starts.starts.value)

        val later = HistoryNotingAccounts(accounts, starts, clock = { HEAD_TIME })
        assertEquals(AccountAdd.AlreadyPresent, later.add(alice))
        assertEquals(HistoryStart.Noted(HEAD_TIME - 60), starts.starts.value[alice.address], "not added again")

        accounts.failSaves = true
        assertEquals(AccountAdd.NotSaved, later.add(vault))
        assertEquals(null, starts.starts.value[vault.address], "not added at all")
    }

    @Test
    fun nothingIsFixedOrWatchedUntilTheLightClientIsSynced() = runTest {
        accounts.accounts.value = listOf(alice)
        starts.starts.value = mapOf(alice.address to HistoryStart.Noted(HEAD_TIME - 1200))
        follow()
        sync(LightClientStatus.WaitingForProofPeers(10_000, HEAD_TIME))
        runCurrent()

        assertEquals(HistoryStart.Noted(HEAD_TIME - 1200), starts.starts.value[alice.address])
        assertEquals(emptyList(), index.asked)

        sync(head)
        runCurrent()

        // 1,200 s and the hour's margin, in 12-second slots: 400 blocks back.
        assertEquals(HistoryStart.Fixed(9_600), starts.starts.value[alice.address])
        assertEquals(mapOf<LogWatchKey, Long>(LogWatchKey.Mention(alice.address) to 9_600), index.watched)
    }

    @Test
    fun aNetworkStoppedAndStartedAgainIsAskedForEverythingAgain() = runTest {
        accounts.accounts.value = listOf(alice)
        starts.starts.value = mapOf(alice.address to HistoryStart.Fixed(9_000))
        follow()
        sync()
        runCurrent()
        assertEquals(1, index.asked.size)

        // Idle, then running again: its chain is created anew, and may hold nothing of before.
        sync(LightClientStatus.Idle)
        runCurrent()
        sync(head.copy(headBlock = 10_001))
        runCurrent()

        assertEquals(2, index.asked.size)
        assertEquals(listOf(LogWatch(LogWatchKey.Mention(alice.address), 9_000)), index.asked.last().first)
    }

    @Test
    fun aSafeIsFollowedAsItsOwnEventsTooAndAnAccountWithoutANoteFromTheHead() = runTest {
        accounts.accounts.value = listOf(alice, vault)
        starts.starts.value = mapOf(vault.address to HistoryStart.Noted(HEAD_TIME))
        follow()
        sync()
        runCurrent()

        assertEquals(HistoryStart.Fixed(10_000), starts.starts.value[alice.address], "known of nothing earlier")
        assertEquals(HistoryStart.Fixed(9_700), starts.starts.value[vault.address], "the hour's margin")
        assertEquals(
            mapOf(
                LogWatchKey.Mention(alice.address) to 10_000L,
                LogWatchKey.Mention(vault.address) to 9_700L,
                LogWatchKey.Emitter(vault.address) to 9_700L,
            ),
            index.watched,
        )
    }

    @Test
    fun aTransactionSentEarlierOrSeenInAnEarlierBlockMovesTheStartBack() = runTest {
        accounts.accounts.value = listOf(alice, vault)
        starts.starts.value = mapOf(
            alice.address to HistoryStart.Noted(HEAD_TIME),
            vault.address to HistoryStart.Noted(HEAD_TIME),
        )
        // Alice sent a transfer a day before; the vault's deployment was seen in block 1,000, while
        // the device's clock was far behind the chain's.
        val deployment = SentAction.SafeDeployment(vault.address, listOf(alice.address), 1, "1.4.1", "Vault")
        history.entries.value = listOf(
            HistoryEntry(sent(alice.address, nativeTo(vault.address), DAY)),
            HistoryEntry(sent(alice.address, deployment, 0, 2), SentOutcome.Finalized(1_000, succeeded = true)),
        )
        follow()
        sync()
        runCurrent()

        assertEquals(HistoryStart.Fixed(1_000), starts.starts.value[alice.address], "it sent the deployment")
        assertEquals(HistoryStart.Fixed(1_000), starts.starts.value[vault.address])

        val bob = Account(
            Network.SEPOLIA,
            Address.of("0x" + "22".repeat(20)),
            AccountKind.EOA,
            "Bob",
            AccountCustody.InKeyStore,
        )
        history.entries.value = listOf(HistoryEntry(sent(bob.address, nativeTo(alice.address), DAY)))
        accounts.accounts.value += bob
        runCurrent()

        // A day and the hour's margin: 7,500 blocks back.
        assertEquals(HistoryStart.Fixed(2_500), starts.starts.value[bob.address])
    }

    @Test
    fun aHistoryThatCannotBeReadLeavesTheStartAtTheNote() = runTest {
        accounts.accounts.value = listOf(alice)
        starts.starts.value = mapOf(alice.address to HistoryStart.Noted(HEAD_TIME))
        history.entries.value = listOf(HistoryEntry(sent(alice.address, nativeTo(vault.address), DAY)))
        history.readable.value = false
        follow()
        sync()
        runCurrent()

        assertEquals(HistoryStart.Fixed(9_700), starts.starts.value[alice.address])
    }

    @Test
    fun anAccountThatLeftIsUnwatchedAndItsStartForgottenOnceTheEngineTookIt() = runTest {
        accounts.accounts.value = listOf(alice, vault)
        starts.starts.value = mapOf(
            alice.address to HistoryStart.Fixed(9_000),
            vault.address to HistoryStart.Fixed(9_500),
        )
        follow()
        sync()
        runCurrent()
        index.takes = false
        accounts.accounts.value = listOf(alice)
        runCurrent()

        assertEquals(HistoryStart.Fixed(9_500), starts.starts.value[vault.address], "not forgotten before it is taken")

        index.takes = true
        sync(head.copy(headBlock = 10_001))
        runCurrent()

        val unwatched = index.asked.last().second
        assertEquals(listOf(LogWatchKey.Mention(vault.address), LogWatchKey.Emitter(vault.address)), unwatched)
        assertEquals(mapOf<LogWatchKey, Long>(LogWatchKey.Mention(alice.address) to 9_000), index.watched)
        assertEquals(mapOf(alice.address to HistoryStart.Fixed(9_000)), starts.starts.value)
    }

    @Test
    fun anAccountThatLeftWithItsStartOnlyNotedIsUnwatchedTooBeforeItIsForgotten() = runTest {
        // Fixed at the head and watched before its note landed, the note then replacing the fixed
        // start, and removed before a round fixed it again.
        accounts.accounts.value = listOf(alice)
        follow()
        sync()
        runCurrent()
        assertEquals(mapOf<LogWatchKey, Long>(LogWatchKey.Mention(alice.address) to 10_000), index.watched)
        starts.starts.value = mapOf(alice.address to HistoryStart.Noted(HEAD_TIME))
        index.takes = false
        accounts.accounts.value = emptyList()
        runCurrent()

        assertEquals(mapOf(alice.address to HistoryStart.Noted(HEAD_TIME)), starts.starts.value, "kept until taken")

        index.takes = true
        sync(head.copy(headBlock = 10_001))
        runCurrent()

        assertEquals(emptyMap(), index.watched)
        assertEquals(emptyMap(), starts.starts.value)
    }

    @Test
    fun whatTheEngineDidNotTakeIsAskedAgainAtTheNextHeadAndWhatItTookIsNot() = runTest {
        accounts.accounts.value = listOf(alice)
        starts.starts.value = mapOf(alice.address to HistoryStart.Fixed(9_000))
        index.takes = false
        follow()
        sync()
        runCurrent()
        index.takes = true
        sync(head.copy(headBlock = 10_001))
        runCurrent()
        sync(head.copy(headBlock = 10_002))
        runCurrent()

        val alices = listOf(LogWatch(LogWatchKey.Mention(alice.address), 9_000))
        assertEquals(listOf(alices to emptyList<LogWatchKey>(), alices to emptyList()), index.asked)
        assertTrue(index.watched.isNotEmpty())
    }

    private fun nativeTo(to: Address) = SentAction.Transfer(to, TransferAsset.Native, UInt256.ONE)

    private fun sent(from: Address, action: SentAction, ageSeconds: Long, raw: Byte = 1) = SentTransfer(
        network = Network.SEPOLIA,
        account = from,
        nonce = 0,
        signed = SignedTransfer(byteArrayOf(2, raw)),
        action = action,
        maxCost = UInt256.ONE,
        sentAt = HEAD_TIME - ageSeconds,
    )

    private companion object {
        const val HEAD_TIME = 1_800_000_000L
    }
}
