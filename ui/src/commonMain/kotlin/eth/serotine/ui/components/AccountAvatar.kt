package eth.serotine.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * An account's picture: the [Blockie] of its [address], and for a Safe its [threshold] over
 * its owner count (`2/3`) on the corner (docs/wallet-ux.md, *Rules every screen follows*). With a
 * [network] (simple mode, docs/simple-ui.md), that network's icon on the other corner, named for
 * a screen reader; the row names the network in text too, so the icon is never the only signal.
 */
@Composable
fun AccountAvatar(
    address: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    threshold: String? = null,
    network: NetworkLabel? = null,
) {
    Box(modifier) {
        Blockie(address, size = size)
        network?.let {
            NetworkIcon(
                it,
                Modifier.align(Alignment.BottomStart).offset(x = (-3).dp, y = 3.dp),
                size = if (size >= 56.dp) 22.dp else if (size <= 32.dp) 14.dp else 18.dp,
            )
        }
        if (threshold != null) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 4.dp),
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Text(
                    threshold,
                    Modifier.padding(horizontal = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * A network's icon (docs/simple-ui.md, decision 7): its colour ([networkColor]) with its mark,
 * Ethereum's diamond, Gnosis Chain's owl, and Sepolia's diamond, being an Ethereum testnet. Drawn
 * here, not bundled: no icon is fetched (`.claude/rules/data-sources.md`, *The token list is
 * built in*), and these are simplified marks, not the projects' logos.
 */
@Composable
internal fun NetworkIcon(network: NetworkLabel, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val color = networkColor(network.chainId)
    // The mark in the surface's light or dark, whichever stands out on the network's colour.
    val mark = if (color.luminance() > 0.4f) Color(0xFF141218) else Color.White
    Canvas(
        modifier
            .size(size)
            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            .background(color, CircleShape)
            .semantics { contentDescription = network.name },
    ) {
        when (network.chainId) {
            GNOSIS_CHAIN_ID -> owl(mark)
            else -> diamond(mark)
        }
    }
}

/** Ethereum's diamond, in two halves, within a 16-unit box. */
private fun DrawScope.diamond(mark: Color) {
    val unit = this.size.width / 16f
    drawPath(polygon(unit, 8f to 2.6f, 11.4f to 8.1f, 8f to 10.1f, 4.6f to 8.1f), mark)
    drawPath(polygon(unit, 8f to 10.9f, 11.4f to 8.9f, 8f to 13.4f, 4.6f to 8.9f), mark.copy(alpha = 0.75f))
}

/** A closed path through [points] of a 16-unit box, each unit [unit] wide. */
private fun polygon(unit: Float, vararg points: Pair<Float, Float>): Path = Path().apply {
    points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * unit, y * unit) else lineTo(x * unit, y * unit) }
    close()
}

/** A simple owl's face for Gnosis Chain: two eyes and a beak, within a 16-unit box. */
private fun DrawScope.owl(mark: Color) {
    val unit = this.size.width / 16f
    val eye = Stroke(width = 1.3f * unit)
    drawCircle(mark, radius = 2f * unit, center = Offset(5.6f * unit, 7.4f * unit), style = eye)
    drawCircle(mark, radius = 2f * unit, center = Offset(10.4f * unit, 7.4f * unit), style = eye)
    drawPath(polygon(unit, 6.8f to 11f, 8f to 12.6f, 9.2f to 11f), mark)
}

/** Gnosis Chain's chain id, whose icon is an owl. */
private const val GNOSIS_CHAIN_ID = 100L
