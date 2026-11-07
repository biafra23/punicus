package eth.serotine.ui.components

import eth.serotine.domain.usecase.HandoffOffer

/** What the action is called wherever it is offered (owner ruling, 2026-11-05, #255). */
const val SEND_FROM_ANOTHER_WALLET = "Send from another wallet"

/**
 * Send from another wallet, as an account's menus and the greyed-out Send offer it (issue #255):
 * enabled when [reason] is null, else greyed out, a tap saying [reason] (owner ruling, 2026-10-18,
 * #55). An account it does not apply to has none at all.
 */
data class HandoffChoice(val reason: String? = null) {
    /** Whether it can be done now. */
    val available: Boolean
        get() = reason == null
}

/**
 * [offer] for an account, as its menus show it: null where it does not apply, greyed out with why
 * where no wallet app takes payment links or the account's network is off ([networkOff], its
 * name), whose light client must read the blocks the transfer lands in.
 */
fun handoffChoice(offer: HandoffOffer, networkOff: String? = null): HandoffChoice? = when (offer) {
    HandoffOffer.LeftOut -> null
    HandoffOffer.NoWallet -> HandoffChoice(NO_WALLET)
    HandoffOffer.Offered -> HandoffChoice(networkOff?.let(::networkOffHandoff))
}

/** Why Send from another wallet is greyed out on a device where no app takes payment links. */
const val NO_WALLET = "No wallet app on this device opens payment links. Install one that holds this " +
    "account's key, such as MetaMask or Rainbow, then try again."

private fun networkOffHandoff(network: String): String =
    "$network is off, so Punicus cannot check what the other wallet sends. Turn $network on first."
