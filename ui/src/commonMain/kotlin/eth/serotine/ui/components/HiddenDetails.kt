package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Test tag of the button that opens a simple screen's *Details*, for UI tests. */
const val DETAILS_TAG = "details"

/**
 * What simple mode keeps one tap away (docs/simple-ui.md): the blocks, the fee's make-up, the
 * verified runs, behind a *Details* button that opens and closes them. [tag] names the button for
 * UI tests.
 */
@Composable
fun HiddenDetails(tag: String = DETAILS_TAG, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, Modifier.testTag(tag)) {
        Text(if (open) "Hide details" else "Details")
    }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
