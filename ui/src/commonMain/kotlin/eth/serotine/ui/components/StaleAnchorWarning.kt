package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientStatus

/** Test tag of a stale anchor's "Catch up…" button, for UI tests; the network's chain id follows. */
const val STALE_ANCHOR_CATCH_UP_TAG = "stale-anchor-catch-up-"

/** Test tag of the stale-anchor dialog's confirmation, for UI tests. */
const val STALE_ANCHOR_CONFIRM_TAG = "stale-anchor-confirm"

/** Test tag of the stale-anchor dialog's "Fetch a fresh checkpoint", for UI tests. */
const val STALE_ANCHOR_FETCH_TAG = "stale-anchor-fetch"

/** Test tag of the stale-anchor dialog's "Use it anyway", for a checkpoint no long-known peer confirmed. */
const val STALE_ANCHOR_UNCONFIRMED_TAG = "stale-anchor-unconfirmed"

/**
 * A light client that refuses to sync because its last trusted checkpoint is older than the
 * weak-subjectivity window, as the screens say it (owner's request, 2026-10-24; CLAUDE.md,
 * *Trust*): the [banner] of its network's section, with [action] to open the dialog ([title],
 * [body]) that offers the two ways on. [fetch] starts over from a fresh checkpoint the network's
 * checkpoint-sync servers and a long-known peer agree on (owner's request, 2026-11-14); [confirm]
 * catches up from the old one anyway; [dismiss] leaves it refused. Display-ready; [chainId] names
 * the network to the screen's actions.
 *
 * While the servers are asked, [fetching], said by [fetchingText]. A fetch refused says why in [fetchProblem]; when the
 * servers agreed but no long-known peer confirmed it, [unconfirmedWarning] says what that risks and
 * [useUnconfirmed] offers to take it anyway, which is asked again each time.
 */
data class StaleAnchorWarning(
    val chainId: Long,
    val banner: String,
    val title: String,
    val body: String,
    val action: String = "Catch up…",
    val fetch: String = "Fetch a fresh checkpoint",
    val confirm: String = "Catch up anyway",
    val dismiss: String = "Not now",
    val fetching: Boolean = false,
    val fetchProblem: String? = null,
    val unconfirmedWarning: String? = null,
    val useUnconfirmed: String = "Use it anyway",
    val fetchingText: String = "Asking the checkpoint servers and the peers Punicus has long known…",
) {
    companion object {
        /**
         * The warning for [network]'s light client parked on [status], its ages in time: each
         * sync-committee period is [Network.syncCommitteePeriodSeconds]. A bound the engine does
         * not say (0) is told as "the safe limit". [fetch] is where a fetch of a fresh checkpoint
         * stands, null when none was asked for.
         */
        fun of(
            network: Network,
            status: LightClientStatus.StaleAnchor,
            fetch: CheckpointFetchState? = null,
        ): StaleAnchorWarning {
            val name = network.displayName
            val age = about(status.anchorAgePeriods * network.syncCommitteePeriodSeconds)
            val limit = if (status.boundPeriods > 0) {
                "the ${about(status.boundPeriods * network.syncCommitteePeriodSeconds).removePrefix("about ")} " +
                    "it can safely catch up from on its own"
            } else {
                "the safe limit for catching up on its own"
            }
            val refused = fetch as? CheckpointFetchState.Refused
            return StaleAnchorWarning(
                chainId = network.chainId,
                banner = "Punicus's last trusted checkpoint for $name is $age old, older than $limit. Nothing " +
                    "on $name is verified until you decide.",
                title = "Catch up with $name?",
                body = "Punicus's last trusted checkpoint for $name is $age old. There are two ways on.\n\n" +
                    "Fetch a fresh checkpoint (recommended): Punicus asks $name's public checkpoint servers " +
                    "which block the chain finalized last, and starts over from it only if every server " +
                    "that answers names the same one, run by at least two operators, and a peer Punicus " +
                    "has known for a week or more names it too. If one differs, nothing changes, and the " +
                    "one that differs is named. That checkpoint is the servers' word; everything after " +
                    "it is verified as usual. The servers see your IP address.\n\n" +
                    "Catch up anyway: Punicus trusts the validators who signed for $name $age ago. Many " +
                    "may have left since, and their old keys can no longer be punished, so whoever " +
                    "gathers enough of them could show Punicus a made-up chain. That cannot reveal your " +
                    "keys or move your funds by itself, but balances, incoming payments and confirmations " +
                    "could be false. If you go on, check large amounts elsewhere before you act on them. " +
                    "This holds while Punicus keeps running: when it starts again and the checkpoint is " +
                    "still too old, it asks again.",
                fetching = fetch == CheckpointFetchState.Fetching || fetch == CheckpointFetchState.Applied,
                fetchProblem = refused?.let { "No fresh checkpoint: ${it.reason.removeSuffix(".")}." },
                unconfirmedWarning = UNCONFIRMED.takeIf { refused?.unconfirmed == true },
            )
        }

        private const val UNCONFIRMED = "The servers agree, but no peer Punicus has known for a week or more " +
            "could be reached to confirm it. If someone controls your internet connection, the servers " +
            "and every peer you reach could be theirs. Use it only if you trust this connection."

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

/**
 * The question [warning] asks: [onFetch] asks for a fresh checkpoint (true: take one no long-known
 * peer confirmed, the user's "Use it anyway"); [onConfirm] catches up anyway, for this run;
 * [onDismiss] leaves it refused. While the servers are asked, nothing else can be chosen and the
 * dialog stays, so their answer is seen.
 */
@Composable
internal fun StaleAnchorDialog(
    warning: StaleAnchorWarning,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onFetch: (useUnconfirmed: Boolean) -> Unit = {},
) {
    val busy = warning.fetching
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(warning.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(warning.body)
                if (busy) Text(warning.fetchingText, fontWeight = FontWeight.SemiBold)
                warning.fetchProblem?.takeIf { !busy }?.let {
                    Text(it, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                }
                warning.unconfirmedWarning?.takeIf { !busy }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = { onFetch(false) },
                    enabled = !busy,
                    modifier = Modifier.testTag(STALE_ANCHOR_FETCH_TAG),
                ) {
                    Text(warning.fetch)
                }
                if (warning.unconfirmedWarning != null) {
                    TextButton(
                        onClick = { onFetch(true) },
                        enabled = !busy,
                        modifier = Modifier.testTag(STALE_ANCHOR_UNCONFIRMED_TAG),
                    ) {
                        Text(warning.useUnconfirmed, color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(
                    onClick = onConfirm,
                    enabled = !busy,
                    modifier = Modifier.testTag(STALE_ANCHOR_CONFIRM_TAG),
                ) {
                    Text(warning.confirm, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(warning.dismiss) } },
    )
}

/**
 * The stale-checkpoint dialog a screen opened with [asking], drawn from [current], the warning as
 * the screen's state has it now: it follows a fetch, and closes ([onClose]) once the network no
 * longer refuses. [onCatchUp] and [onFetch] take the network's chain id.
 */
@Composable
internal fun StaleAnchorQuestion(
    asking: StaleAnchorWarning?,
    current: (chainId: Long) -> StaleAnchorWarning?,
    onClose: () -> Unit,
    onCatchUp: (chainId: Long) -> Unit,
    onFetch: (chainId: Long, useUnconfirmed: Boolean) -> Unit,
) {
    val open = asking?.let { current(it.chainId) }
    if (asking != null && open == null) SideEffect { onClose() }
    open?.let { warning ->
        StaleAnchorDialog(
            warning,
            onConfirm = {
                onClose()
                onCatchUp(warning.chainId)
            },
            onDismiss = onClose,
            onFetch = { useUnconfirmed -> onFetch(warning.chainId, useUnconfirmed) },
        )
    }
}
