package eth.serotine.domain.usecase

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.CurrencyChoice
import eth.serotine.domain.repository.DisplayCurrencySetting
import eth.serotine.domain.repository.PriceFeeds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The display currency (docs/wallet-ux.md, screen 19; docs/networks.md, *Values*): one for the
 * whole wallet, the one chosen, and the ones offered, those at least one network with accounts
 * can price (their own USD rate has a feed on it; every network's when the wallet has no account,
 * or its list cannot be read). A choice none of them offers, or none yet, is USD. A network with
 * accounts that cannot price the chosen one is named in [DisplayCurrencies.unpricedOn]: its
 * accounts have no value in it, and are left out of every total, rather than valued in another.
 */
class ObserveDisplayCurrencies(
    private val feeds: PriceFeeds,
    private val setting: DisplayCurrencySetting,
    private val accounts: AccountRepository,
) {
    operator fun invoke(): Flow<DisplayCurrencies> =
        combine(setting.observe(), heldNetworks(accounts)) { choice, held ->
            val considered = held.ifEmpty { Network.entries.toSet() }
            val offered = DisplayCurrency.entries.filter { currency -> considered.any { feeds.prices(it, currency) } }
            val chosen = (choice as? CurrencyChoice.Chosen)?.currency?.takeIf { it in offered } ?: DisplayCurrency.USD
            DisplayCurrencies(
                chosen,
                offered,
                unreadable = choice is CurrencyChoice.Unreadable,
                setAside = (choice as? CurrencyChoice.Chosen)?.setAside == true,
                unpricedOn = held.filterTo(LinkedHashSet()) { !feeds.prices(it, chosen) },
            )
        }.distinctUntilChanged()

    /**
     * The currency a figure on [network] alone is shown in: the chosen one where [network] can
     * price it, USD otherwise (a home-screen widget, which shows one network's rate).
     */
    fun on(network: Network): Flow<DisplayCurrency> =
        invoke().map { it.chosen.takeIf { chosen -> feeds.prices(network, chosen) } ?: DisplayCurrency.USD }
            .distinctUntilChanged()
}

/** Chooses the display currency; false when no network with accounts offers it or it could not be saved. */
class SetDisplayCurrency(
    private val feeds: PriceFeeds,
    private val setting: DisplayCurrencySetting,
    private val accounts: AccountRepository,
) {
    suspend operator fun invoke(currency: DisplayCurrency): Boolean {
        val considered = heldNetworks(accounts).first().ifEmpty { Network.entries.toSet() }
        return considered.any { feeds.prices(it, currency) } && setting.choose(currency)
    }
}

/**
 * The [chosen] display currency, the [offered] ones in their order, whether the stored choice
 * cannot be read ([unreadable]: [chosen] is then USD, and nothing can be chosen), whether it was
 * damaged and set aside, so [chosen] is USD until another is chosen ([setAside]), and the
 * networks with accounts that cannot price [chosen] ([unpricedOn]).
 */
data class DisplayCurrencies(
    val chosen: DisplayCurrency,
    val offered: List<DisplayCurrency>,
    val unreadable: Boolean = false,
    val setAside: Boolean = false,
    val unpricedOn: Set<Network> = emptySet(),
)

/** Whether [network] can price [currency]: USD always, any other with its USD feed there. */
private fun PriceFeeds.prices(network: Network, currency: DisplayCurrency): Boolean =
    currency.priceId == PriceId.USD || currency.priceId in quotes(network)

/** The networks the wallet has accounts on; none while the list cannot be read. */
private fun heldNetworks(accounts: AccountRepository): Flow<Set<Network>> =
    accounts.observeAccounts().map { list ->
        (list as? AccountList.Loaded)?.accounts?.mapTo(LinkedHashSet()) { it.network }.orEmpty()
    }.distinctUntilChanged()
