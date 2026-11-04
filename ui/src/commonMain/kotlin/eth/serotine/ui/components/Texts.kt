package eth.serotine.ui.components

import eth.serotine.domain.model.Network

/** "Main", "Main and Savings", "Main, Savings and Daily": a list as a sentence says it. */
internal fun spokenList(items: List<String>): String =
    if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString() + " and " + items.last()

/** What a Turn on that could not be saved says (docs/networks.md, N19): the network stays off. */
internal fun notTurnedOn(network: Network): String =
    "${network.displayName} could not be turned on: the setting cannot be saved right now. Try again, or in " +
        "Settings → Networks."
