package eth.serotine.ui.send

import eth.serotine.domain.repository.FeeLevel

/**
 * What Send opens with filled in, beside the account and the asset: the recipient [to] as text (an
 * address), the [amount] as text in the asset's units, and the [fee] level. All of it still the
 * user's to change, and the transfer theirs to review and sign: what Sign again and Edit open for a
 * queued transaction (docs/lenient-mode.md, *The outbox*), discarded first.
 */
data class SendFill(val to: String, val amount: String, val fee: FeeLevel)
