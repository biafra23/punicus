package eth.serotine.ui.currency

import eth.serotine.domain.model.DisplayCurrency

/** [currency]'s name as the screens give it ("Euro"). */
internal fun nameOf(currency: DisplayCurrency): String = when (currency) {
    DisplayCurrency.USD -> "US dollar"
    DisplayCurrency.EUR -> "Euro"
    DisplayCurrency.GBP -> "Pound sterling"
    DisplayCurrency.CHF -> "Swiss franc"
    DisplayCurrency.JPY -> "Japanese yen"
    DisplayCurrency.ETH -> "Ether"
    DisplayCurrency.BTC -> "Bitcoin"
}
