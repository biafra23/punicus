package eth.serotine.domain.repository

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import kotlinx.coroutines.flow.Flow

/**
 * The port through which the domain reaches the accounts the wallet manages: networks, addresses, kinds and labels,
 * never key material. An account is named by its [AccountId], an address on a network: the same address may be listed
 * once on each network. :data supplies the implementation (a file the list survives restarts in); the UI never sees
 * this interface, only the use cases that consume it.
 */
interface AccountRepository {
    /**
     * The managed accounts, re-emitted on each change, or why they cannot be shown. In a
     * [AccountList.Loaded] each [AccountId] appears at most once — an adapter that can receive
     * the same one twice keeps the first — so consumers may key on it. Never throws: a
     * stored list that cannot be read is [AccountList.Unreadable], never an empty list.
     */
    fun observeAccounts(): Flow<AccountList>

    /**
     * Appends [account] to the list ([AccountAdd.Added]); or, when its [Account.id] is already managed (the same
     * address on the same network), leaves the existing entry exactly as it is ([AccountAdd.AlreadyPresent]); or, when
     * the list cannot be stored or is [AccountList.Unreadable], changes nothing ([AccountAdd.NotSaved]). Never throws.
     */
    suspend fun add(account: Account): AccountAdd

    /**
     * Gives the entry [id] the name [label], the user's own from then on (its
     * [Account.ensName] cleared), keeping everything else about it ([AccountChange.Changed]);
     * or, when there is no such entry, changes nothing ([AccountChange.NotFound]); or, when
     * the list cannot be stored or is [AccountList.Unreadable], changes nothing
     * ([AccountChange.NotSaved]). [label] follows
     * [Account.label]'s rule: a blank one is the caller's error and throws. Never throws
     * otherwise.
     */
    suspend fun rename(id: AccountId, label: String): AccountChange

    /**
     * Records that the entry [id] is now held as [custody], keeping everything
     * else about it ([AccountChange.Changed]); or [AccountChange.NotFound]; or, when the list
     * cannot be stored or is [AccountList.Unreadable], [AccountChange.NotSaved]. A watched
     * account becomes [AccountCustody.InKeyStore] when its key is imported. Never throws.
     */
    suspend fun setCustody(id: AccountId, custody: AccountCustody): AccountChange

    /**
     * Takes the entry [id] out of the list ([AccountChange.Changed]); or, when there is no
     * such entry, changes nothing ([AccountChange.NotFound]); or, when the list
     * cannot be stored or is [AccountList.Unreadable], changes nothing
     * ([AccountChange.NotSaved]). Only the entry: a key the key store holds for the address
     * is the key store's to delete, and it may still be the key of the same address on another
     * network. Never throws.
     */
    suspend fun remove(id: AccountId): AccountChange
}

/** What [AccountRepository.observeAccounts] emits. */
sealed interface AccountList {

    /**
     * The [accounts], in display order. [damagedListSetAside] is true when the stored list
     * was damaged and has been set aside, unread, under another name, so these are only
     * the accounts added since: the user should hear about it.
     */
    data class Loaded(val accounts: List<Account>, val damagedListSetAside: Boolean = false) : AccountList

    /**
     * A stored list exists but cannot be used, for the reason [reason] gives; it is left
     * untouched, and nothing can be added until it can be read.
     */
    data class Unreadable(val reason: Reason) : AccountList

    /** Why an [Unreadable] list cannot be used. */
    enum class Reason {
        /** Written by a newer version of the app, in a format this one does not know. */
        NEWER_FORMAT,

        /** The file could not be read, or a damaged one could not be set aside. */
        READ_FAILED,
    }
}

/** The outcome of [AccountRepository.add]. */
sealed interface AccountAdd {
    /** The account is in the list now. */
    data object Added : AccountAdd

    /** Its address was in the list already on its network; the existing entry is unchanged. */
    data object AlreadyPresent : AccountAdd

    /** The list could not be stored, or cannot be read; nothing changed. */
    data object NotSaved : AccountAdd
}

/** The outcome of [AccountRepository.rename] and [AccountRepository.remove]. */
sealed interface AccountChange {
    /** The change is in the list now. */
    data object Changed : AccountChange

    /** No entry has that network and address; nothing changed. */
    data object NotFound : AccountChange

    /** The list could not be stored, or cannot be read; nothing changed. */
    data object NotSaved : AccountChange
}
