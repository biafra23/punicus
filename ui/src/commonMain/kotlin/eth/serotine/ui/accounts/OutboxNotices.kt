package eth.serotine.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Test tag of the notice that a queued transaction was lost, for UI tests. */
const val ACCOUNTS_LOST_TAG = "accounts-lost"

/** Test tag of a line saying what a network has queued, for UI tests. */
const val ACCOUNTS_QUEUED_TAG = "accounts-queued"

/**
 * The notice, in either view, that queued signed bytes could not be opened since they were sealed
 * (docs/lenient-mode.md, *The signed bytes kept*): said until the user dismisses it ([onDismiss]).
 */
@Composable
internal fun LostQueuedNotice(text: String, onDismiss: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(ACCOUNTS_LOST_TAG),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(
            Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Dismiss") }
        }
    }
}
