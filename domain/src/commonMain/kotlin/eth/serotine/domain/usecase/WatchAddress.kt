package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.flow.first

/**
 * Adds an address on a network to the wallet to watch it there (issues #9 and #10). Only what the light client
 * proves is an externally owned account — with or without an EIP-7702 delegation — or a Safe
 * is added, as an [AccountKind.EOA] or [AccountKind.SAFE] held as [AccountCustody.Watched];
 * another contract is refused, and so is an address whose kind cannot be verified right now:
 * the account's kind is chain data, shown verified or not at all. An address already in the
 * wallet on that network, watched or with its key, is refused and named. [label] is trimmed; empty, the
 * account's name follows [ensName], the ENS name the address was resolved from (issue #67), or,
 * with none or one longer than [RenameAccount.MAX_LABEL_LENGTH], is "Account N" as an import
 * names it. On a network that resolves no names ([Network.ensNames] false: Gnosis Chain), the
 * account follows none: a fitting [ensName] is only its label, given once and never followed.
 * The caller resolves [ensName] to [address] just before; whether it still points
 * there is checked again while it is shown ([ObserveEnsNames]).
 */
class WatchAddress(
    private val accounts: AccountRepository,
    private val kinds: AddressKinds,
) {
    /**
     * The name to watch an address under on several networks, all alike: [label] trimmed when the
     * user gave one, empty when the accounts follow [ensName] (as [invoke] decides), otherwise one
     * "Account N" taken once from the list as it is now; null when the list cannot be read.
     */
    suspend fun sharedName(label: String, ensName: String?): String? {
        val name = label.trim()
        if (name.isNotEmpty()) return name
        if (ensName != null && ensName.length <= RenameAccount.MAX_LABEL_LENGTH) return ""
        return when (val list = accounts.observeAccounts().first()) {
            is AccountList.Unreadable -> null
            is AccountList.Loaded -> nextFreeLabel(list.accounts)
        }
    }

    suspend operator fun invoke(
        network: Network,
        address: Address,
        label: String,
        ensName: String? = null,
    ): AddressWatch {
        val name = label.trim()
        if (name.length > RenameAccount.MAX_LABEL_LENGTH) return AddressWatch.NameTooLong
        val current = when (val list = accounts.observeAccounts().first()) {
            is AccountList.Unreadable -> return AddressWatch.NotSaved
            is AccountList.Loaded -> list.accounts
        }
        current.firstOrNull { it.network == network && it.address == address }
            ?.let { return AddressWatch.AlreadyInWallet(it) }
        val kind = when (val reading = kinds.observeKind(network, address).first()) {
            KindReading.Syncing -> return AddressWatch.NotVerified(null)
            is KindReading.Unavailable -> return AddressWatch.NotVerified(reading.reason)
            is KindReading.Verified -> when (reading.kind) {
                AddressKind.Contract -> return AddressWatch.Contract
                is AddressKind.Safe -> AccountKind.SAFE
                AddressKind.Eoa, is AddressKind.DelegatedEoa -> AccountKind.EOA
            }
        }
        // A name too long for a row is no name to follow. Where names are not resolved (Gnosis
        // Chain), the account follows none: the name it was found by is only its label.
        val fitting = ensName?.takeIf { it.length <= RenameAccount.MAX_LABEL_LENGTH }
        val follows = fitting?.takeIf { name.isEmpty() && network.ensNames }
        val account = Account(
            network,
            address,
            kind,
            follows ?: name.ifEmpty { fitting ?: nextFreeLabel(current) },
            AccountCustody.Watched,
            ensName = follows,
        )
        return when (accounts.add(account)) {
            AccountAdd.Added -> AddressWatch.Added(account)
            AccountAdd.NotSaved -> AddressWatch.NotSaved
            // Added by another writer since the list was read: name what is there now.
            AccountAdd.AlreadyPresent -> when (val list = accounts.observeAccounts().first()) {
                is AccountList.Loaded -> list.accounts.firstOrNull { it.id == account.id }
                    ?.let { AddressWatch.AlreadyInWallet(it) } ?: AddressWatch.NotSaved
                is AccountList.Unreadable -> AddressWatch.NotSaved
            }
        }
    }
}

/** The outcome of [WatchAddress]. */
sealed interface AddressWatch {

    /** [account] is in the wallet, watched. */
    data class Added(val account: Account) : AddressWatch

    /** The address is in the wallet already on that network, as [account]; nothing changed. */
    data class AlreadyInWallet(val account: Account) : AddressWatch

    /** The address holds a contract that is not a recognised Safe; nothing changed. */
    data object Contract : AddressWatch

    /**
     * The light client cannot prove the address's kind right now: still syncing ([reason]
     * null), or for the [reason] given. Nothing changed.
     */
    data class NotVerified(val reason: ReadUnavailable?) : AddressWatch

    /** The name is longer than [RenameAccount.MAX_LABEL_LENGTH]; nothing changed. */
    data object NameTooLong : AddressWatch

    /** The list could not be saved, or cannot be read; nothing changed. */
    data object NotSaved : AddressWatch
}
