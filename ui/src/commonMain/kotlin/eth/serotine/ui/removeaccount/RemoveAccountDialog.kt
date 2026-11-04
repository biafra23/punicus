package eth.serotine.ui.removeaccount

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.Blockie

/** Binds [RemoveAccountDialog] to its ViewModel; a removal, or a vanished account, ends it through [onClose]. */
@Composable
fun RemoveAccountRoute(viewModel: RemoveAccountViewModel, onClose: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    val finished = state is RemoveAccountUiState.Done || state is RemoveAccountUiState.Gone
    LaunchedEffect(finished) {
        if (finished) close()
    }
    RemoveAccountDialog(state, viewModel::remove, onClose)
}

/**
 * The remove dialog as a pure function of [state]. For an account whose key is on this
 * device it says, before anything happens, that the key goes too and only the recovery
 * phrase brings it back (docs/wallet-ux.md, screen 3), or that it stays for the same address
 * held here on other networks (docs/networks.md, N7).
 */
@Composable
fun RemoveAccountDialog(state: RemoveAccountUiState, onRemove: () -> Unit, onCancel: () -> Unit) {
    when (state) {
        is RemoveAccountUiState.Notice -> AlertDialog(
            onDismissRequest = onCancel,
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onCancel) { Text("OK") } },
        )
        else -> {
            val confirm = state as? RemoveAccountUiState.Confirm
            // While it runs the removal finishes regardless; leaving now would only hide how it went.
            val removing = confirm?.removing == true
            AlertDialog(
                onDismissRequest = { if (!removing) onCancel() },
                title = { Text(confirm?.let { "Remove ${it.label} on ${it.network}?" } ?: "Remove account?") },
                text = {
                    confirm?.let {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Blockie(it.address, size = 24.dp)
                                Text(
                                    it.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Text(
                                if (it.keyed && it.keyStaysFor != null) {
                                    "Its key stays on this device for the same address on ${it.keyStaysFor}. " +
                                        "You can add it on ${it.network} again from there."
                                } else if (it.keyed) {
                                    "Its key is deleted from this device. Only its recovery phrase brings this " +
                                        "account back: make sure you have it."
                                } else {
                                    "Serotine stops watching this address. You can watch it again at any time."
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = onRemove,
                        enabled = confirm != null && !confirm.removing,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Remove")
                    }
                },
                dismissButton = { TextButton(onClick = onCancel, enabled = !removing) { Text("Cancel") } },
            )
        }
    }
}
