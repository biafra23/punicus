package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Test tag of the reason an unavailable action gives on a tap, for UI tests. */
const val UNAVAILABLE_REASON_TAG = "unavailable-reason"

/**
 * An action that cannot be done because something it needs is missing is shown, greyed out, and
 * a tap on it says what is missing and how to get it; it is never hidden (owner ruling,
 * 2026-10-18, #55; CLAUDE.md, *Conventions*). What these helpers draw it with.
 */
internal object Unavailable {

    /** What accessibility services say of such an action, beside its label. */
    const val STATE = "Unavailable"

    /** The colour such an action's label and icon are drawn in: as a disabled control's. */
    @Composable
    fun contentColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    /** The colour such an action's container is drawn in: as a disabled control's. */
    @Composable
    fun containerColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    /** A filled or tonal button's colours, greyed out while it stays tappable. */
    @Composable
    fun buttonColors(): ButtonColors =
        ButtonDefaults.buttonColors(containerColor = containerColor(), contentColor = contentColor())
}

/** Marks a control that is shown greyed out, as [Unavailable] has it, for accessibility services; else as it is. */
internal fun Modifier.unavailable(unavailable: Boolean): Modifier =
    if (unavailable) semantics { stateDescription = Unavailable.STATE } else this

/** A way to get what an unavailable action needs, offered beside its [UnavailableDialog]'s reason. */
class UnavailableFix(val label: String, val onClick: () -> Unit)

/** What a tap on an unavailable action says: the action's [title], the [reason] and its [fixes]. */
class UnavailableNotice(val title: String, val reason: String, val fixes: List<UnavailableFix> = emptyList())

/** [notice] as its [UnavailableDialog]. */
@Composable
internal fun UnavailableDialog(notice: UnavailableNotice, onDismiss: () -> Unit) =
    UnavailableDialog(notice.title, notice.reason, onDismiss, notice.fixes)

/**
 * What a tap on an unavailable action says: [title] (the action), the [reason] (what is missing
 * and how to get it), and the [fixes] that lead there, each closing the dialog first.
 */
@Composable
internal fun UnavailableDialog(
    title: String,
    reason: String,
    onDismiss: () -> Unit,
    fixes: List<UnavailableFix> = emptyList(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(reason, Modifier.testTag(UNAVAILABLE_REASON_TAG)) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                fixes.forEach { fix ->
                    TextButton(
                        onClick = {
                            onDismiss()
                            fix.onClick()
                        },
                    ) { Text(fix.label) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * What an action on an account whose [network] is off in this wallet says (docs/networks.md, N19):
 * its [title] ("Send needs Sepolia"), the [reason], and Turn on as the way to get it ([onTurnOn]).
 */
internal fun networkOffNotice(title: String, reason: String, network: String, onTurnOn: () -> Unit) =
    UnavailableNotice(title, reason, listOf(UnavailableFix("Turn on $network", onTurnOn)))

/** Why [account] cannot be added on another network while its [network] is off in this wallet. */
internal fun addOnNetworkOffReason(network: String, account: String): String =
    "$network is off in this wallet. Turned on, $account can be added on another network from here."
