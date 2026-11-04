package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One account of the wallet, as a stream a screen about that account renders: its entry,
 * re-emitted when it changes (a rename), or that it is not in the list (removed, or never
 * there), or that the list cannot be read right now.
 */
class ObserveAccount(private val accounts: AccountRepository) {
    operator fun invoke(id: AccountId): Flow<AccountLookup> = accounts.observeAccounts()
        .map { list ->
            when (list) {
                is AccountList.Unreadable -> AccountLookup.ListUnavailable
                is AccountList.Loaded ->
                    list.accounts.firstOrNull { it.id == id }?.let(AccountLookup::Found)
                        ?: AccountLookup.NotInList
            }
        }
        .distinctUntilChanged()
}

/** What [ObserveAccount] emits. */
sealed interface AccountLookup {
    /** The entry, as it is now. */
    data class Found(val account: Account) : AccountLookup

    /** No entry has the network and address: it was removed, or never added. */
    data object NotInList : AccountLookup

    /** The stored list cannot be read right now, so whether the account is in it is unknown. */
    data object ListUnavailable : AccountLookup
}
