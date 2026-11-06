package eth.serotine.ui.dapptransaction

import eth.serotine.domain.model.DappTransactionRequest
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.DappAsk
import eth.serotine.domain.repository.DappMetadata
import eth.serotine.domain.repository.DappRequest
import eth.serotine.domain.repository.DappRequestError
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.model.Network
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveDappMessages
import eth.serotine.domain.usecase.ProposeDappSafeTx
import eth.serotine.domain.usecase.QuoteDappTransaction
import eth.serotine.domain.usecase.RefuseDappRequest
import eth.serotine.domain.usecase.SendDappTransaction
import eth.serotine.domain.usecase.SignDappSafeTx
import eth.serotine.domain.usecase.SignSafeTx
import eth.serotine.ui.FakeDappRequests
import eth.serotine.ui.FakeKeys
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.MemoryTransactionHistory
import eth.serotine.ui.SafeWorld
import eth.serotine.ui.components.Eip55
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

/** Screen D6 for a Safe: a dApp's transaction proposed as a Safe transaction, signed by an owner held here, queued. */
@OptIn(ExperimentalCoroutinesApi::class)
class DappSafeTransactionViewModelTest {

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private val now = 1_793_700_000L
    private val world = SafeWorld().also {
        it.state = SafeState("1.4.1", l2 = false, listOf(it.alice.address, it.bob.address, it.carol), 2, 4, emptyList(), null)
        it.balances.of(it.safe).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
    }
    private val networks = MemoryNetworkSettings(setOf(Network.SEPOLIA))
    private val requests = FakeDappRequests()
    private val clock = TestTimeSource()
    private val payment =
        DappTransactionRequest(world.safe, world.recipient, UInt256.of("500000000000000000"), ByteArray(0), null)

    private fun request(id: Long, transaction: DappTransactionRequest = payment) = DappRequest(
        DappRequestId(DappSessionId("t"), id),
        DappMetadata("Example dApp", "https://app.example.org", ""),
        listOf(eth.serotine.domain.model.AccountId(eth.serotine.domain.model.Network.SEPOLIA, world.safe)),
        "eip155:11155111",
        DappAsk.SendTransaction(transaction),
        now + 300,
    )

    private fun TestScope.viewModel(id: Long = 1): DappTransactionViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val history = MemoryTransactionHistory()
        val vm = DappTransactionViewModel(
            "t/$id",
            ObserveDappMessages(requests, world.accounts, networks, world.catalog, clock = { now }),
            QuoteDappTransaction(
                requests, world.accounts, networks, world.catalog, world.balances, world.inputs, world.sent, history,
                outbox = Outbox.None,
                clock = { now },
            ),
            SendDappTransaction(
                requests, world.accounts, networks, world.signer, world.broadcaster, world.sent, history,
                world.receipts, clock = { now },
            ),
            ProposeDappSafeTx(
                requests, world.accounts, networks, world.catalog, world.observeSafe, world.balances, world.inputs,
                world.queue, clock = { now },
            ),
            SignDappSafeTx(requests) { SignSafeTx(world.signer, world.observeSafe, world.recovery, world.queue, it) },
            RefuseDappRequest(requests, world.accounts, networks, clock = { now }),
            DescribeKeyStore(FakeKeys()),
            clock = { now },
            timeSource = clock,
            ticks = kotlinx.coroutines.flow.flowOf(Unit),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun aGnosisChainSafesTransactionIsSignedThereAndOpensAsTheGnosisChainSafes() = runTest {
        val gnosis = SafeWorld(Network.GNOSIS).also {
            it.balances.of(it.safe).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        }
        val on = MemoryNetworkSettings(setOf(Network.GNOSIS))
        val asked = request(1).copy(
            sessionAccounts = listOf(eth.serotine.domain.model.AccountId(Network.GNOSIS, gnosis.safe)),
            sessionNetworks = setOf(Network.GNOSIS, Network.ETHEREUM),
            chain = "eip155:100",
            ask = DappAsk.SendTransaction(
                DappTransactionRequest(gnosis.safe, gnosis.recipient, UInt256.of("500000000000000000"), ByteArray(0), null),
            ),
        )
        requests.waiting.value = listOf(asked)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val history = MemoryTransactionHistory()
        val vm = DappTransactionViewModel(
            "t/1",
            ObserveDappMessages(requests, gnosis.accounts, on, gnosis.catalog, clock = { now }),
            QuoteDappTransaction(
                requests, gnosis.accounts, on, gnosis.catalog, gnosis.balances, gnosis.inputs, gnosis.sent, history,
                outbox = Outbox.None,
                clock = { now },
            ),
            SendDappTransaction(
                requests, gnosis.accounts, on, gnosis.signer, gnosis.broadcaster, gnosis.sent, history,
                gnosis.receipts, clock = { now },
            ),
            ProposeDappSafeTx(
                requests, gnosis.accounts, on, gnosis.catalog, gnosis.observeSafe, gnosis.balances, gnosis.inputs,
                gnosis.queue, clock = { now },
            ),
            SignDappSafeTx(requests) { SignSafeTx(gnosis.signer, gnosis.observeSafe, gnosis.recovery, gnosis.queue, it) },
            RefuseDappRequest(requests, gnosis.accounts, on, clock = { now }),
            DescribeKeyStore(FakeKeys()),
            clock = { now },
            timeSource = clock,
            ticks = kotlinx.coroutines.flow.flowOf(Unit),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        assertEquals("On Gnosis Chain, real funds", vm.uiState.value.network)
        assertEquals("0.5 XDAI", vm.uiState.value.quote[1].value)
        vm.onPasswordChange("made-up test password")
        vm.send()
        runCurrent()
        val queued = gnosis.queue.requests.value.single()
        assertEquals(Network.GNOSIS, queued.network)
        // It opens as the Gnosis Chain Safe's transaction, never the same address's on Sepolia.
        assertEquals(eth.serotine.domain.model.AccountId(Network.GNOSIS, gnosis.safe), vm.uiState.value.safeAccount)
        assertEquals("0x" + queued.hash.hex, vm.uiState.value.queued)
    }

    @Test
    fun aSafesTransactionIsProposedSignedByAnOwnerHeldHereAndQueued() = runTest {
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        val state = vm.uiState.value
        assertTrue(state.fromSafe)
        assertEquals("Sign and queue", state.signLabel)
        assertNull(state.refusal)
        assertEquals(
            listOf("Safe transaction", "Left in the Safe, at least", "Verified run", "Signs here as", "Fee"),
            state.quote.map { it.label },
        )
        assertEquals("Nonce 4; 2 of 3 owners sign it", state.quote[0].value)
        assertEquals("0.5 ETH (testnet)", state.quote[1].value)
        assertEquals(Eip55.format(world.alice.address), state.quote[3].value, "the first owner held here signs")
        assertEquals(emptyList(), world.queue.requests.value, "nothing is queued before it is signed")

        vm.onPasswordChange("made-up test password")
        vm.send()
        runCurrent()
        val queued = world.queue.requests.value.single()
        assertEquals(world.recipient, queued.tx.to)
        assertEquals(payment.value, queued.tx.value)
        assertEquals(4, queued.nonce)
        assertEquals(listOf(world.alice.address), world.signer.asked.map { it.first })
        assertEquals("0x" + queued.hash.hex, vm.uiState.value.queued)
        assertTrue(vm.uiState.value.openQueued)
        assertEquals(emptyList(), requests.refusals, "the dApp waits for the execution's hash")
        vm.onQueuedOpened()
        assertFalse(vm.uiState.value.openQueued)
    }

    @Test
    fun aRequestQueuedOnceIsThatSafeTransactionNeverAnother() = runTest {
        requests.waiting.value = listOf(request(1))
        val first = viewModel()
        first.onPasswordChange("made-up test password")
        first.send()
        runCurrent()
        val hash = assertNotNull(first.uiState.value.queued)

        val again = viewModel()
        assertEquals(hash, again.uiState.value.queued)
        assertFalse(again.uiState.value.openQueued, "opened from its button, not by itself")
        assertEquals(1, world.queue.requests.value.size)
    }

    @Test
    fun aSafeWithNoOwnerHeldHereGreysSigningOutAndSaysWhy() = runTest {
        world.state = SafeState("1.4.1", l2 = false, listOf(world.bob.address, world.carol), 1, 4, emptyList(), null)
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        assertEquals(DappTransactionViewModel.NO_OWNER_HERE, vm.uiState.value.notQuoted)
        assertTrue(vm.uiState.value.sendUnavailable)
        vm.send()
        assertEquals("Cannot be signed", vm.uiState.value.explaining?.title)
        assertEquals(emptyList(), world.signer.asked)
    }

    @Test
    fun aValueTheSafeCannotPayIsNotProposed() = runTest {
        requests.waiting.value = listOf(request(1, DappTransactionRequest(world.safe, world.recipient, UInt256.of("2000000000000000000"), ByteArray(0), null)))
        val vm = viewModel()
        assertEquals(DappTransactionViewModel.SAFE_SHORT, vm.uiState.value.notQuoted)
        assertTrue(vm.uiState.value.sendUnavailable)
    }

    @Test
    fun aCallThatRevertsFromTheSafeIsNotProposed() = runTest {
        world.inputs.simulation = Simulation.Reverted(100)
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        assertTrue(vm.uiState.value.notQuoted.orEmpty().startsWith("The call reverts"))
        assertTrue(vm.uiState.value.sendUnavailable)
    }

    @Test
    fun aSignatureNotMadeQueuesNothing() = runTest {
        world.signer.answer = Signing.WrongPassword
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        vm.onPasswordChange("made-up test password")
        vm.send()
        runCurrent()
        assertEquals(emptyList(), world.queue.requests.value)
        assertNull(vm.uiState.value.queued)
        assertNotNull(vm.uiState.value.unlock.error)
    }

    @Test
    fun aSafesTransactionRefusedIsAnsweredAsDeclined() = runTest {
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        vm.refuse()
        runCurrent()
        assertEquals(listOf(request(1).id to DappRequestError.USER_REJECTED), requests.refusals)
    }

    @Test
    fun aSafeTransactionNoLongerQueuedIsNeverReplacedAndCanOnlyBeRefused() = runTest {
        requests.waiting.value = listOf(request(1))
        val first = viewModel()
        first.onPasswordChange("made-up test password")
        first.send()
        runCurrent()
        assertNotNull(requests.waiting.value.single().safeTx, "tied to its Safe transaction before it was signed")
        // Rejected here, and another transaction holds its nonce now.
        world.queue.requests.value = listOf(world.request(world.ether(4), world.carol))
        val again = viewModel()
        assertEquals(DappTransactionViewModel.NO_LONGER_QUEUED, again.uiState.value.notQuoted)
        assertTrue(again.uiState.value.sendUnavailable)
        assertNull(again.uiState.value.queued)
    }

    @Test
    fun aSafesGasLimitIsRefusedNeverIgnored() = runTest {
        requests.waiting.value = listOf(
            request(1, DappTransactionRequest(world.safe, world.recipient, UInt256.ONE, ByteArray(0), 21_000)),
        )
        val vm = viewModel()
        assertTrue(vm.uiState.value.fromSafe)
        assertNotNull(vm.uiState.value.refusal)
        vm.refuse()
        runCurrent()
        assertEquals(listOf(request(1).id to DappRequestError.INVALID_PARAMS), requests.refusals)
    }
}
