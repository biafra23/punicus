package eth.serotine.ui.pairdapp

import eth.serotine.domain.usecase.PairDapp
import eth.serotine.ui.FakeDappSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingDappLinkTest {

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun onlyAWcLinkOfAPairingUrisLengthIsOffered() {
        val inbox = IncomingDappLink()
        assertFalse(inbox.offer("https://example.org"))
        assertFalse(inbox.offer("wc:" + "x".repeat(3000)))
        assertNull(inbox.pending.value)
        assertTrue(inbox.offer("wc:abc@2"))
        assertEquals("wc:abc@2", inbox.take())
        assertNull(inbox.take())
    }

    @Test
    fun serotinesDeepLinkHandsOverTheWcLinkItCarries() {
        val inbox = IncomingDappLink()
        val wc = "wc:7f6e@2?relay-protocol=irn&symKey=587d&expiryTimestamp=1700000000"
        // As WalletConnect's modals build it: encodeURIComponent of the wc: link.
        val encoded = "wc%3A7f6e%402%3Frelay-protocol%3Dirn%26symKey%3D587d%26expiryTimestamp%3D1700000000"
        assertTrue(inbox.offer("serotine://wc?uri=$encoded"))
        assertEquals(wc, inbox.take())
        // Another parameter beside it, in either order, changes nothing.
        assertTrue(inbox.offer("serotine://wc?requestId=1&uri=$encoded"))
        assertEquals(wc, inbox.take())
    }

    @Test
    fun aDeepLinkWithoutOneWellEncodedWcLinkOffersNothing() {
        val inbox = IncomingDappLink()
        // A dApp bringing the wallet to the front for a request: no link.
        assertFalse(inbox.offer("serotine://wc?requestId=1&sessionTopic=ab"))
        assertFalse(inbox.offer("serotine://wc?uri=wc:abc@2?relay-protocol=irn&symKey=00"), "not encoded")
        assertFalse(inbox.offer("serotine://wc?uri=wc%3Aa%402&uri=wc%3Ab%402"), "two links")
        assertFalse(inbox.offer("serotine://wc?uri=wc%3Aabc%4"), "a broken escape")
        assertFalse(inbox.offer("serotine://wc?uri=wc%3A%FF"), "not UTF-8")
        assertFalse(inbox.offer("serotine://wc?uri=https%3A%2F%2Fexample.org"), "not a wc: link")
        assertFalse(inbox.offer("serotine://other?uri=wc%3Aabc%402"), "another path")
        assertFalse(inbox.offer("serotine://wc?uri=wc%3A" + "x".repeat(3000)), "too long")
        assertNull(inbox.pending.value)
    }

    @Test
    fun connectADappFillsInTheLinkAndPairsOnlyOnConnect() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val sessions = FakeDappSessions()
        val inbox = IncomingDappLink()
        inbox.offer("wc:first@2")
        val vm = PairDappViewModel(PairDapp(sessions), inbox)
        runCurrent()
        assertEquals("wc:first@2", vm.uiState.value.text)
        assertTrue(sessions.paired.isEmpty())
        // One more arriving while the screen is open replaces it.
        inbox.offer("wc:second@2")
        runCurrent()
        assertEquals("wc:second@2", vm.uiState.value.text)
        vm.connect()
        runCurrent()
        assertEquals(listOf("wc:second@2"), sessions.paired)
    }

    @Test
    fun aLinkArrivingWhileConnectIsUnderWayWaitsForItsAnswer() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val answer = kotlinx.coroutines.CompletableDeferred<eth.serotine.domain.repository.DappPairing>()
        val sessions = object : eth.serotine.domain.repository.DappSessions by FakeDappSessions() {
            override suspend fun pair(uri: String) = answer.await()
        }
        val inbox = IncomingDappLink()
        val vm = PairDappViewModel(PairDapp(sessions), inbox)
        vm.edit("wc:typed@2")
        vm.connect()
        inbox.offer("wc:arrived@2")
        runCurrent()
        // The Connect under way keeps its link, and the new one waits.
        assertEquals("wc:typed@2", vm.uiState.value.text)
        assertEquals("wc:arrived@2", inbox.pending.value)
        answer.complete(eth.serotine.domain.repository.DappPairing.Expired)
        runCurrent()
        assertEquals("wc:arrived@2", vm.uiState.value.text)
        assertNull(inbox.pending.value)
    }

    @Test
    fun aLinkWaitingWhenTheConnectUnderWayPairsLeavesTheExitAloneAndWaitsOn() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val answer = kotlinx.coroutines.CompletableDeferred<eth.serotine.domain.repository.DappPairing>()
        val sessions = object : eth.serotine.domain.repository.DappSessions by FakeDappSessions() {
            override suspend fun pair(uri: String) = answer.await()
        }
        val inbox = IncomingDappLink()
        val vm = PairDappViewModel(PairDapp(sessions), inbox)
        vm.edit("wc:typed@2")
        vm.connect()
        inbox.offer("wc:arrived@2")
        answer.complete(eth.serotine.domain.repository.DappPairing.Paired(waitingForRelay = false))
        runCurrent()
        // The pairing's exit stands, and the link waits for the app to open Connect a dApp again.
        assertTrue(vm.uiState.value.paired)
        assertEquals("wc:typed@2", vm.uiState.value.text)
        assertEquals("wc:arrived@2", inbox.pending.value)
    }
}

