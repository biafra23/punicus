package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import kotlinx.coroutines.flow.first

/** How [addHeld] went: one outcome for every network, the most telling one. */
internal sealed interface HeldAdd {
    /** [account] is in the wallet, held in the key store, in one of the ways below. */
    sealed interface InWallet : HeldAdd {
        val account: Account
    }

    /** [account] is new in the wallet. */
    data class Added(override val account: Account) : InWallet

    /** [account] was in the wallet, held in the key store, already. */
    data class AlreadyHeld(override val account: Account) : InWallet

    /** [account] was in the wallet as a watched address; it is held in the key store from here on. */
    data class NowHeld(override val account: Account) : InWallet

    /** The account list could not be read or saved, so the account is not in it. */
    data object ListNotSaved : HeldAdd
}

/**
 * [address], whose key the key store has just stored, held on each of [networks], and wherever else
 * it is watched, since its key is here now: an [AccountKind.EOA] in the key store on each, all named
 * "Account N" ([nextFreeLabel]), N one more than the number of accounts, raised past any such name
 * already taken. The caller runs it under the app's [WalletChanges]. A list that cannot be saved is
 * said first, as adding again adds the rest; then a new account; then a watched one now held; then
 * that every one was there already.
 */
internal suspend fun AccountRepository.addHeld(address: Address, networks: List<Network>): HeldAdd {
    val watchedElsewhere = when (val list = observeAccounts().first()) {
        is AccountList.Loaded -> list.accounts
            .filter { it.address == address && it.custody == AccountCustody.Watched }
            .map { it.network }
        is AccountList.Unreadable -> return HeldAdd.ListNotSaved
    }
    // One name for every account added, taken from the list as the first add reads it.
    var label: String? = null
    val name = { current: List<Account> -> label ?: nextFreeLabel(current).also { label = it } }
    val outcomes = (networks + watchedElsewhere).distinct().map { network ->
        addOrFind(AccountId(network, address), name)
    }
    return outcomes.firstOrNull { it == HeldAdd.ListNotSaved }
        ?: outcomes.firstOrNull { it is HeldAdd.Added }
        ?: outcomes.firstOrNull { it is HeldAdd.NowHeld }
        ?: outcomes.first()
}

/**
 * The entry [id]: the existing one (a watched one now held in the key store), or a new one this
 * call adds. Read and add are two steps, so another writer can get in between; then the next round
 * reads again rather than assume what that writer left — an entry, or one already removed again.
 */
private suspend fun AccountRepository.addOrFind(id: AccountId, label: (List<Account>) -> String): HeldAdd {
    while (true) {
        val current = when (val list = observeAccounts().first()) {
            is AccountList.Loaded -> list.accounts
            is AccountList.Unreadable -> return HeldAdd.ListNotSaved
        }
        val existing = current.firstOrNull { it.id == id }
        if (existing != null) {
            if (existing.custody == AccountCustody.InKeyStore) return HeldAdd.AlreadyHeld(existing)
            // A watched address whose key the user has now: it is held, not watched, from here on.
            when (setCustody(id, AccountCustody.InKeyStore)) {
                AccountChange.Changed -> return HeldAdd.NowHeld(existing.copy(custody = AccountCustody.InKeyStore))
                AccountChange.NotSaved -> return HeldAdd.ListNotSaved
                AccountChange.NotFound -> continue // removed by another writer meanwhile: read again
            }
        }
        val account = Account(id.network, id.address, AccountKind.EOA, label(current), AccountCustody.InKeyStore)
        when (add(account)) {
            AccountAdd.Added -> return HeldAdd.Added(account)
            AccountAdd.NotSaved -> return HeldAdd.ListNotSaved
            AccountAdd.AlreadyPresent -> Unit // lost a race: read again
        }
    }
}
