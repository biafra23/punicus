package eth.serotine.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eth.serotine.domain.model.Network

/** Test tag of a network's check box row, followed by its chain id, for UI tests. */
const val NETWORK_CHOICE_TAG = "network-choice-"

/**
 * One network an account could be added on (docs/networks.md, N6, N8 and N9): its [name], whether
 * it is a [testnet], what holding an account there means ([line]: "Real funds", "Testnet: test
 * ether only"), whether it is [on] in this wallet (one that is off is greyed out, with Turn on as
 * the way to choose it), and whether it is [chosen]. [chainId] names it to the intents.
 */
data class NetworkChoice(
    val chainId: Long,
    val name: String,
    val testnet: Boolean,
    val on: Boolean,
    val chosen: Boolean = false,
    val line: String? = null,
)

/**
 * [networks] as check boxes, in `Network` order: those [on] in this wallet, those [chosen] among
 * them ticked, none ticked unless chosen (the question is asked, not pre-answered: N8).
 */
internal fun networkChoices(
    on: Set<Network>,
    chosen: Set<Network>,
    networks: List<Network> = Network.entries,
    /** Whether each says what an account there holds: N8 and N9 do, N6's short sheet does not. */
    withLines: Boolean = true,
): List<NetworkChoice> = networks.map { network ->
    NetworkChoice(
        network.chainId,
        network.displayName,
        network.testnet,
        on = network in on,
        chosen = network in chosen && network in on,
        line = networkLine(network).takeIf { withLines },
    )
}

/** What holding an account on [network] means, for its check box: "Real funds", "Testnet: test ether only". */
internal fun networkLine(network: Network): String = when {
    network.testnet -> "Testnet: test ${network.nativeName.lowercase()} only"
    network.nativeSymbol == "ETH" -> "Real funds"
    else -> "Real funds, fees in ${network.nativeName}"
}

/** What an add greyed out while no network is ticked says (docs/networks.md, N8 and N9). */
internal const val NO_NETWORK_TICKED = "Tick at least one network to add the account to. Each network ticked " +
    "adds an account of its own, under the same key."

/** The networks ticked among [this] that are on: those an account is added on. */
internal fun List<NetworkChoice>.chosenNetworks(): List<Network> =
    filter { it.chosen && it.on }.mapNotNull { Network.ofChainId(it.chainId) }

/**
 * [choices] as check boxes: each ticked or unticked through [onToggle] while [enabled]; one that is
 * off greyed out, with Turn on ([onTurnOn]) as the way to choose it (docs/networks.md, *Networks on
 * and off*).
 */
@Composable
internal fun NetworkChecklist(
    choices: List<NetworkChoice>,
    enabled: Boolean,
    onToggle: (chainId: Long) -> Unit,
    onTurnOn: (chainId: Long) -> Unit,
) {
    choices.forEach { choice ->
        Row(
            Modifier.fillMaxWidth()
                .toggleable(
                    value = choice.chosen,
                    enabled = choice.on && enabled,
                    role = Role.Checkbox,
                    onValueChange = { onToggle(choice.chainId) },
                )
                .testTag(NETWORK_CHOICE_TAG + choice.chainId)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = choice.chosen, onCheckedChange = null, enabled = choice.on && enabled)
            Column(Modifier.weight(1f).padding(start = 8.dp).alpha(if (choice.on) 1f else 0.6f)) {
                NetworkMark(choice.chainId, choice.name, choice.testnet, style = MaterialTheme.typography.bodyLarge)
                val line = if (choice.on) {
                    choice.line
                } else {
                    listOfNotNull("Off in this wallet", choice.line).joinToString(" · ")
                }
                line?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!choice.on) {
                TextButton(onClick = { onTurnOn(choice.chainId) }, enabled = enabled) { Text("Turn on") }
            }
        }
    }
}
