package eth.serotine.ui.activity

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PinnedFallbackHandlers
import eth.serotine.domain.model.SafeEvent
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.usecase.BuiltInCheck
import eth.serotine.domain.usecase.KnownToken
import eth.serotine.domain.usecase.SafeChainEvent
import eth.serotine.domain.usecase.TokenTransferEvent
import eth.serotine.ui.components.AMOUNT_MASK
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.FALLBACK_HANDLER_REMOVED
import eth.serotine.ui.components.GUARD_REMOVED_WARNING
import eth.serotine.ui.components.GUARD_SET_WARNING
import eth.serotine.ui.components.MODULE_DISABLED_WARNING
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.exactAmount
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.shortAddress

/**
 * What the account at [address] is told of each transaction and event of its history, in the
 * words the Activity screen lists it with and its detail screen heads it with (docs/wallet-ux.md,
 * screens 25 and 26): one place, so the two never say it differently. [network] names its
 * currency, [formatTime] dates a transaction a peer took. With [hideAmounts] every amount it says
 * reads [AMOUNT_MASK] beside its unit (docs/wallet-ux.md, *Hiding amounts*).
 */
internal class ActivityRows(
    private val address: Address,
    private val network: Network,
    private val formatTime: (Long) -> String,
    private val hideAmounts: Boolean = false,
) {

    /**
     * A token's [event] as a row: what moved, to or from whom, in which block. Only a token the
     * wallet shows has its transfer said as one: an unlisted contract can log any transfer it likes,
     * between any addresses, so its row says that the contract logged one, names the contract and
     * not the other side, and is marked as unconfirmed. A token the user added whose symbol is a
     * built-in token's says it is another contract, as every screen does. One received from a
     * sender the user does not deal with ([Parties.familiar]) is cautioned: the sender may be a
     * look-alike of an address the user uses, planted for them to copy.
     */
    fun transferRow(
        event: TokenTransferEvent,
        parties: Parties,
        tokens: Map<Address, KnownToken>,
    ): ActivityRow {
        val names = parties.names
        val transfer = event.transfer
        val contract = shortAddress(Eip55.format(transfer.token))
        val listed = tokens[transfer.token]
        val known = listed?.takeIf { it.shown }
        val direction = when {
            transfer.from == address && transfer.to == address -> Direction.ITSELF
            transfer.to == address -> Direction.IN
            else -> Direction.OUT
        }
        val row = ActivityRow(
            key = "${event.transactionHash}:${event.logIndex}",
            title = "",
            detail = "",
            about = Eip55.format(transfer.token),
            time = "Block ${grouped(event.blockNumber)}",
            simpleTime = null,
            status = TOKEN_ON_CHAIN,
            state = ActivityState.DONE,
            opens = ActivityKey(event.transactionHash, event.logIndex),
        )
        if (known == null) {
            val title = when (direction) {
                Direction.IN -> "An unlisted token logged a transfer to it"
                Direction.OUT -> "An unlisted token logged a transfer from it"
                Direction.ITSELF -> "An unlisted token logged a transfer to itself"
            }
            // What would name it, for the reason it is not named.
            val status = when {
                listed == null -> UNLISTED
                listed.check is BuiltInCheck.Disagrees -> CONTRADICTED
                else -> HIDDEN
            }
            return row.copy(
                title = title,
                detail = "Contract $contract",
                status = status,
                state = ActivityState.UNKNOWN,
            )
        }
        val symbol = displaySymbol(known.token.symbol)
        val what = when (transfer) {
            is TokenTransfer.Fungible -> figure(transfer.amount, known.token.decimals, symbol)
            is TokenTransfer.Nft -> "$symbol NFT #${tokenId(transfer.tokenId)}"
        }
        val (title, other, detail) = when (direction) {
            Direction.ITSELF -> Triple("Moved $what to itself", transfer.token, "Token $contract")
            Direction.IN -> Triple("Received $what", transfer.from, "From ${named(transfer.from, names)}")
            Direction.OUT -> Triple("Sent $what", transfer.to, "To ${named(transfer.to, names)}")
        }
        return when {
            known.sharesBuiltInSymbol -> row.copy(
                title = title,
                detail = "$detail · token $contract",
                about = Eip55.format(other),
                status = "Not the built-in $symbol: another contract",
                state = ActivityState.WARNING,
            )
            direction == Direction.IN && !parties.familiar(transfer.from) -> row.copy(
                title = title,
                detail = detail,
                about = Eip55.format(other),
                status = UNFAMILIAR_SENDER,
                state = ActivityState.CAUTION,
            )
            else -> row.copy(title = title, detail = detail, about = Eip55.format(other))
        }
    }

    /** Which way a token moved, as the account sees it. */
    private enum class Direction { IN, OUT, ITSELF }

    /** An NFT's id as screens show it: whole when short, its ends when long. */
    fun tokenId(id: UInt256): String =
        id.toDecimal().let { if (it.length > MAX_ID_DIGITS) it.take(6) + "…" + it.takeLast(4) else it }

    /** [other] as screens name it: the wallet's name for it and its short address, or the short address. */
    fun named(other: Address, names: Map<Address, String>): String {
        val short = shortAddress(Eip55.format(other))
        return names[other]?.let { "$it · $short" } ?: short
    }

    /** A Safe's [event] as a row; ETH received from a sender the user does not deal with is cautioned. */
    fun eventRow(event: SafeChainEvent, parties: Parties): ActivityRow {
        fun named(other: Address): String = named(other, parties.names)
        val (title, other, detail) = when (val what = event.event) {
            is SafeEvent.SetUp ->
                Triple("Set up", address, "${what.threshold} of ${owners(what.owners.size.toLong())} must sign")
            is SafeEvent.Executed -> Triple(
                if (what.succeeded) "Executed a Safe transaction" else "A Safe transaction failed",
                address,
                "Safe transaction ${shortHash(what.safeTxHash)}",
            )
            is SafeEvent.Received -> Triple(
                "Received ${amount(what.amount, TransferAsset.Native)}",
                what.from,
                "From ${named(what.from)}",
            )
            is SafeEvent.Approved -> Triple("Approved a Safe transaction", what.owner, "By ${named(what.owner)}")
            is SafeEvent.OwnerAdded -> Triple("Owner added", what.owner, named(what.owner))
            is SafeEvent.OwnerRemoved -> Triple("Owner removed", what.owner, named(what.owner))
            is SafeEvent.ThresholdChanged ->
                Triple("Threshold changed", address, "${owners(what.threshold)} must sign now")
            is SafeEvent.ModuleEnabled -> Triple("Module enabled", what.module, named(what.module))
            is SafeEvent.ModuleDisabled -> Triple("Module disabled", what.module, named(what.module))
            is SafeEvent.GuardChanged -> if (what.guard == Address.ZERO) {
                Triple("Guard removed", address, "No guard checks its transactions now")
            } else {
                Triple("Guard set", what.guard, named(what.guard))
            }
            is SafeEvent.FallbackHandlerChanged -> if (what.handler == Address.ZERO) {
                Triple("Fallback handler removed", address, "No fallback handler now")
            } else {
                Triple("Fallback handler set", what.handler, named(what.handler))
            }
            is SafeEvent.ModuleExecuted -> Triple(
                if (what.succeeded) "A module ran a transaction" else "A module's transaction failed",
                what.module,
                "Module ${named(what.module)}",
            )
            is SafeEvent.MessageSigned -> Triple("Signed a message", address, "Message ${shortHash(what.messageHash)}")
        }
        val received = event.event as? SafeEvent.Received
        val (status, state) = if (received != null && !parties.familiar(received.from)) {
            UNFAMILIAR_SENDER to ActivityState.CAUTION
        } else {
            statusOf(event.event)
        }
        return ActivityRow(
            key = "${event.transactionHash}:${event.logIndex}",
            title = title,
            detail = detail,
            about = Eip55.format(other),
            time = "Block ${grouped(event.blockNumber)}",
            simpleTime = null,
            status = status,
            state = state,
            opens = ActivityKey(event.transactionHash, event.logIndex),
        )
    }

    /**
     * How an event's row is marked: failed for a call that reverted; a warning for what can move
     * the Safe's funds without its owners (CLAUDE.md, *Trust*): a module enabled, or a fallback
     * handler other than the pinned one; and for every other change that weakens the Safe, with the
     * warning its Safe transaction is signed with (docs/wallet-ux.md, 17): a module disabled, a
     * guard set or removed, a fallback handler removed.
     */
    fun statusOf(event: SafeEvent): Pair<String, ActivityState> = when {
        (event as? SafeEvent.Executed)?.succeeded == false ||
            (event as? SafeEvent.ModuleExecuted)?.succeeded == false -> FAILED_ON_CHAIN to ActivityState.FAILED
        event is SafeEvent.ModuleEnabled -> MODULE_WARNING to ActivityState.WARNING
        event is SafeEvent.ModuleDisabled -> MODULE_DISABLED_WARNING to ActivityState.WARNING
        event is SafeEvent.GuardChanged && event.guard == Address.ZERO -> GUARD_REMOVED_WARNING to ActivityState.WARNING
        event is SafeEvent.GuardChanged -> GUARD_SET_WARNING to ActivityState.WARNING
        // Removing it is signed here, with a warning (owner ruling, 2026-10-20, #106): the row warns alike.
        event is SafeEvent.FallbackHandlerChanged && event.handler == Address.ZERO ->
            FALLBACK_HANDLER_REMOVED to ActivityState.WARNING
        event is SafeEvent.FallbackHandlerChanged && !PinnedFallbackHandlers.allowed(network, event.handler) ->
            HANDLER_WARNING to ActivityState.WARNING
        else -> ON_CHAIN to ActivityState.DONE
    }

    /** [count] owners, as a sentence says it: "1 owner", "2 owners". */
    fun owners(count: Long): String = if (count == 1L) "1 owner" else "$count owners"

    /** A 32-byte hash as screens shorten it, as they do an address: "0xab12…cd34". */
    fun shortHash(hash: String): String = shortAddress(hash)


    /** A transaction sent from this device ([entry]) as a row; [names] are the wallet's names for addresses. */
    fun row(entry: HistoryEntry, names: Map<Address, String>): ActivityRow {
        val transfer = entry.transfer
        // Its other side: whom a transfer went to, the Safe a call was for, or, seen from that Safe,
        // the account that sent it.
        val (title, other, detail) = when (val action = transfer.action) {
            is SentAction.Transfer -> Triple("Sent ${amount(action.amount, action.asset)}", action.to, "To")
            // A zero approval takes the allowance back, before the whole amount is approved. The spender
            // is Uniswap's router or CoW's vault relayer, which the record does not tell apart.
            is SentAction.TokenApproval -> if (action.amount.isZero) {
                Triple("Allowance reset for a swap", action.spender, "Spender")
            } else {
                val approved = amount(action.amount, TransferAsset.Erc20Token(action.token))
                Triple("Approved $approved", action.spender, "Spender")
            }
            is SentAction.Swap -> {
                val (symbol, decimals) = shown(action.buy)
                val title = "Swapped ${amount(action.amountIn, action.sell)} for at least " +
                    figure(action.minimumOut, decimals, symbol)
                Triple(title, action.router, "On Uniswap ·")
            }
            is SentAction.SafeApproval -> safeCall("Approved", transfer.account, action.request)
            is SentAction.SafeExecution -> safeCall("Executed", transfer.account, action.request)
            is SentAction.SafeDeployment -> if (transfer.account == address) {
                Triple("Deployed a Safe", action.safe, "Safe")
            } else {
                Triple("Deployed", transfer.account, "Paid by")
            }
            is SentAction.CowCancellation -> Triple("Cancelled a CoW order on chain", action.settlement, "On CoW ·")
            // The dApp's name is its own claim: said as such.
            is SentAction.DappCall -> Triple("Sent for a dApp stating it is “${action.dapp}”", action.to, "To")
        }
        val (status, state) = statusOf(entry)
        return ActivityRow(
            key = transfer.hash,
            opens = ActivityKey(transfer.hash),
            title = title,
            detail = "$detail ${named(other, names)}",
            about = Eip55.format(other),
            time = formatTime(transfer.sentAt),
            status = status,
            state = state,
            simpleStatus = simpleStatusOf(entry),
        )
    }

    /** [units] of [asset] as screens say it: "0.25 ETH". */
    fun amount(units: UInt256, asset: TransferAsset): String {
        val (symbol, decimals) = shown(asset)
        return figure(units, decimals, symbol)
    }

    /** [units] at [decimals] in [symbol]: "0.25 ETH", or "•••• ETH" while amounts are hidden. */
    fun figure(units: UInt256, decimals: Int, symbol: String): String =
        if (hideAmounts) "$AMOUNT_MASK $symbol" else exactAmount(units, decimals, symbol)

    /**
     * A CoW order signed here as a row: what it sells and buys, and where it stands as the chain
     * proves it (docs/swaps.md, *Proving an order's end*), dated when it was signed. A tap opens the
     * order's status.
     */
    fun orderRow(order: CowOrderRecord): ActivityRow {
        val sells = amount(order.order.sellAmount, order.sell)
        val simple = when (val end = order.end) {
            is CowOrderEnd.Filled -> "Filled"
            is CowOrderEnd.Unfilled -> if (end.cancelled) "Cancelled" else "Not filled: it expired"
            else -> null
        }
        val (title, status, state) = when (val end = order.end) {
            null -> Triple(
                "CoW order: $sells for at least ${amount(order.order.buyAmount, order.buy)}",
                "May fill until ${formatTime(order.order.validTo)}",
                ActivityState.WAITING,
            )
            is CowOrderEnd.Filled -> Triple(
                "Swapped $sells for ${amount(end.trade.buyAmount, order.buy)} on CoW",
                "Filled in block ${grouped(end.block)}",
                ActivityState.DONE,
            )
            is CowOrderEnd.Unfilled -> {
                val block = grouped(end.endBlock)
                if (end.cancelled) {
                    Triple("CoW order cancelled: $sells", "Cancelled in block $block", ActivityState.DONE)
                } else {
                    Triple("CoW order not filled: $sells", "Not filled by block $block", ActivityState.DONE)
                }
            }
            CowOrderEnd.NotProvable -> Triple("CoW order: $sells", "Its end cannot be proven", ActivityState.UNKNOWN)
        }
        return ActivityRow(
            key = order.uid.hex,
            title = title,
            detail = "On CoW · ${shortAddress(Eip55.format(order.settlement))}",
            about = Eip55.format(order.settlement),
            time = formatTime(order.signedAt),
            status = status,
            state = state,
            opens = ActivityKey(order.uid.hex, cowOrder = true),
            simpleStatus = simple,
        )
    }

    /** [asset]'s symbol as screens show it, and its decimals. */
    fun shown(asset: TransferAsset): Pair<String, Int> = when (asset) {
        TransferAsset.Native -> network.nativeSymbol to NATIVE_DECIMALS
        is TransferAsset.Erc20Token -> displaySymbol(asset.token.symbol) to asset.token.decimals
    }

    /**
     * An approval or execution ([verb]) of [request]'s Safe transaction, sent by [sender]: its title
     * and other side as the account at [address] sees it, the Safe or the sender.
     */
    private fun safeCall(verb: String, sender: Address, request: SigningRequest): Triple<String, Address, String> =
        if (sender == address) {
            Triple("$verb a Safe transaction", request.safe, "Safe nonce ${request.nonce} ·")
        } else {
            Triple("$verb transaction ${request.nonce}", sender, "By")
        }

    /** What simple mode says instead of a sent transaction's block: null where [statusOf] names none. */
    private fun simpleStatusOf(entry: HistoryEntry): String? = when (val outcome = entry.outcome) {
        is SentOutcome.Finalized -> if (outcome.succeeded) "Final" else "Failed: its fee was paid, nothing else done"
        is SentOutcome.Included -> if (outcome.succeeded) "Confirmed" else "Failed: its fee was paid, nothing else done"
        else -> null
    }

    /** How a sent transaction's row is marked: how it ended as this device saw it. */
    fun statusOf(entry: HistoryEntry): Pair<String, ActivityState> = when (val outcome = entry.outcome) {
        is SentOutcome.Finalized -> if (outcome.succeeded) {
            "Finalized in block ${grouped(outcome.blockNumber)}" to ActivityState.DONE
        } else {
            "Reverted in block ${grouped(outcome.blockNumber)}" to ActivityState.FAILED
        }
        is SentOutcome.Included -> if (outcome.succeeded) {
            "In block ${grouped(outcome.blockNumber)}" to ActivityState.DONE
        } else {
            "Reverted in block ${grouped(outcome.blockNumber)}" to ActivityState.FAILED
        }
        SentOutcome.NonceUsed ->
            "Mined while the app was not watching, unless another transaction took its nonce" to
                ActivityState.UNKNOWN
        SentOutcome.NotSeen -> if (entry.transfer.maybeSent) {
            "No peer confirmed its broadcast" to ActivityState.UNCONFIRMED
        } else {
            "No block seen to include it yet" to ActivityState.WAITING
        }
    }

    private companion object {
        const val NATIVE_DECIMALS = 18
        const val MAX_ID_DIGITS = 12
        const val ON_CHAIN = "On chain, from the Safe's verified logs"
        const val TOKEN_ON_CHAIN = "On chain, from the token's verified logs"
        const val UNLISTED =
            "The claim of a contract the wallet does not list: open it to add it as a token and see what moved"
        const val HIDDEN = "The claim of a token you hid: show it again in Settings → Tokens to see what moved"
        const val CONTRADICTED = "The claim of a built-in token whose contract says otherwise: what moved is not shown"
        const val FAILED_ON_CHAIN = "Failed on chain: its call reverted"
        const val MODULE_WARNING = "A module can move this Safe's funds without its owners"
        const val UNFAMILIAR_SENDER = "From an address you have not sent to: compare every character before you copy it"
        const val HANDLER_WARNING = "Not the pinned fallback handler: it answers signature checks for this Safe"
    }
}
