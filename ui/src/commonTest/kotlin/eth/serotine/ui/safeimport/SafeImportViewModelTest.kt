package eth.serotine.ui.safeimport

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.ImportSigningRequest
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.ui.SafeWorld
import eth.serotine.ui.components.Eip55
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** [SafeImportViewModel] over fake ports, through the real use case. */
@OptIn(ExperimentalCoroutinesApi::class)
class SafeImportViewModelTest {

    private val world = SafeWorld()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): SafeImportViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = SafeImportViewModel(
            AccountId(Network.SEPOLIA, world.safe),
            ObserveAccount(world.accounts),
            ImportSigningRequest(
                world.codec,
                world.observeSafe,
                world.recovery,
                world.queue,
                UnconfinedTestDispatcher(testScheduler),
            ),
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private val SafeImportViewModel.entering get() = assertIs<SafeImportUiState.Entering>(uiState.value)

    private fun TestScope.imported(text: String): SafeImportViewModel {
        val viewModel = viewModel()
        viewModel.onTextChange(text)
        viewModel.import()
        runCurrent()
        return viewModel
    }

    @Test
    fun theScreenSaysWhichSafeTheRequestIsFor() = runTest {
        val state = viewModel().entering

        assertEquals("Treasury", state.label)
        assertEquals(Eip55.format(world.safe), state.address)
        assertEquals(false, state.canImport, "nothing pasted yet")
    }

    @Test
    fun aRequestCheckedAgainstTheChainIsQueuedAndOpened() = runTest {
        val request = world.request(world.usdcTransfer(5), world.carol)

        val viewModel = imported("  " + world.codec.encode(request) + "\n")

        assertEquals(SafeImportUiState.Imported("Sepolia", "0x" + request.hash.hex), viewModel.uiState.value)
        assertEquals(listOf(request), world.queue.requests.value)
    }

    @Test
    fun whileTheSafeCannotBeVerifiedItIsCheckedAgainAndThenImported() = runTest {
        val request = world.request(world.usdcTransfer(5), world.carol)
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)

        val viewModel = imported(world.codec.encode(request))
        advanceTimeBy(10.seconds)
        assertEquals(true, viewModel.entering.importing, "still checking the Safe on chain")
        assertEquals(null, viewModel.entering.error)

        // The next reading proves it.
        world.state = world.state
        runCurrent()
        assertEquals(SafeImportUiState.Imported("Sepolia", "0x" + request.hash.hex), viewModel.uiState.value)
    }

    @Test
    fun aSafeStillUnverifiedOnceTheCheckGivesUpSaysSo() = runTest {
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)

        val viewModel = imported(world.codec.encode(world.request(world.ether(4))))
        advanceTimeBy(46.seconds)

        assertEquals(false, viewModel.entering.importing)
        assertEquals(
            "The Safe's owners and nonce could not be verified this time: try again.",
            viewModel.entering.error,
        )
        assertEquals(emptyList(), world.queue.requests.value)
    }

    @Test
    fun aRequestForAnotherSafeIsRefusedAndNothingIsKept() = runTest {
        val other = Address.of("0x" + "6b".repeat(20))
        val request = SigningRequest.of(Network.SEPOLIA, other, world.ether(4))

        val viewModel = imported(world.codec.encode(request))

        assertEquals(
            "This request is for another Safe, ${Eip55.format(other)}: import it from that Safe's own screen.",
            viewModel.entering.error,
        )
        assertEquals(emptyList(), world.queue.requests.value)
    }

    @Test
    fun aTextThatIsNoRequestIsRefused() = runTest {
        val viewModel = imported("hello")

        assertEquals("This text is not a Safe signing request.", viewModel.entering.error)
    }

    @Test
    fun aSignatureThatIsNotItsOwnersIsRefused() = runTest {
        val unsigned = SigningRequest.of(Network.SEPOLIA, world.safe, world.ether(4))
        // Carol's made-up signature of another transaction, said to be of this one.
        val elsewhere = SigningRequest.of(Network.SEPOLIA, world.safe, world.ether(5)).hash
        val signature = SafeSignature.Ecdsa(world.carol, world.recovery.sign(world.carol, elsewhere))
        val forged = unsigned.withSignatures(listOf(signature))

        val viewModel = imported(world.codec.encode(forged))

        assertTrue(viewModel.entering.error!!.startsWith("The signature said to be"), viewModel.entering.error)
        assertEquals(emptyList(), world.queue.requests.value)
    }

    @Test
    fun aRequestTheQueueCannotSaveIsSaidAndNotOpened() = runTest {
        world.queue.failSaves = true

        val viewModel = imported(world.codec.encode(world.request(world.ether(4))))

        assertTrue(viewModel.entering.error!!.startsWith("The request passed every check"), viewModel.entering.error)
    }

    @Test
    fun editingTheTextClearsTheLastRefusal() = runTest {
        val viewModel = imported("hello")

        viewModel.onTextChange("hello again")

        assertEquals(null, viewModel.entering.error)
        assertEquals("hello again", viewModel.entering.text)
    }
}
