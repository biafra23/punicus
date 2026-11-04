package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Every account in the wallet with what it is worth, and the portfolio's total
 * (docs/wallet-ux.md, screen 1): one [ObserveAccountValue] per account ([readEach]), so an
 * account added or removed starts or stops its own reading only.
 */
class ObservePortfolio(
    private val observeAccounts: ObserveAccounts,
    private val observeAccountValue: ObserveAccountValue,
    private val observeDisplayCurrencies: ObserveDisplayCurrencies,
) {
    /** Totalled in the display currency, one for the wallet ([ObserveDisplayCurrencies]). */
    operator fun invoke(): Flow<Portfolio> {
        val values = observeAccounts().readEach(
            keys = { list -> (list as? AccountList.Loaded)?.accounts?.mapTo(LinkedHashSet()) { it.id }.orEmpty() },
            read = { id -> observeAccountValue(id) },
        )
        return combine(values, observeDisplayCurrencies()) { (accounts, read), currencies ->
            Portfolio(accounts, read, currencies.chosen, currencies.unpricedOn)
        }
    }
}

/**
 * The [accounts] as stored, what each is worth ([values], none for an account until its first
 * reading), the [currency] the [total] is in, and the networks with accounts that cannot price
 * it ([unpricedOn]), whose accounts have no value in it.
 */
data class Portfolio(
    val accounts: AccountList,
    val values: Map<AccountId, AccountValue>,
    val currency: DisplayCurrency,
    val unpricedOn: Set<Network> = emptySet(),
) {
    /**
     * The sum of the accounts' totals in [currency]. An account not read yet, or still valued in
     * the currency chosen before, makes it incomplete rather than adding a sum in another one.
     */
    val total: Total
        get() = totalOf { true }

    /** [total] of the accounts on [network] alone: a section of the Accounts screen (docs/networks.md, N1). */
    fun totalOn(network: Network): Total = totalOf { it == network }

    private fun totalOf(onNetwork: (Network) -> Boolean): Total {
        val loaded = (accounts as? AccountList.Loaded)?.accounts.orEmpty()
        return loaded.filter { onNetwork(it.network) }.fold(Total.NONE) { sum, account ->
            val value = values[account.id]
            sum + if (value != null && value.currency == currency) value.total else Total.PENDING
        }
    }
}
