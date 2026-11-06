package eth.serotine.ui.components

import eth.serotine.domain.model.UInt256

/**
 * [number]'s digits as screens show an NFT's id or amount: whole when short, its first six and last
 * four digits around an ellipsis past twelve ("982531…4107"; an ENS name's id has 77 digits). The
 * Activity's rows and the account's NFTs share it, so one id reads the same in both.
 */
fun shortDigits(number: UInt256): String {
    val digits = number.toDecimal()
    return if (digits.length > MAX_DIGITS) digits.take(6) + "…" + digits.takeLast(4) else digits
}

private const val MAX_DIGITS = 12
