package eth.serotine.ui.platform

import androidx.compose.runtime.Composable

/** No scanner on this platform yet: Connect a dApp takes a pasted link. */
@Composable
actual fun rememberQrScanner(onResult: (QrScan) -> Unit): (() -> Unit)? = null
