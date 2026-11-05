package eth.serotine.ui.platform

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import eth.serotine.ui.components.QrScannerFrame
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The back camera through CameraX, in a full-screen dialog, each frame decoded on the device by
 * ZXing. The camera permission is asked for on the first scan; a refusal reports
 * [QrScan.CameraRefused] and asks again on the next tap, which the system answers itself once the
 * user has refused for good. The first code read ends the scan.
 */
@Composable
actual fun rememberQrScanner(onResult: (QrScan) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    var scanning by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else result(QrScan.CameraRefused)
    }
    if (scanning) {
        val cancel = {
            scanning = false
            result(QrScan.Cancelled)
        }
        Dialog(
            onDismissRequest = cancel,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            QrScannerFrame(hint = "Point the camera at the dApp's WalletConnect QR code.", onCancel = cancel) {
                CameraQrReader(
                    onRead = { text ->
                        scanning = false
                        result(QrScan.Read(text))
                    },
                    onUnavailable = {
                        scanning = false
                        result(QrScan.CameraUnavailable)
                    },
                )
            }
        }
    }
    return remember(context) {
        {
            if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                scanning = true
            } else {
                permission.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

/**
 * The camera's preview, its frames handed to [QrDecoder] off the main thread, the first text read to
 * [onRead] on it. [onUnavailable] when no back camera can be started.
 */
@Composable
private fun CameraQrReader(onRead: (String) -> Unit, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val read by rememberUpdatedState(onRead)
    val unavailable by rememberUpdatedState(onUnavailable)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycleOwner) {
        val analysisThread = Executors.newSingleThreadExecutor()
        val main = context.mainExecutor
        val done = AtomicBoolean(false)
        val disposed = AtomicBoolean(false)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed.get()) return@addListener
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val decoder = QrDecoder()
                analysis.setAnalyzer(analysisThread) { image ->
                    val text = try {
                        if (done.get()) null else decoder.decode(image)
                    } finally {
                        image.close()
                    }
                    if (text != null && done.compareAndSet(false, true)) {
                        // A scan cancelled meanwhile has reported already: it reports once.
                        main.execute { if (!disposed.get()) read(text) }
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                // No back camera, or one another app holds: said, never a frozen preview.
                if (done.compareAndSet(false, true)) unavailable()
            }
        }, main)
        onDispose {
            disposed.set(true)
            if (future.isDone) runCatching { future.get().unbindAll() }
            analysisThread.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** Reads a QR code from a camera frame's luminance (its first, Y plane), or null when it holds none. */
private class QrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val buffer = plane.buffer
        // The plane's rows may be padded to rowStride: copied row by row into a tight frame.
        val luminance = ByteArray(width * height)
        for (row in 0 until height) {
            buffer.position(row * plane.rowStride)
            buffer.get(luminance, row * width, width)
        }
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            null // no QR code in this frame: the next one is tried
        } catch (_: RuntimeException) {
            null // a frame ZXing cannot take: never a crash, the next one is tried
        } finally {
            reader.reset()
        }
    }
}
