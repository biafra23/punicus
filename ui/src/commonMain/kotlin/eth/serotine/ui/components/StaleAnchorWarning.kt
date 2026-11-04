package eth.serotine.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientStatus

/** Test tag of a stale anchor's "Catch up anyway…" button, for UI tests; the network's chain id follows. */
const val STALE_ANCHOR_CATCH_UP_TAG = "stale-anchor-catch-up-"

/** Test tag of the stale-anchor dialog's confirmation, for UI tests. */
const val STALE_ANCHOR_CONFIRM_TAG = "stale-anchor-confirm"

/**
 * A light client that refuses to sync because its last trusted checkpoint is older than the
 * weak-subjectivity window, as the screens say it (owner's request, 2026-10-24; CLAUDE.md,
 * *Trust*): the [banner] of its network's section, with [action] to open the dialog that asks
 * ([title], [body], [confirm], [dismiss]) whether to catch up anyway. Display-ready; [chainId]
 * names the network to the screen's action.
 */
data class StaleAnchorWarning(
    val chainId: Long,
    val banner: String,
    val title: String,
    val body: String,
    val action: String = "Catch up anyway…",
    val confirm: String = "Catch up anyway",
    val dismiss: String = "Not now",
) {
    companion object {
        /**
         * The warning for [network]'s light client parked on [status], its ages in time: each
         * sync-committee period is [Network.syncCommitteePeriodSeconds]. A bound the engine does
         * not say (0) is told as "the safe limit".
         */
        fun of(network: Network, status: LightClientStatus.StaleAnchor): StaleAnchorWarning {
            val name = network.displayName
            val age = about(status.anchorAgePeriods * network.syncCommitteePeriodSeconds)
            val limit = if (status.boundPeriods > 0) {
                "the ${about(status.boundPeriods * network.syncCommitteePeriodSeconds).removePrefix("about ")} " +
                    "it can safely catch up from on its own"
            } else {
                "the safe limit for catching up on its own"
            }
            return StaleAnchorWarning(
                chainId = network.chainId,
                banner = "Serotine's last trusted checkpoint for $name is $age old, older than $limit. Nothing " +
                    "on $name is verified until you decide.",
                title = "Catch up with $name anyway?",
                body = "To catch up from a checkpoint this old, Serotine has to trust the validators who signed " +
                    "for $name $age ago. Many may have left since, and their old keys can no longer be " +
                    "punished, so whoever gathers enough of them could show Serotine a made-up chain. That " +
                    "cannot reveal your keys or move your funds by itself, but balances, incoming payments " +
                    "and confirmations could be false. If you go on, check large amounts elsewhere before you " +
                    "act on them. This holds while Serotine keeps running: when it starts again and the " +
                    "checkpoint is still too old, it asks again.",
            )
        }

        /** [seconds] as a rough age: "about 34 hours", "about 3 days". */
        private fun about(seconds: Long): String {
            val hours = (seconds + 1_800) / 3_600
            return when {
                hours < 48 -> "about $hours hours"
                else -> "about ${(hours + 12) / 24} days"
            }
        }
    }
}

/** [warning]'s banner in its network's section, on [color], with its button to the dialog ([onAction]). */
@Composable
internal fun StaleAnchorBanner(warning: StaleAnchorWarning, color: Color, onAction: () -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(warning.banner, style = MaterialTheme.typography.labelMedium, color = color)
        TextButton(onClick = onAction, modifier = Modifier.testTag(STALE_ANCHOR_CATCH_UP_TAG + warning.chainId)) {
            Text(warning.action)
        }
    }
}

/** The question [warning] asks: [onConfirm] catches up anyway, for this run; [onDismiss] leaves it refused. */
@Composable
internal fun StaleAnchorDialog(warning: StaleAnchorWarning, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(warning.title) },
        text = { Text(warning.body) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(STALE_ANCHOR_CONFIRM_TAG)) {
                Text(warning.confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(warning.dismiss) } },
    )
}
