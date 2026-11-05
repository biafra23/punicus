package eth.serotine.ui.pairdapp

import eth.serotine.domain.usecase.PairDapp
import eth.serotine.ui.FakeDappSessions
import eth.serotine.ui.platform.QrScan
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

/** Connect a dApp's QR code scan: what the scanner reports, as the ViewModel takes it. */
@OptIn(ExperimentalCoroutinesApi::class)
class PairDappScanTest {

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private val sessions = FakeDappSessions()

    private fun kotlinx.coroutines.test.TestScope.viewModel(): PairDappViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        return PairDappViewModel(PairDapp(sessions))
    }

    @Test
    fun aScannedLinkFillsTheFieldAndPairsOnlyOnConnect() = runTest {
        val vm = viewModel()
        vm.scanned(QrScan.Read("  wc:abc@2?relay-protocol=irn&symKey=00 \n"))
        assertEquals("wc:abc@2?relay-protocol=irn&symKey=00", vm.uiState.value.text)
        assertNull(vm.uiState.value.message)
        assertTrue(sessions.paired.isEmpty(), "a scan connects nothing by itself")
        vm.connect()
        runCurrent()
        assertEquals(listOf("wc:abc@2?relay-protocol=irn&symKey=00"), sessions.paired)
    }

    @Test
    fun aCodeThatIsNoWalletConnectLinkIsRefusedAndKeepsWhatWasTyped() = runTest {
        val vm = viewModel()
        vm.edit("wc:typed@2")
        vm.scanned(QrScan.Read("https://example.org"))
        assertEquals("wc:typed@2", vm.uiState.value.text)
        assertEquals(PairDappViewModel.NOT_A_LINK, vm.uiState.value.message)
    }

    @Test
    fun anUppercaseSchemeIsRefusedAtTheScanAsTheParserWouldOnConnect() = runTest {
        val vm = viewModel()
        vm.scanned(QrScan.Read("WC:ABC@2?RELAY-PROTOCOL=IRN&SYMKEY=00"))
        assertEquals("", vm.uiState.value.text)
        assertEquals(PairDappViewModel.NOT_A_LINK, vm.uiState.value.message)
    }

    @Test
    fun aRefusedOrMissingCameraGreysScanOutWithItsReasonUntilAScanWorks() = runTest {
        val vm = viewModel()
        vm.scanned(QrScan.CameraRefused)
        assertTrue(vm.uiState.value.cameraUnavailable)
        assertEquals(PairDappViewModel.CAMERA_REFUSED, vm.uiState.value.message)
        vm.scanned(QrScan.CameraUnavailable)
        assertEquals(PairDappViewModel.NO_CAMERA, vm.uiState.value.message)
        vm.scanned(QrScan.Read("wc:abc@2"))
        assertFalse(vm.uiState.value.cameraUnavailable)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun aCancelledScanChangesNothing() = runTest {
        val vm = viewModel()
        vm.edit("wc:typed@2")
        vm.scanned(QrScan.Cancelled)
        assertEquals("wc:typed@2", vm.uiState.value.text)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun aScannerThatRanAndWasCancelledClearsAGreyedOutScan() = runTest {
        val vm = viewModel()
        vm.scanned(QrScan.CameraRefused)
        vm.edit("wc:typed@2")
        vm.scanned(QrScan.CameraRefused)
        vm.scanned(QrScan.Cancelled)
        assertFalse(vm.uiState.value.cameraUnavailable)
        assertNull(vm.uiState.value.message)
        assertEquals("wc:typed@2", vm.uiState.value.text)
    }
}
