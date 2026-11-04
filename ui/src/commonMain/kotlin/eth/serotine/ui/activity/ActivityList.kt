package eth.serotine.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.GHOST_ROWS_UNKNOWN
import eth.serotine.ui.components.GhostCircle
import eth.serotine.ui.components.GhostLine
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.components.GhostRows

/** Test tag of each transaction row, for UI tests. */
const val ACTIVITY_ROW_TAG = "activity-row"

/** Test tag of each card of a swap whose next step waits, for UI tests. */
const val WAITING_STEP_TAG = "waiting-step"

/**
 * An account's Activity (docs/wallet-ux.md, screen 25) as items of the list it is drawn in, its
 * detail's Activity tab: what the list covers, then each transaction this device sent for it and
 * each event on chain, newest first, with how it ended as this device saw it; a tap on a row opens
 * its detail ([onOpen], by its key). Above them, a card for each swap whose next step waits for the
 * user (docs/simple-ui.md, H5): Continue opens Swap with it ([onContinue], by the token it sells), greyed
 * out unless [continueAvailable] as Swap is (a tap still goes to [onContinue], which says why), and
 * Dismiss lets it go ([onDismiss]). One item per row, so that a long history is composed as it
 * scrolls into view. While it is read, ghost rows in the rows' shape hold their room.
 */
fun LazyListScope.activityItems(
    state: ActivityUiState,
    onOpen: (ActivityKey) -> Unit,
    onContinue: (sell: String) -> Unit = {},
    onDismiss: (sell: String) -> Unit = {},
    continueAvailable: Boolean = true,
) {
    when (state) {
        // What the list covers and a few rows, in their shapes, until it is read: no spinner the rows push down.
        ActivityUiState.Loading -> item {
            Column {
                GhostLine(240.dp, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall)
                GhostRows(GHOST_ROWS_UNKNOWN, "Reading the activity") { ActivityGhost() }
            }
        }
        is ActivityUiState.Unavailable -> item {
            Text(state.message, color = MaterialTheme.colorScheme.error)
        }
        is ActivityUiState.Shown -> {
            items(state.waiting, key = { "waiting-${it.sell}" }) { waiting ->
                WaitingStepCard(
                    waiting,
                    continueAvailable,
                    onContinue = { onContinue(waiting.sell) },
                    onDismiss = { onDismiss(waiting.sell) },
                )
            }
            item {
                Text(
                    state.coverage,
                    Modifier.padding(bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.rows.isEmpty()) {
                item { Text(state.empty, style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.rows, key = { it.key }) { row ->
                ActivityItem(row, onClick = { onOpen(row.opens) })
            }
        }
    }
}

/** A swap whose next step waits: what it swaps, where it stands, and Continue or Dismiss. */
@Composable
private fun WaitingStepCard(step: WaitingStep, available: Boolean, onContinue: () -> Unit, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag(WAITING_STEP_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(step.title, style = MaterialTheme.typography.titleMedium)
            Text(step.message, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                Button(
                    onClick = onContinue,
                    modifier = Modifier.unavailable(!available),
                    colors = if (available) ButtonDefaults.buttonColors() else Unavailable.buttonColors(),
                ) { Text("Continue") }
            }
        }
    }
}

/** An [ActivityItem] not read yet, of its height. */
@Composable
private fun ActivityGhost() {
    ListItem(
        leadingContent = { GhostCircle(40.dp) },
        overlineContent = { GhostLine(96.dp) },
        headlineContent = { GhostLine(140.dp) },
        supportingContent = {
            Column {
                GhostLine(180.dp)
                GhostLine(120.dp, style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}

/** One row; a tap opens its detail ([onClick]). */
@Composable
private fun ActivityItem(row: ActivityRow, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.testTag(ACTIVITY_ROW_TAG).clickable(onClick = onClick),
        leadingContent = { AccountAvatar(row.about) },
        // When, above what it did: beside it, the date would squeeze each row into narrow lines.
        overlineContent = row.time.takeIf { it.isNotEmpty() }?.let { time -> { Text(time) } },
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column {
                Text(row.detail)
                Text(row.status, style = MaterialTheme.typography.labelSmall, color = statusColor(row.state))
            }
        },
    )
}

/**
 * The colour a row's status is said in, here and on its detail: an error's for a revert or a
 * warning, muted for what is not known yet or calls for care.
 */
@Composable
internal fun statusColor(state: ActivityState): Color = when (state) {
    ActivityState.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
    ActivityState.FAILED, ActivityState.WARNING -> MaterialTheme.colorScheme.error
    ActivityState.WAITING, ActivityState.UNCONFIRMED, ActivityState.UNKNOWN, ActivityState.CAUTION ->
        MaterialTheme.colorScheme.tertiary
}
