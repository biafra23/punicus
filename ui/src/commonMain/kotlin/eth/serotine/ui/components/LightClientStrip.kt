package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The status strip, on every screen that draws it. */
const val LIGHT_CLIENT_STRIP_TAG = "light-client-strip"

/**
 * What the light client is doing, until and after it serves verified reads, in its health's
 * colour: a dot before the text, and the bar under it (owner's request, 2026-10-19). Drawn
 * under the top bar of every screen whose content or actions depend on what the light client
 * can verify (docs/wallet-ux.md), so a screen waiting on it says why.
 */
@Composable
fun LightClientStrip(item: LightClientItem, modifier: Modifier = Modifier) {
    val color = item.health.healthColor()
    Column(modifier.fillMaxWidth().testTag(LIGHT_CLIENT_STRIP_TAG)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightClientHealthDot(item.health)
            Text(
                item.text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val track = color.copy(alpha = TRACK_ALPHA)
        when {
            item.progress != null -> LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier.fillMaxWidth(),
                color = color,
                trackColor = track,
            )
            item.working -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = color, trackColor = track)
            // Thinner than a progress bar: a settled state, not one under way.
            else -> HorizontalDivider(thickness = 2.dp, color = color)
        }
    }
}

/**
 * A screen's body under the [LightClientStrip] of [item]: the strip on top, [content] in the
 * rest of the space [modifier] (the scaffold's padding) leaves.
 */
@Composable
fun UnderLightClientStrip(
    item: LightClientItem,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        LightClientStrip(item)
        Box(Modifier.weight(1f).fillMaxWidth(), content = content)
    }
}

/**
 * [content] under the light client's strip where there is one ([item], Detailed view's), and on its
 * own where there is none: simple mode, which says how fresh its values are in its own words
 * (docs/simple-ui.md), under its [SyncLine] of [syncLine] while that has something to say.
 */
@Composable
fun UnderLightClientStripIfAny(
    item: LightClientItem?,
    modifier: Modifier = Modifier,
    syncLine: LightClientItem? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    when {
        item != null -> UnderLightClientStrip(item, modifier, content)
        syncLine?.simpleText != null -> Column(modifier.fillMaxSize()) {
            SyncLine(syncLine)
            Box(Modifier.weight(1f).fillMaxWidth(), content = content)
        }
        else -> Box(modifier.fillMaxSize(), content = content)
    }
}

/** Simple mode's sync line, on every screen that draws one. */
const val SYNC_LINE_TAG = "sync-line"

/**
 * Simple mode's sync line (docs/simple-ui.md, *The sync line*; owner's requests, 2026-10-29): what
 * keeps [item]'s network from verified reads, in plain words and the strip's colour, with its
 * progress where there is a fraction. Nothing while [LightClientItem.simpleText] is null.
 */
@Composable
fun SyncLine(item: LightClientItem, modifier: Modifier = Modifier) {
    val text = item.simpleText ?: return
    LightClientStrip(item.copy(text = text), modifier.testTag(SYNC_LINE_TAG))
}

/** How strongly a progress bar's track shows its colour under the part done. */
private const val TRACK_ALPHA = 0.4f
