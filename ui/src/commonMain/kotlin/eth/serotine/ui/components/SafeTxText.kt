package eth.serotine.ui.components

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PinnedFallbackHandlers
import eth.serotine.domain.model.RouterSwap
import eth.serotine.domain.model.SafeSettingsChange
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SafeTxGasTerms
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.BuiltInCheck
import eth.serotine.domain.usecase.CallRefusal
import eth.serotine.domain.usecase.KnownTokenList
import eth.serotine.domain.usecase.QuoteFigure
import eth.serotine.domain.usecase.SafeSwapCheck
import eth.serotine.domain.usecase.SafeTxRefusal
import eth.serotine.domain.usecase.SwapNow

/**
 * What a Safe transaction does, as decoded here from its raw fields (docs/wallet-ux.md, screen
 * 17), display-ready: [title] ("Send 40 USDC"), the [addresses] it names, each with what it is,
 * [notes] on what else it does ("The threshold becomes 2", its call data), and [warning], what to
 * know before anyone signs it (a delegate call, a module that can move the funds), or null. [icon]
 * and [symbol] are the asset's for a transfer of a known one, null otherwise. Never taken from
 * anything that came with the request: a request carries no description.
 */
data class SafeTxActionItem(
    val title: String,
    val addresses: List<ActionAddress> = emptyList(),
    val notes: List<String> = emptyList(),
    val warning: String? = null,
    val icon: String? = null,
    val symbol: String? = null,
)

/**
 * Where a Safe's approval or swap stands as the chain is now (docs/swaps.md, *A Safe's swap*),
 * display-ready: [lines] (whose router it is, what it returns now against its minimum, its
 * deadline) and [warning], what to know before anyone signs or executes it, or null.
 * [proposeAgain] when Uniswap's swap is worth proposing again in its place: its minimum has
 * fallen behind the market, or its deadline has passed (owner ruling, 2026-10-19);
 * [proposeAgainReason] why that cannot be done from here (no owner held here), the offer then
 * greyed out (owner ruling, 2026-10-18, #55), or null when it can. [simpleLines] are [lines] with
 * no block, for simple mode (docs/simple-ui.md); null where they are [lines] themselves.
 */
data class SafeSwapItem(
    val lines: List<String>,
    val warning: String? = null,
    val proposeAgain: Boolean = false,
    val proposeAgainReason: String? = null,
    val simpleLines: List<String>? = null,
)

/** An address a Safe transaction names: [what] it is ("To", "New owner") and its EIP-55 [address]. */
data class ActionAddress(val what: String, val address: String)

/**
 * The gas and refund terms a Safe transaction carries beside its call (docs/wallet-ux.md, screen
 * 17), display-ready: [lines] ("safeTxGas: 50,000") and the [addresses] they name (the gas token,
 * the refund receiver).
 */
data class GasTermsItem(val lines: List<String>, val addresses: List<ActionAddress>)

/**
 * A token a Safe transaction's transfer is shown in the units of: [token] as the wallet knows it,
 * and whether it is a token the user added whose symbol is a built-in token's ([sharesBuiltInSymbol]),
 * which it is not: a contract may call itself anything.
 */
internal data class DecodingToken(val token: Token, val sharesBuiltInSymbol: Boolean = false)

/**
 * [action], a transaction of a Safe at [nonce], as screens show it: a transfer's amount in the
 * token's units where the wallet knows the token ([tokens], the known ones by address), marked when
 * the token only takes a built-in token's symbol; its smallest units where it does not; and every
 * address it names in full.
 */
internal fun safeTxActionItem(
    action: SafeTxAction,
    nonce: Long,
    tokens: List<DecodingToken>,
    network: Network,
): SafeTxActionItem = when (action) {
    is SafeTxAction.NativeTransfer -> SafeTxActionItem(
        title = "Send ${action.amount.formatUnits(NATIVE_DECIMALS)} ${network.nativeSymbol}",
        addresses = listOf(ActionAddress("To", Eip55.format(action.to))),
        icon = network.nativeIcon,
        symbol = network.nativeSymbol,
    )
    is SafeTxAction.TokenTransfer -> {
        val amount = tokenAmount(action.token, action.amount, tokens)
        SafeTxActionItem(
            title = "Send ${amount.text}",
            addresses = listOf(
                ActionAddress("To", Eip55.format(action.to)),
                ActionAddress("Token contract", Eip55.format(action.token)),
            ),
            notes = listOfNotNull(amount.unknownNote),
            warning = amount.warning,
            icon = amount.icon,
            symbol = amount.symbol,
        )
    }
    is SafeTxAction.TokenApproval -> {
        val amount = tokenAmount(action.token, action.amount, tokens)
        SafeTxActionItem(
            title = "Allow a contract to take ${amount.text}",
            addresses = listOf(
                ActionAddress("Spender", Eip55.format(action.spender)),
                ActionAddress("Token contract", Eip55.format(action.token)),
            ),
            notes = listOfNotNull(
                "It moves nothing on its own: the spender may take up to this much of the Safe's tokens later.",
                amount.unknownNote,
            ),
            warning = amount.warning,
            icon = amount.icon,
            symbol = amount.symbol,
        )
    }
    is SafeTxAction.Swap -> {
        val swap = action.swap
        val sold = if (swap.sellsEther) nativeAmount(swap.amountIn, network) else
            tokenAmount(swap.route.tokenIn, swap.amountIn, tokens)
        val bought = if (swap.buysEther) nativeAmount(swap.minimumOut, network) else
            tokenAmount(swap.route.tokenOut, swap.minimumOut, tokens)
        SafeTxActionItem(
            title = "Swap ${sold.text} for at least ${bought.text}",
            addresses = listOfNotNull(
                ActionAddress("Router", Eip55.format(swap.router)),
                ActionAddress("Token sold", Eip55.format(swap.route.tokenIn)).takeUnless { swap.sellsEther },
                ActionAddress("Token bought", Eip55.format(swap.route.tokenOut)).takeUnless { swap.buysEther },
            ),
            notes = listOfNotNull(
                if (swap.route.isDirect) {
                    "Through one pool at ${feeTierText(swap.route.fees.single().pips)}."
                } else {
                    "Through ${tokenSymbol(swap.route.tokens[1], tokens)}: two pools, at " +
                        swap.route.fees.joinToString(" and ") { feeTierText(it.pips) } + "."
                },
                "Paid to the Safe.",
                "Whether the router is Uniswap's is checked against the chain once the transaction is opened: " +
                    "until then, what it does with the call is up to its code.",
                sold.unknownNote ?: bought.unknownNote,
            ),
            warning = listOfNotNull(sold.warning, bought.warning).takeIf { it.isNotEmpty() }?.joinToString(". "),
            icon = sold.icon,
            symbol = sold.symbol,
        )
    }
    is SafeTxAction.SettingsChange -> settingsChange(action.change)
    SafeTxAction.Rejection -> SafeTxActionItem(
        title = "Reject: use up nonce $nonce",
        notes = listOf(
            "Nothing is called and nothing moves. Executed, it uses up nonce $nonce, so no other " +
                "transaction at that nonce can ever run.",
        ),
    )
    is SafeTxAction.ContractCall -> SafeTxActionItem(
        title = "Call a contract",
        addresses = listOf(ActionAddress("Contract", Eip55.format(action.to))),
        notes = listOfNotNull(
            action.value.takeUnless { it.isZero }
                ?.let { "With ${it.formatUnits(NATIVE_DECIMALS)} ${network.nativeSymbol}" },
            callData(action.selector, action.dataSize),
            "Not decoded here: what it does is up to the contract's code.",
        ),
    )
    is SafeTxAction.DelegateCall -> SafeTxActionItem(
        title = "Delegate call",
        addresses = listOf(ActionAddress("Code run as the Safe", Eip55.format(action.to))),
        notes = listOf(callData(action.selector, action.dataSize)),
        warning = "A delegate call: the Safe would run this contract's code as itself, with its storage, " +
            "owners and funds, and the code can change any of them. Serotine shows it and decodes it no " +
            "further, and does not sign, approve or execute it.",
    )
}

private fun settingsChange(change: SafeSettingsChange): SafeTxActionItem = when (change) {
    is SafeSettingsChange.OwnerAdded -> SafeTxActionItem(
        title = "Add an owner",
        addresses = listOf(ActionAddress("New owner", Eip55.format(change.owner))),
        notes = listOf(threshold(change.threshold)),
    )
    is SafeSettingsChange.OwnerRemoved -> SafeTxActionItem(
        title = "Remove an owner",
        addresses = listOf(ActionAddress("Owner removed", Eip55.format(change.owner))),
        notes = listOf(threshold(change.threshold)),
    )
    is SafeSettingsChange.OwnerSwapped -> SafeTxActionItem(
        title = "Replace an owner",
        addresses = listOf(
            ActionAddress("Owner removed", Eip55.format(change.old)),
            ActionAddress("New owner", Eip55.format(change.new)),
        ),
    )
    is SafeSettingsChange.ThresholdChanged ->
        SafeTxActionItem(title = "Change the threshold", notes = listOf(threshold(change.threshold)))
    is SafeSettingsChange.ModuleEnabled -> SafeTxActionItem(
        title = "Enable a module",
        addresses = listOf(ActionAddress("Module", Eip55.format(change.module))),
        warning = "A module can move the Safe's funds without the owners' signatures.",
    )
    is SafeSettingsChange.ModuleDisabled -> SafeTxActionItem(
        title = "Disable a module",
        addresses = listOf(ActionAddress("Module", Eip55.format(change.module))),
        warning = MODULE_DISABLED_WARNING,
    )
    is SafeSettingsChange.GuardSet -> change.guard?.let { guard ->
        SafeTxActionItem(
            title = "Set a guard",
            addresses = listOf(ActionAddress("Guard", Eip55.format(guard))),
            warning = GUARD_SET_WARNING,
        )
    } ?: SafeTxActionItem(
        title = "Remove the guard",
        warning = GUARD_REMOVED_WARNING,
    )
    is SafeSettingsChange.FallbackHandlerSet -> change.handler?.let { handler ->
        SafeTxActionItem(
            title = "Set the fallback handler",
            addresses = listOf(ActionAddress("Fallback handler", Eip55.format(handler))),
            notes = listOfNotNull(
                "It answers the calls the Safe has no function for.",
                "The canonical CompatibilityFallbackHandler 1.4.1, as pinned here."
                    .takeIf { handler == PinnedFallbackHandlers.COMPATIBILITY_1_4_1 },
            ),
            warning = "The fallback handler answers the signature checks (EIP-1271) other contracts make for this " +
                "Safe: one that accepts every signature lets anyone spend what the Safe has approved (Permit2, " +
                "permits, orders), without its owners.",
        )
    } ?: SafeTxActionItem(
        title = "Remove the fallback handler",
        warning = FALLBACK_HANDLER_REMOVED,
    )
}

private fun threshold(threshold: Long) = "The threshold becomes $threshold"

/** The warning on a module disabled: what it did for the Safe, and stops. */
internal const val MODULE_DISABLED_WARNING =
    "Whatever this module does for the Safe stops: a spending limit, a delay, a recovery path."

/** The warning on a guard set: what it checks, and that it can lock the Safe. */
internal const val GUARD_SET_WARNING =
    "A guard checks every transaction the owners sign: one that refuses them all locks the Safe."

/** The warning on a guard removed: what it enforced, and stops. */
internal const val GUARD_REMOVED_WARNING =
    "Whatever the guard enforces on the Safe's transactions stops: a limit, an allowlist, a delay."

/** The warning on a fallback handler removed: what the handler answered for the Safe, and stops. */
internal const val FALLBACK_HANDLER_REMOVED =
    "Whatever the fallback handler answers for the Safe stops: tokens sent to it with a safe transfer " +
        "(ERC-721, ERC-1155) are refused, and the signature checks (EIP-1271) other contracts make for it fail, " +
        "so orders and permits signed for the Safe no longer work."

/**
 * [action], when it is an approval or a swap, as [check] found it (null: still being checked),
 * its amounts in [tokens]' units and its deadline dated by [formatTime]; null for anything else.
 */
internal fun safeSwapItem(
    action: SafeTxAction,
    check: SafeSwapCheck?,
    tokens: List<DecodingToken>,
    network: Network,
    formatTime: (Long) -> String,
): SafeSwapItem? = when (action) {
    is SafeTxAction.TokenApproval -> when (check) {
        null -> SafeSwapItem(listOf("Checking the spender with the light client…"))
        SafeSwapCheck.ApprovalOfRouter ->
            SafeSwapItem(listOf("The spender is Uniswap's SwapRouter02, its code proven by the light client."))
        is SafeSwapCheck.NotUniswap -> SafeSwapItem(emptyList(), warning = NOT_UNISWAP_SPENDER)
        else -> SafeSwapItem(
            emptyList(),
            warning = "Whether the spender is Uniswap's could not be verified this time: until it is, treat it as " +
                "any contract.",
        )
    }
    is SafeTxAction.Swap -> swapItem(action.swap, check, tokens, network, formatTime)
    else -> null
}

private fun swapItem(
    swap: RouterSwap,
    check: SafeSwapCheck?,
    tokens: List<DecodingToken>,
    network: Network,
    formatTime: (Long) -> String,
): SafeSwapItem {
    // What the router does with its deadline is said only of Uniswap's: another's code may ignore it.
    val signed = "Signed with a deadline of ${formatTime(swap.deadline)}."
    val checked = when (check) {
        null -> return SafeSwapItem(listOf("Checking the swap with the light client…", signed))
        is SafeSwapCheck.NotUniswap -> return SafeSwapItem(listOf(signed), warning = NOT_UNISWAP_ROUTER)
        is SafeSwapCheck.SwapOnUniswap -> check
        else -> return SafeSwapItem(
            listOf(signed),
            warning = "Whether the router is Uniswap's could not be verified this time: until it is, treat it as " +
                "any contract's call.",
        )
    }
    val bought = { amount: UInt256 ->
        if (swap.buysEther) {
            nativeAmount(amount, network).text
        } else {
            tokenAmount(swap.route.tokenOut, amount, tokens).text
        }
    }
    val now = when (val quote = checked.now) {
        is SwapNow.Quoted -> "Returns ${bought(quote.amountOut)} now, by a verified QuoterV2 quote" +
            (quote.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty()) +
            if (checked.meetsMinimum == true) {
                ": it meets its minimum."
            } else {
                ": below its minimum, so executed now it reverts."
            }
        SwapNow.NoQuote -> "No pool on its route takes this amount now: executed now, it reverts."
        is SwapNow.Unavailable -> "What it returns now could not be verified this time."
    }
    val warnings = listOfNotNull(
        if (checked.expired == true) {
            "Its deadline has passed: the router refuses it, so it can never run as signed. Replace it at its " +
                "nonce: propose the swap again, or reject it."
        } else {
            null
        },
        checked.leftOpen?.takeIf { checked.expired != true }?.let { open ->
            "Its minimum has fallen behind the market: it returns ${bought(open)} more than its minimum now, more " +
                "than the default ${SlippageTolerance.DEFAULT} tolerance would leave, and a sandwich could take all " +
                "of it. Propose it again with a fresh minimum, or execute it as signed."
        },
    )
    val simpleNow = (checked.now as? SwapNow.Quoted)?.let { quote ->
        "Returns ${bought(quote.amountOut)} now, as checked on chain" + if (checked.meetsMinimum == true) {
            ": it meets its minimum."
        } else {
            ": below its minimum, so executed now it reverts."
        }
    } ?: now
    val router = "Through Uniswap's SwapRouter02, its code proven by the light client."
    val deadline = "Refused by the router after ${formatTime(swap.deadline)}."
    return SafeSwapItem(
        lines = listOf(router, now, deadline),
        simpleLines = listOf(router, simpleNow, deadline).takeIf { simpleNow != now },
        warning = warnings.takeIf { it.isNotEmpty() }?.joinToString(" "),
        proposeAgain = checked.expired == true || checked.leftOpen != null,
    )
}

private const val NOT_UNISWAP_SPENDER = "The spender is not Uniswap's pinned SwapRouter02: what it does with the " +
    "Safe's tokens is not known here."

private const val NOT_UNISWAP_ROUTER = "The router is not Uniswap's pinned SwapRouter02: what it does with the " +
    "Safe's tokens is not known here."

/**
 * An amount as a Safe transaction's text says it: [text] ("100 USDC", or "100,000,000 units of a
 * token" where the token is not known here), with the [icon] and [symbol] of a known one, a
 * [warning] when it only takes a built-in token's symbol, and [unknownNote] when it is not known.
 */
private class AmountText(
    val text: String,
    val icon: String? = null,
    val symbol: String? = null,
    val warning: String? = null,
    val unknownNote: String? = null,
)

private fun nativeAmount(amount: UInt256, network: Network) = AmountText(
    "${amount.formatUnits(NATIVE_DECIMALS)} ${network.nativeSymbol}",
    network.nativeIcon,
    network.nativeSymbol,
)

private fun tokenAmount(address: Address, amount: UInt256, tokens: List<DecodingToken>): AmountText {
    val known = tokens.firstOrNull { it.token.address == address }
        ?: return AmountText(
            "${grouped(amount)} units of a token",
            unknownNote = "Serotine does not know the token at this contract: the amount is in its smallest " +
                "units, and how many decimals it has is not known here.",
        )
    val symbol = displaySymbol(known.token.symbol)
    return AmountText(
        "${amount.formatUnits(known.token.decimals)} $symbol",
        known.token.icon,
        symbol,
        warning = if (known.sharesBuiltInSymbol) "Not the built-in $symbol: another contract" else null,
    )
}

private fun tokenSymbol(address: Address, tokens: List<DecodingToken>): String =
    tokens.firstOrNull { it.token.address == address }?.let { displaySymbol(it.token.symbol) }
        ?: shortAddress(Eip55.format(address))


private fun callData(selector: String?, size: Int): String =
    selector?.let { "Function 0x$it, ${bytes(size)} of call data" }
        ?: "${bytes(size)} of call data, shorter than a function's selector"

private fun bytes(count: Int) = if (count == 1) "1 byte" else "${grouped(count.toLong())} bytes"

/**
 * [terms] as screens show them, a refund in [network]'s own currency, before the warning that
 * says what they can do.
 */
internal fun gasTermsItem(terms: SafeTxGasTerms, network: Network): GasTermsItem = GasTermsItem(
    lines = listOf(
        "safeTxGas: ${grouped(terms.safeTxGas)}",
        "baseGas: ${grouped(terms.baseGas)}",
        "gasPrice: ${grouped(terms.gasPrice)}" + if (terms.gasToken == null) " wei" else " of the gas token's units",
        if (terms.gasToken == null) {
            "A refund is paid in ${network.nativeSymbol}"
        } else {
            "A refund is paid in the gas token"
        },
        if (terms.receiver == null) "A refund goes to whoever executes it" else "A refund goes to the refund receiver",
    ),
    addresses = listOfNotNull(
        terms.gasToken?.let { ActionAddress("Gas token", Eip55.format(it)) },
        terms.receiver?.let { ActionAddress("Refund receiver", Eip55.format(it)) },
    ),
)

/** What gas and refund terms can do, and that this wallet does not sign them (docs/wallet-ux.md, screen 17). */
internal const val GAS_TERMS_WARNING =
    "Gas and refund terms: a refund pays (gas used + baseGas) × gasPrice to the refund receiver, bounded only " +
        "by what the Safe holds, and with a gas price the call gets exactly safeTxGas; a safeTxGas without one " +
        "lets a failing call use up the nonce. Serotine proposes none, and does not sign, approve or execute a " +
        "transaction that carries them."

/** Why a Safe transaction that enables a module is not signed, approved or executed (owner ruling on #54). */
internal const val MODULE_ENABLED_REFUSAL =
    "Enabling a module is refused: a module can move the Safe's funds without its owners. Which modules may be " +
        "enabled is still open (#54)."

/** Why a Safe transaction was not imported, signed, approved or executed, display-ready. */
internal fun safeTxRefusalText(refusal: SafeTxRefusal, network: Network): String = when (refusal) {
    SafeTxRefusal.Malformed -> "This text is not a Safe signing request."
    is SafeTxRefusal.UnknownVersion ->
        "A signing request of a newer version (${refusal.version}): update Serotine to read it."
    // The same address on another network is another Safe: both are named (docs/networks.md, N15).
    is SafeTxRefusal.WrongChain -> Network.ofChainId(refusal.chainId)?.let { other ->
        "This request is for the Safe at this address on ${other.displayName}. This account is the Safe on " +
            "${network.displayName}: a different Safe, with its own owners and nonce. Watch the Safe on " +
            "${other.displayName} to sign it there."
    } ?: "A request for chain id ${refusal.chainId}, which is not a network this wallet supports."
    SafeTxRefusal.NotASafe -> "The address holds no Safe Serotine recognises, as its verified code and storage say."
    is SafeTxRefusal.OtherSafe ->
        "This request is for another Safe, ${Eip55.format(refusal.safe)}: import it from that Safe's own screen."
    is SafeTxRefusal.NonceUsed ->
        "Its nonce is used: the Safe is at nonce ${refusal.safeNonce} already, so it can never run."
    is SafeTxRefusal.NotNext ->
        "Not next: the Safe is at nonce ${refusal.safeNonce}, and the transactions before this one run first."
    is SafeTxRefusal.NotAnOwner -> "${Eip55.format(refusal.address)} is not one of the Safe's current owners."
    is SafeTxRefusal.BadSignature ->
        "The signature said to be ${Eip55.format(refusal.owner)}'s is not that owner's over this transaction: " +
            "refused, and nothing of the request is kept."
    SafeTxRefusal.DelegateCall -> "A delegate call: Serotine does not sign, approve or execute one yet. Batches " +
        "through the pinned MultiSendCallOnly come later."
    is SafeTxRefusal.AlreadySigned -> "${Eip55.format(refusal.owner)} has signed it already."
    SafeTxRefusal.AlreadyApproved -> "This owner has approved it on chain already."
    SafeTxRefusal.AlreadySent ->
        "Sent from this wallet already and in no block yet: sending it again would only pay a fee to revert."
    is SafeTxRefusal.ThresholdNotMet ->
        "${refusal.confirmations} of the ${refusal.threshold} confirmations it needs: not enough to execute."
    SafeTxRefusal.GasTerms -> "It carries gas or refund terms: Serotine does not sign, approve or execute those."
    is SafeTxRefusal.UnpinnedFallbackHandler -> "It sets a fallback handler Serotine does not pin, " +
        "${Eip55.format(refusal.handler)}: the handler answers the signature checks other contracts make for the " +
        "Safe, so only the canonical CompatibilityFallbackHandler 1.4.1, or none, is signed, approved or executed " +
        "here."
    is SafeTxRefusal.ModuleEnabled -> MODULE_ENABLED_REFUSAL
    SafeTxRefusal.UndecodedSelfCall -> "A call to the Safe itself that could not be decoded exactly: on chain it " +
        "could still change the Safe's settings, so Serotine does not sign, approve or execute it."
    is SafeTxRefusal.NeedsRecoveryModule -> "It would leave all ${refusal.owners} owners needed to sign: with one " +
        "of them lost, the rest could never reach the threshold again. That needs a recovery module, and Serotine " +
        "recognises none yet (#54), so it does not sign, approve or execute it."
    SafeTxRefusal.Syncing -> "The light client is not synced yet: try again in a moment."
    is SafeTxRefusal.Unavailable -> unavailableText(refusal.reason)
    is SafeTxRefusal.SafeUnavailable ->
        if (refusal.reason == ReadUnavailable.UNPROVEN) SAFE_UNPROVEN else unavailableText(refusal.reason)
    is SafeTxRefusal.NotQuoted -> callRefusalText(refusal.refusal, network)
    is SafeTxRefusal.TooOldToSign -> "This Safe is still on version ${refusal.version}, from before the way " +
        "Serotine checks signatures today: it is shown, but not signed, approved or executed here. Migrating it " +
        "to a newer version (in Safe{Wallet} or another app) fixes this."
}

/**
 * What a step says while it checks the Safe on chain (Import, Sign, Propose's signature, and
 * Approve and Execute's quote): a check that waits out a reading that could not be verified for
 * the next one, up to 45 s. The display is kept on meanwhile ([eth.serotine.ui.platform.KeepScreenOn]).
 */
internal const val CHECKING_SAFE = "Checking the Safe on chain…"

/** Why a check of the Safe itself came to nothing this time, after it was tried again for a while. */
internal const val SAFE_UNPROVEN = "The Safe's owners and nonce could not be verified this time: try again."

/** Why the transaction an account here would send was not quoted, display-ready. */
internal fun callRefusalText(refusal: CallRefusal, network: Network): String = when (refusal) {
    CallRefusal.InsufficientFunds -> "Not enough ${network.nativeSymbol} in this account for the most the fee can " +
        "be. Transactions not mined yet count too."
    is CallRefusal.Infeasible -> "This transaction cannot succeed: ${refusal.detail}"
    is CallRefusal.Reverts -> "It reverts, run as a verified call" +
        refusal.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty() + ". Nothing was signed."
    CallRefusal.Refused -> "The light client on this device cannot quote this transaction."
    CallRefusal.Syncing -> "The light client is not synced yet: try again in a moment."
    is CallRefusal.Unavailable -> if (refusal.reason == ReadUnavailable.UNPROVEN) {
        "${figureText(refusal.figure, "of the quote")} could not be verified this time: try again."
    } else {
        unavailableText(refusal.reason)
    }
    is CallRefusal.EarlierMaybeSent -> EARLIER_MAYBE_SENT
}

/**
 * [safeTxRefusalText] in simple mode's words, with no block (docs/simple-ui.md); null where that is
 * [safeTxRefusalText] itself, which names none.
 */
internal fun simpleSafeTxRefusalText(refusal: SafeTxRefusal): String? =
    (refusal as? SafeTxRefusal.NotQuoted)?.let { simpleCallRefusalText(it.refusal) }

/**
 * [callRefusalText] in simple mode's words, with no block (docs/simple-ui.md); null where that is
 * [callRefusalText] itself, which names none.
 */
internal fun simpleCallRefusalText(refusal: CallRefusal): String? =
    if (refusal is CallRefusal.Reverts) "It reverts, checked on chain. Nothing was signed." else null

/**
 * Why nothing new is quoted from an account whose earlier transaction may still be on its way
 * ([CallRefusal.EarlierMaybeSent]), where the screen offers no way to its status: Send on that
 * account does.
 */
internal const val EARLIER_MAYBE_SENT = "An earlier transaction from this account may still be on its way: no " +
    "peer answered when it was broadcast, and no block has it yet. Try it again from its status (Send on this " +
    "account opens it) rather than sign a new one, which could pay twice."

/**
 * The figure of a quote that could not be verified, as a sentence starts with it ("The fee"), or,
 * when not said, "A figure" and [ofWhat] ("A figure of the quote").
 */
internal fun figureText(figure: QuoteFigure?, ofWhat: String): String = when (figure) {
    QuoteFigure.BALANCE -> "The account's balance"
    QuoteFigure.NONCE -> "The account's nonce"
    QuoteFigure.FEES -> "The fee"
    QuoteFigure.GAS -> "The gas estimate"
    QuoteFigure.RUN -> "The verified run of the transaction"
    QuoteFigure.ALLOWANCE -> "The token's allowance"
    QuoteFigure.ROUTE -> "The swap's route"
    null -> "A figure $ofWhat"
}

/** Why a verified figure could not be had right now, display-ready. */
internal fun unavailableText(reason: ReadUnavailable): String = when (reason) {
    ReadUnavailable.NO_ENGINE -> "There is no light client on this device, so nothing can be verified."
    ReadUnavailable.NETWORK_OFF -> "The network's light client is not running, so nothing can be verified."
    ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so nothing can be verified."
    ReadUnavailable.SYNCING -> "The light client is still syncing, so nothing can be verified yet."
    ReadUnavailable.UNPROVEN -> "A figure could not be verified this time: try again."
    ReadUnavailable.REFUSED -> "The light client on this device cannot read this."
}

/** [amount] in whole digits, grouped by thousands ("1,500,000"). */
private fun grouped(amount: UInt256): String =
    amount.toDecimal().reversed().chunked(3).joinToString(",").reversed()

/**
 * The tokens a Safe transaction's transfer is shown in the units of: every token the wallet knows,
 * hidden ones too, but no built-in entry its contract contradicts; an added one that takes a
 * built-in token's symbol marked as such.
 */
internal fun KnownTokenList.forDecoding(): List<DecodingToken> =
    tokens.filter { it.check !is BuiltInCheck.Disagrees }.map { DecodingToken(it.token, it.sharesBuiltInSymbol) }
