package eth.serotine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Test tag of the scanner's Cancel button, for UI tests. */
const val QR_SCANNER_CANCEL_TAG = "qr-scanner-cancel"

/**
 * What a QR code scan shows over the camera: the [camera] filling the screen, a square to aim the
 * code into, what to scan, and Cancel. Stateless: the platform's scanner supplies the camera and
 * reads the code (platform/QrScanning.kt). [hint] names what the code is for.
 */
@Composable
fun QrScannerFrame(
    hint: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    camera: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        camera()
        Box(
            Modifier
                .align(Alignment.Center)
                .size(260.dp)
                .border(3.dp, Color.White, RoundedCornerShape(16.dp)),
        )
        Text(
            hint,
            Modifier
                .align(Alignment.TopCenter)
                .systemBarsPadding()
                .padding(24.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(12.dp),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .padding(24.dp)
                .fillMaxWidth()
                .testTag(QR_SCANNER_CANCEL_TAG),
        ) { Text("Cancel") }
    }
}
