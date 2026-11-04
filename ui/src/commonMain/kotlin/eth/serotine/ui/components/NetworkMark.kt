package eth.serotine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import eth.serotine.domain.model.Network

/** A network as a screen names it: its [chainId], its [name] and whether it is a [testnet]. */
data class NetworkLabel(val chainId: Long, val name: String, val testnet: Boolean) {
    companion object {
        /** [network]'s label. */
        fun of(network: Network): NetworkLabel = NetworkLabel(network.chainId, network.displayName, network.testnet)
    }
}

/** [label]'s mark. */
@Composable
internal fun NetworkMark(
    label: NetworkLabel,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleSmall,
) = NetworkMark(label.chainId, label.name, label.testnet, modifier, style)

/**
 * A network's mark (docs/networks.md, N1): its colour beside its [name], and "Testnet" after a
 * [testnet]'s, so the colour is never the only signal. [chainId] picks the colour.
 */
@Composable
internal fun NetworkMark(
    chainId: Long,
    name: String,
    testnet: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleSmall,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(networkColor(chainId), CircleShape))
        Text(if (testnet) "$name · Testnet" else name, style = style)
    }
}

/**
 * Each network's own colour, a lighter tone on a dark surface: Ethereum's blue, Gnosis Chain's
 * green, Sepolia's amber, and grey for a chain without one.
 */
@Composable
@ReadOnlyComposable
internal fun networkColor(chainId: Long): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (chainId) {
        1L -> if (dark) Color(0xFF8FA4F0) else Color(0xFF4A63C9)
        100L -> if (dark) Color(0xFF6FBF9A) else Color(0xFF2F6E52)
        11_155_111L -> if (dark) Color(0xFFF2C25C) else Color(0xFFB07A00)
        else -> if (dark) Color(0xFF9AA0A6) else Color(0xFF80868B)
    }
}
