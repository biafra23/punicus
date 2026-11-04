package eth.serotine.ui.activitydetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeEvent
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.usecase.BuiltInCheck
import eth.serotine.domain.usecase.IndexedEvent
import eth.serotine.domain.usecase.IndexedHistory
import eth.serotine.domain.usecase.KnownToken
import eth.serotine.domain.usecase.ObserveAccountHistory
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveAmountsHidden
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveSafeHistory
import eth.serotine.domain.usecase.ObserveSentToAddresses
import eth.serotine.domain.usecase.ObserveTokenTransfers
import eth.serotine.domain.usecase.SafeChainEvent
import eth.serotine.domain.usecase.ToggleAmountsHidden
import eth.serotine.domain.usecase.TokenTransferEvent
import eth.serotine.ui.activity.ActivityKey
import eth.serotine.ui.activity.ActivityRow
import eth.serotine.ui.activity.ActivityRows
import eth.serotine.ui.activity.ActivitySources
import eth.serotine.ui.activity.ActivityState
import eth.serotine.ui.activity.Parties
import eth.serotine.ui.activity.movesNothing
import eth.serotine.ui.components.DecodingToken
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.grouped
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The ViewModel of the detail of one row of the Activity of the account at [address]
 * (docs/wallet-ux.md, screen 26), the row [key] names: a transaction this device sent, read from
 * the account's history ([ObserveAccountHistory]); or a token's transfer or a Safe's event, read
 * from the engine's verified log index as the Activity reads them ([ObserveTokenTransfers],
 * [ObserveSafeHistory]). Its headline and status are its row's ([ActivityRows]); its fields name
 * every address and hash it concerns in full, with the wallet's names for them ([ObserveAccounts])
 * and the tokens' symbols and decimals ([ObserveKnownTokens]); a Safe transaction approved or
 * executed, sent from here or seen on chain, is headed with what it does as its row is, from the
 * request this device holds of it. One received from an address the
 * wallet never sent to ([ObserveSentToAddresses]) and that is none of its accounts says why to
 * compare it before copying it. A transfer of a contract the wallet
 * does not list is a claim, as its row is: only the contract is named, and an ERC-20 one can be
 * added. No intents: Copy is the screen's own, Status and Add token are navigation.
 */
class ActivityDetailViewModel(
    private val account: AccountId,
    private val key: ActivityKey,
    observeAccountHistory: ObserveAccountHistory,
    observeAccounts: ObserveAccounts,
    observeSafeHistory: ObserveSafeHistory,
    observeTokenTransfers: ObserveTokenTransfers,
    observeKnownTokens: ObserveKnownTokens,
    observeSentTo: ObserveSentToAddresses,
    /** Whether amounts are hidden (docs/wallet-ux.md, *Hiding amounts*): the eye in the top bar shows it. */
    observeAmountsHidden: ObserveAmountsHidden,
    /** Hides amounts or shows them again: what a tap on the eye asks. */
    private val toggleAmountsHidden: ToggleAmountsHidden,
    private val formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {
    /** The account this screen is about: its address, on its network. */
    private val address: Address = account.address

    /** The account's network: everything here is read and sent there. */
    private val network: Network = account.network


    /**
     * What a row of this account says, with its amounts masked while they are [hidden], a Safe
     * transaction in the units of the [tokens] known and by the requests in [entries], as the
     * Activity says it.
     */
    private fun rowsFor(hidden: Boolean, tokens: Map<Address, KnownToken>?, entries: List<HistoryEntry>) =
        ActivityRows(
            address,
            network,
            formatTime,
            hidden,
            decodingTokens = tokens?.values.orEmpty()
                .filter { it.check !is BuiltInCheck.Disagrees }
                .map { DecodingToken(it.token, it.sharesBuiltInSymbol) },
            requests = entries.mapNotNull {
                when (val action = it.transfer.action) {
                    is SentAction.SafeApproval -> action.request
                    is SentAction.SafeExecution -> action.request
                    else -> null
                }
            }.associateBy { it.hash },
        )

    private val sources = ActivitySources(
        account,
        observeAccounts,
        observeSafeHistory,
        observeTokenTransfers,
        observeKnownTokens,
        observeSentTo,
    )

    /**
     * Only what the row is read from is followed: the history for a sent one, the log index for an
     * event; the history beside an event too, for the request of a Safe transaction it may be of.
     */
    private val shown: Flow<ActivityDetailUiState> = observeAmountsHidden().let { hiddenFlow ->
        if (key.logIndex == null) {
            combine(
                observeAccountHistory(account),
                sources.parties,
                combine(sources.tokens, hiddenFlow, ::Pair),
            ) { history, parties, (tokens, hidden) ->
                val entries = (history as? HistoryList.Loaded)?.entries.orEmpty()
                renderSent(history, parties, rowsFor(hidden, tokens, entries)).withHidden(hidden)
            }
        } else {
            combine(
                sources.parties,
                sources.safeHistory,
                sources.transfers,
                combine(sources.tokens, hiddenFlow, ::Pair),
                observeAccountHistory(account),
            ) { parties, safe, transfers, (tokens, hidden), history ->
                val entries = (history as? HistoryList.Loaded)?.entries.orEmpty()
                renderLogged(parties, safe, transfers, tokens, rowsFor(hidden, tokens, entries)).withHidden(hidden)
            }
        }
    }

    /**
     * Hides amounts, or shows them again: the eye in the top bar. This screen has no message to say a
     * choice that could not be saved: the eye simply stays as it was.
     */
    fun toggleAmounts() {
        viewModelScope.launch { toggleAmountsHidden() }
    }

    /** [this] with the eye's state, where it is drawn. */
    private fun ActivityDetailUiState.withHidden(hidden: Boolean): ActivityDetailUiState =
        if (this is ActivityDetailUiState.Shown) copy(amountsHidden = hidden) else this

    val uiState: StateFlow<ActivityDetailUiState> = shown.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        ActivityDetailUiState.Loading(sources.shortTitle),
    )

    private fun renderSent(history: HistoryList, parties: Parties, rows: ActivityRows): ActivityDetailUiState {
        val names = parties.names
        val title = sources.titleOf(names)
        val entries = when (history) {
            HistoryList.Unreadable -> return ActivityDetailUiState.Unavailable(title, UNREADABLE)
            is HistoryList.Loaded -> history.entries
        }
        val entry = entries.firstOrNull { it.transfer.hash == key.hash }
            ?: return ActivityDetailUiState.Unavailable(title, NOT_SENT_HERE)
        return shownOf(title, rows.row(entry, names), sentFields(entry, names, rows), opensStatus = entry.transfer.hash)
    }

    private fun renderLogged(
        parties: Parties,
        safe: IndexedHistory<SafeChainEvent>?,
        transfers: IndexedHistory<TokenTransferEvent>?,
        tokens: Map<Address, KnownToken>?,
        rows: ActivityRows,
    ): ActivityDetailUiState {
        val names = parties.names
        val title = sources.titleOf(names)
        if (parties.list !is AccountList.Loaded) return ActivityDetailUiState.Unavailable(title, ACCOUNTS_UNREADABLE)
        // Only an account of the wallet has its logs followed.
        if (transfers == null) return ActivityDetailUiState.Unavailable(title, NOT_IN_WALLET)
        safe.found()?.let { return shownOf(title, rows.eventRow(it, parties), safeFields(it, names, rows)) }
        val transfer = transfers.found() ?: return missing(title, listOfNotNull(safe, transfers))
        // A transfer of nothing is not listed, and not detailed either: it plants a look-alike address.
        if (transfer.transfer.movesNothing()) return ActivityDetailUiState.Unavailable(title, MOVED_NOTHING)
        // Until the tokens are known, what moved cannot be said.
        if (tokens == null) return ActivityDetailUiState.Loading(title)
        return transferShown(title, transfer, parties, tokens, rows)
    }

    /** The event [key] names in this history, when it is read with it. */
    private fun <E : IndexedEvent> IndexedHistory<E>?.found(): E? = (this as? IndexedHistory.Read)?.events
        ?.firstOrNull { it.transactionHash == key.hash && it.logIndex == key.logIndex }

    /**
     * Why the event is not shown when no history read has it: gone once every one is checked from
     * its start (a reorganisation replaced its block), and loading otherwise: while the blocks it
     * may be in are being checked, and while a history cannot be read, which is also what one says
     * until its first coverage answers (the strip says when the light client is not running).
     */
    private fun missing(title: String, histories: List<IndexedHistory<*>>): ActivityDetailUiState =
        if (histories.all { it is IndexedHistory.Read && it.complete }) {
            ActivityDetailUiState.Unavailable(title, GONE)
        } else {
            ActivityDetailUiState.Loading(title)
        }

    private fun shownOf(
        title: String,
        row: ActivityRow,
        fields: List<DetailField>,
        note: String? = null,
        addToken: String? = null,
        opensStatus: String? = null,
    ) = ActivityDetailUiState.Shown(
        title = title,
        headline = row.title,
        about = row.about,
        status = row.status,
        simpleStatus = row.simpleStatus,
        state = row.state,
        fields = fields,
        // A row cautioned about its sender says why, here where the sender can be copied.
        note = note ?: SENDER_NOTE.takeIf { row.state == ActivityState.CAUTION },
        addToken = addToken,
        opensStatus = opensStatus,
    )

    /** Each party a sent transaction concerns, then what it moved, then where it is on chain. */
    private fun sentFields(entry: HistoryEntry, names: Map<Address, String>, rows: ActivityRows): List<DetailField> {
        val transfer = entry.transfer
        val fields = mutableListOf(addressOf("From", transfer.account, names))
        when (val action = transfer.action) {
            is SentAction.Transfer -> {
                fields += addressOf("To", action.to, names)
                fields += DetailField("Amount", rows.amount(action.amount, action.asset))
                fields.addContract("Token contract", action.asset, names)
            }
            is SentAction.TokenApproval -> {
                fields += DetailField("Allowance", rows.amount(action.amount, TransferAsset.Erc20Token(action.token)))
                fields.addContract("Token contract", TransferAsset.Erc20Token(action.token), names)
                fields += addressOf("Spender", action.spender, names)
            }
            is SentAction.Swap -> {
                fields += DetailField("Sold", rows.amount(action.amountIn, action.sell))
                fields += DetailField("Bought at least", rows.amount(action.minimumOut, action.buy))
                fields.addContract("Token sold", action.sell, names)
                fields.addContract("Token bought", action.buy, names)
                fields += addressOf("Router", action.router, names)
            }
            is SentAction.SafeApproval -> fields += safeCallFields(action.request, names)
            is SentAction.SafeExecution -> fields += safeCallFields(action.request, names)
            is SentAction.SafeDeployment -> {
                fields += addressOf("Safe", action.safe, names, action.label.ifBlank { null })
                action.owners.forEachIndexed { at, owner -> fields += addressOf("Owner ${at + 1}", owner, names) }
                fields += DetailField("Threshold", "${action.threshold} of ${rows.owners(action.owners.size.toLong())}")
                fields += DetailField("Safe version", action.version)
            }
            is SentAction.CowCancellation -> {
                fields += DetailField("CoW order", action.uid.hex, DetailKind.HASH)
                fields += addressOf("Settlement contract", action.settlement, names)
            }
            is SentAction.DappCall -> {
                fields += addressOf("To", action.to, names)
                fields += DetailField("Value", rows.amount(action.value, TransferAsset.Native))
                fields += DetailField("For the dApp", "${action.dapp} (stated by the dApp, not verified)")
            }
        }
        fields += DetailField("Transaction hash", transfer.hash, DetailKind.HASH)
        fields += DetailField("Nonce", transfer.nonce.toString())
        fields += DetailField("Sent", formatTime(transfer.sentAt))
        entry.outcome.blockNumberOrNull?.let { fields += DetailField("Block", grouped(it), DetailKind.BLOCK) }
        return fields
    }

    /** What an approval or execution of [request]'s Safe transaction concerns: the Safe, and the transaction. */
    private fun safeCallFields(request: SigningRequest, names: Map<Address, String>): List<DetailField> = listOf(
        addressOf("Safe", request.safe, names),
        DetailField("Safe nonce", request.nonce.toString()),
        DetailField("Safe transaction hash", "0x${request.hash.hex}", DetailKind.HASH),
        addressOf("Safe transaction to", request.tx.to, names),
    )

    /** The contract of [asset], when it is a token, named by its symbol. */
    private fun MutableList<DetailField>.addContract(label: String, asset: TransferAsset, names: Map<Address, String>) {
        val token = (asset as? TransferAsset.Erc20Token)?.token ?: return
        this += addressOf(label, token.address, names, displaySymbol(token.symbol))
    }

    /** What a Safe's event concerns, the Safe, then where it is on chain. */
    private fun safeFields(event: SafeChainEvent, names: Map<Address, String>, rows: ActivityRows): List<DetailField> {
        val fields = mutableListOf<DetailField>()
        when (val what = event.event) {
            is SafeEvent.SetUp -> {
                fields += addressOf("Set up by", what.initiator, names)
                what.owners.forEachIndexed { at, owner -> fields += addressOf("Owner ${at + 1}", owner, names) }
                fields += DetailField("Threshold", "${what.threshold} of ${rows.owners(what.owners.size.toLong())}")
            }
            is SafeEvent.Executed -> fields += DetailField("Safe transaction hash", what.safeTxHash, DetailKind.HASH)
            is SafeEvent.Received -> {
                fields += addressOf("From", what.from, names)
                fields += DetailField("Amount", rows.amount(what.amount, TransferAsset.Native))
            }
            is SafeEvent.Approved -> {
                fields += addressOf("Owner", what.owner, names)
                fields += DetailField("Safe transaction hash", what.safeTxHash, DetailKind.HASH)
            }
            is SafeEvent.OwnerAdded -> fields += addressOf("Owner", what.owner, names)
            is SafeEvent.OwnerRemoved -> fields += addressOf("Owner", what.owner, names)
            is SafeEvent.ThresholdChanged ->
                fields += DetailField("Threshold", "${rows.owners(what.threshold)} must sign")
            is SafeEvent.ModuleEnabled -> fields += addressOf("Module", what.module, names)
            is SafeEvent.ModuleDisabled -> fields += addressOf("Module", what.module, names)
            is SafeEvent.ModuleExecuted -> fields += addressOf("Module", what.module, names)
            is SafeEvent.GuardChanged -> fields += if (what.guard == Address.ZERO) {
                DetailField("Guard", "None")
            } else {
                addressOf("Guard", what.guard, names)
            }
            is SafeEvent.FallbackHandlerChanged -> fields += if (what.handler == Address.ZERO) {
                DetailField("Fallback handler", "None")
            } else {
                addressOf("Fallback handler", what.handler, names)
            }
            is SafeEvent.MessageSigned -> fields += DetailField("Message hash", what.messageHash, DetailKind.HASH)
        }
        fields += addressOf("Safe", address, names)
        return fields + loggedAt(event)
    }

    /**
     * A token's transfer: for a token the wallet shows, both sides and what moved; for any other
     * contract only the contract, as its row does, since it can log any transfer between any
     * addresses (a look-alike address planted for the user to copy). An ERC-20 one the wallet does
     * not list can be added; one it hides, or a built-in one its contract contradicts, is listed
     * already.
     */
    private fun transferShown(
        title: String,
        event: TokenTransferEvent,
        parties: Parties,
        tokens: Map<Address, KnownToken>,
        rows: ActivityRows,
    ): ActivityDetailUiState {
        val names = parties.names
        val transfer = event.transfer
        val listed = tokens[transfer.token]
        val known = listed?.takeIf { it.shown }
        val row = rows.transferRow(event, parties, tokens)
        val symbol = listed?.let { displaySymbol(it.token.symbol) }
        val contract = addressOf("Token contract", transfer.token, names, symbol)
        if (known == null) {
            val fields = listOf(contract) + loggedAt(event)
            if (transfer !is TokenTransfer.Fungible) return shownOf(title, row, fields, note = "$CLAIM_NOTE $NFT_NOTE")
            val add = Eip55.format(transfer.token).takeIf { listed == null }
                ?: return shownOf(title, row, fields, note = CLAIM_NOTE)
            // The row's status asks to open it to add it; here, Add token is on screen.
            val shown = shownOf(title, row.copy(status = UNLISTED), fields, note = "$CLAIM_NOTE $ADD_NOTE")
            return shown.copy(addToken = add)
        }
        val moved = when (transfer) {
            is TokenTransfer.Fungible ->
                DetailField(
                    "Amount",
                    rows.figure(transfer.amount, known.token.decimals, displaySymbol(known.token.symbol)),
                )
            is TokenTransfer.Nft -> DetailField("Token id", transfer.tokenId.toDecimal())
        }
        val sides = listOf(addressOf("From", transfer.from, names), addressOf("To", transfer.to, names))
        return shownOf(title, row, sides + moved + contract + loggedAt(event))
    }

    /**
     * Where an event is on chain: the transaction that logged it, when its block was produced where
     * the engine reported it from the block's verified header, and its block.
     */
    private fun loggedAt(event: IndexedEvent): List<DetailField> = listOfNotNull(
        DetailField("Transaction hash", event.transactionHash, DetailKind.HASH),
        event.blockTimestamp?.let { DetailField("Time", formatTime(it)) },
        DetailField("Block", grouped(event.blockNumber), DetailKind.BLOCK),
    )

    /**
     * [of] as a field: its EIP-55 address, named as the wallet names it, else as [name] says (a
     * token's symbol, a Safe's planned name), else the zero address as such.
     */
    private fun addressOf(label: String, of: Address, names: Map<Address, String>, name: String? = null) = DetailField(
        label = label,
        value = Eip55.format(of),
        kind = DetailKind.ADDRESS,
        name = names[of] ?: name ?: ZERO_NAME.takeIf { of == Address.ZERO },
    )

    private companion object {
        const val UNREADABLE = "The history of your transactions cannot be read right now. Serotine keeps trying."
        const val NOT_SENT_HERE = "This transaction is not in this account's history."
        const val ACCOUNTS_UNREADABLE = "The wallet's accounts cannot be read right now."
        const val NOT_IN_WALLET = "This account is not in the wallet any more, so its history on chain is not read."
        const val GONE = "This event is not in the account's verified history any more: a reorganisation of the " +
            "chain may have replaced its block."
        const val MOVED_NOTHING = "This transfer moved nothing, so it is not shown: anyone can have a token log " +
            "one from any address, to plant a look-alike address for you to copy."
        const val UNLISTED = "The claim of a contract the wallet does not list"
        const val SENDER_NOTE = "You have not sent to the sender's address from this device, and it is not one of " +
            "your accounts. Anyone can send you a little from an address made to look like one you use, so that you " +
            "copy it from your history later: compare every character before you send anything to it."
        const val ZERO_NAME = "The zero address"
        const val CLAIM_NOTE = "A contract the wallet does not show can log any transfer it likes, between any " +
            "addresses, so only the contract is named here: an address it names could be a look-alike planted " +
            "for you to copy."
        const val ADD_NOTE = "Add it as a token to see what moved: its symbol, name and decimals are read from " +
            "the contract first."
        const val NFT_NOTE = "It logged an ERC-721 transfer, and the wallet adds ERC-20 tokens only."
    }
}
