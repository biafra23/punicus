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
}
