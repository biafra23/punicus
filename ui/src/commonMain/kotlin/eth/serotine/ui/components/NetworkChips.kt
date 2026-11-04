package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Test tag of a network's chip, followed by its chain id, for UI tests. */
const val NETWORK_CHIP_TAG = "network-chip-"

/**
 * One network as a screen's first choice (docs/networks.md, N12 and N17): its [network], whether it
 * is [selected], and why it cannot be chosen now ([reason]; it is [off], or the screen's own reason);
 * greyed out while there is one, a tap saying it.
 */
data class NetworkChip(
    val network: NetworkLabel,
    val selected: Boolean,
    val reason: String? = null,
    val off: Boolean = false,
)

/**
 * [chips] in a row under a "Network" heading: a tap chooses one ([onChoose]); one that cannot be
 * chosen is greyed out, and a tap says why under the title "[action] <network>", with Turn on
 * ([onTurnOn]) for one that is off (owner ruling, 2026-10-18, #55).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NetworkChips(
    chips: List<NetworkChip>,
    action: String,
    onChoose: (chainId: Long) -> Unit,
    onTurnOn: (chainId: Long) -> Unit,
) {
    var unavailable by remember { mutableStateOf<UnavailableNotice?>(null) }
    unavailable?.let { notice -> UnavailableDialog(notice, onDismiss = { unavailable = null }) }
    Heading("Network")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { chip ->
            val network = chip.network
            FilterChip(
                selected = chip.selected,
                onClick = {
                    val reason = chip.reason
                    if (reason == null) {
                        onChoose(network.chainId)
                    } else {
                        val turnOn = { onTurnOn(network.chainId) }
                        unavailable = UnavailableNotice(
                            "$action ${network.name}",
                            reason,
                            if (chip.off) listOf(UnavailableFix("Turn on ${network.name}", turnOn)) else emptyList(),
                        )
                    }
                },
                label = { NetworkMark(network, Modifier.alpha(if (chip.reason == null) 1f else 0.6f)) },
                modifier = Modifier.testTag(NETWORK_CHIP_TAG + network.chainId).unavailable(chip.reason != null),
            )
        }
    }
}
