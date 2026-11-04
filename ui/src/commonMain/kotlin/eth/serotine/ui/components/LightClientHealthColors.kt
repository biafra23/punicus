package eth.serotine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * This health's colour on the current theme: green, yellow or red, fixed hues rather than the
 * theme's roles (whose dark error is a pale pink), a lighter tone on a dark surface: each at
 * least 3:1 against its surface, the contrast a status mark needs.
 */
@Composable
@ReadOnlyComposable
internal fun LightClientHealth.healthColor(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (this) {
        LightClientHealth.VERIFIED -> if (dark) Color(0xFF81C995) else Color(0xFF1E8E3E)
        LightClientHealth.STALE -> if (dark) Color(0xFFFDD663) else Color(0xFFB88700)
        LightClientHealth.NOT_VERIFIED -> if (dark) Color(0xFFEE675C) else Color(0xFFC5221F)
        LightClientHealth.NOT_RUNNING -> if (dark) Color(0xFF9AA0A6) else Color(0xFF80868B)
    }
}

/** A small dot in [health]'s colour, beside the light client's state wherever it is written. */
@Composable
internal fun LightClientHealthDot(health: LightClientHealth, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(health.healthColor(), CircleShape))
}
