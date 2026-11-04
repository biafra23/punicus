package eth.serotine.ui.addnetwork

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.NetworkChecklist

/** Binds [AddOnNetworksDialog] to its ViewModel; an add, or a vanished account, ends it through [onClose]. */
@Composable
fun AddOnNetworksRoute(viewModel: AddOnNetworksViewModel, onClose: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    val finished = state is AddOnNetworksUiState.Done || state is AddOnNetworksUiState.Gone
    LaunchedEffect(finished) {
        if (finished) close()
    }
    AddOnNetworksDialog(state, viewModel::toggle, viewModel::turnOn, viewModel::add, onClose)
}

/**
 * Add on another network (docs/networks.md, N6) as a pure function of [state]: the networks the
 * account is not on yet, each ticked or unticked through [onToggle]; one that is off greyed out,
 * with Turn on ([onTurnOn]) as the way to choose it; and Add ([onAdd]). No password: nothing is
 * signed.
 */
@Composable
fun AddOnNetworksDialog(
    state: AddOnNetworksUiState,
    onToggle: (chainId: Long) -> Unit,
    onTurnOn: (chainId: Long) -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    val choose = state as? AddOnNetworksUiState.Choose
    AlertDialog(
        onDismissRequest = { if (choose?.adding != true) onCancel() },
        title = { Text(choose?.let { "Add ${it.label} on another network" } ?: "Add on another network") },
        text = {
            when (state) {
                is AddOnNetworksUiState.Unavailable -> Text(state.message, color = MaterialTheme.colorScheme.error)
                is AddOnNetworksUiState.Choose -> Choices(state, onToggle, onTurnOn)
                else -> Unit
            }
        },
        confirmButton = {
            if (choose != null && choose.choices.isNotEmpty()) {
                TextButton(onClick = onAdd, enabled = choose.canAdd) { Text("Add") }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = choose?.adding != true) {
                Text(if (choose != null && choose.choices.isNotEmpty()) "Cancel" else "Close")
            }
        },
    )
}

@Composable
private fun Choices(state: AddOnNetworksUiState.Choose, onToggle: (Long) -> Unit, onTurnOn: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.choices.isEmpty()) {
            Text("It is on every network this wallet offers already.")
            return@Column
        }
        Text(
            "Its address is the same on every network. Each network ticked adds an account of its own there, " +
                "under the same name. Nothing is signed.",
        )
        NetworkChecklist(state.choices, enabled = !state.adding, onToggle, onTurnOn)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
