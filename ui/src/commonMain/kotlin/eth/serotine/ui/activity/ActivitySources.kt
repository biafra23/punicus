package eth.serotine.ui.activity

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.usecase.IndexedHistory
import eth.serotine.domain.usecase.KnownToken
import eth.serotine.domain.usecase.KnownTokenList
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveSafeHistory
import eth.serotine.domain.usecase.ObserveSentToAddresses
import eth.serotine.domain.usecase.ObserveTokenTransfers
import eth.serotine.domain.usecase.SafeChainEvent
import eth.serotine.domain.usecase.TokenTransferEvent
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.shortAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * What the Activity of the account at [address] and the detail of its rows are read from, besides
 * its sent transactions (docs/wallet-ux.md, screens 25 and 26): one place, so a row and its detail
 * are read alike. Cold flows: each collector reads them anew. [observeSentTo] gives the addresses
 * the wallet sent to ([Parties.familiar]).
 */
internal class ActivitySources(
    private val account: AccountId,
    observeAccounts: ObserveAccounts,
    observeSafeHistory: ObserveSafeHistory,
    observeTokenTransfers: ObserveTokenTransfers,
    observeKnownTokens: ObserveKnownTokens,
    observeSentTo: ObserveSentToAddresses,
) {
    private val address: Address = account.address

    private val accounts: Flow<AccountList> = observeAccounts()

    /** The wallet's accounts and the addresses it sent to: whom the rows name, and which senders they caution about. */
    val parties: Flow<Parties> = combine(
        accounts,
        observeSentTo(),
    ) { list, sentTo -> Parties(list, sentTo, account.network) }

    /** The account's kind while it is in the wallet; null while it is not. */
    private val kind = accounts
        .map { list -> (list as? AccountList.Loaded)?.accounts?.firstOrNull { it.id == account }?.kind }
        .distinctUntilChanged()

    /** The Safe's own events while the account is a Safe of the wallet; null for any other. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val safeHistory: Flow<IndexedHistory<SafeChainEvent>?> = kind.flatMapLatest {
        if (it == AccountKind.SAFE) observeSafeHistory(account) else flowOf(null)
    }

    /** The account's token transfers while it is in the wallet; null while it is not. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val transfers: Flow<IndexedHistory<TokenTransferEvent>?> =
        kind.flatMapLatest { if (it != null) observeTokenTransfers(account) else flowOf(null) }

    /**
     * The tokens the wallet knows, by address, shown or not (a row says why one it does not show
     * is not named); null until the list is read, so that the rows already known never wait for
     * the tokens' checks.
     */
    val tokens: Flow<Map<Address, KnownToken>?> = observeKnownTokens(account.network)
        .map<KnownTokenList, Map<Address, KnownToken>?> { list -> list.tokens.associateBy { it.token.address } }
        .onStart { emit(null) }

    /** The account's name, or its short address while it is not in the wallet. */
    fun titleOf(names: Map<Address, String>): String = names[address] ?: shortTitle

    /** The account's short address: its title until its name is read. */
    val shortTitle: String
        get() = shortAddress(Eip55.format(address))
}

/**
 * A fungible transfer of nothing: it moves no value, and anyone can have a token log one from any
 * address (`transferFrom` of zero needs no allowance), which is how a look-alike address is planted
 * in a history for the user to copy. Neither listed nor detailed.
 */
internal fun TokenTransfer.movesNothing(): Boolean = this is TokenTransfer.Fungible && amount.isZero

/**
 * The wallet's accounts as [list] holds them, and the addresses it has sent to ([sentTo],
 * `ObserveSentToAddresses`): the names rows of an account on [network] give addresses, and the
 * senders they trust.
 */
internal class Parties(val list: AccountList, private val sentTo: Set<Address>, network: Network) {

    /**
     * The wallet's names for addresses on [network], by address: an address is named by its
     * account on the network the rows are about, never another network's; none while the
     * accounts cannot be read.
     */
    val names: Map<Address, String> =
        (list as? AccountList.Loaded)?.accounts.orEmpty()
            .filter { it.network == network }
            .associate { it.address to it.label }

    /**
     * Whether a transfer from [sender] is from someone the user deals with: one of the wallet's
     * accounts, an address it sent to, or none (the zero address, a mint). Any other sender may be a
     * look-alike of an address the user uses, sending a little so that it is copied from the history
     * later (owner's request on #136, 2026-10-21): its rows and detail caution about it.
     */
    fun familiar(sender: Address): Boolean = sender == Address.ZERO || sender in names || sender in sentTo
}
