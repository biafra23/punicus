package eth.serotine.ui.components

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.AccountValue
import eth.serotine.domain.usecase.RecipientLookup
import eth.serotine.domain.usecase.Valuation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlin.time.TimeMark
import kotlin.time.Duration.Companion.seconds

// The transfer form (docs/wallet-ux.md, screen 7) as Send and a Safe's proposal of a transfer
// (screen 16) both fill it in: the account's assets as options, the recipient typed or picked and
// resolved, the amount typed in token units, and what the form says about each. Pure: each
// ViewModel keeps its own inputs and its own steps, and draws its form from these.

/** An asset the account can send, with what the form shows of it. */
internal data class AssetOption(
    val asset: TransferAsset,
    val symbol: String,
    val name: String,
    val icon: String?,
    val decimals: Int,
    val balance: UInt256?,
    val balanceText: String,
    val verifiedAt: String?,
    /** When the head the balance was proved at was produced, in unix seconds; null when not known. */
    val provenAt: Long?,
    val valuation: Valuation?,
    val warning: String?,
) {
    /** The token's contract, null for the network's currency. */
    val contract: Address?
        get() = (asset as? TransferAsset.Erc20Token)?.token?.address

    /** As the form lists it, its balance dated against [now] (unix seconds) where one is given. */
    fun choice(now: Long? = null) = AssetChoice(
        symbol = symbol,
        name = name,
        icon = icon,
        balance = balanceText,
        contract = contract?.let(Eip55::format),
        verifiedAt = verifiedAt,
        warning = warning,
        age = provenAt?.let { at -> now?.let { "updated ${agoText(at, it)}" } },
    )
}

/**
 * The network's currency and every token the account holds, as [value] reads them, and the token
 * [chosen] whatever its balance says: it was asked for, and its row says what is known of it. With
 * [everyToken], every token the wallet lists, held or not: what a swap may buy.
 */
internal fun assetOptions(
    value: AccountValue,
    chosen: Address?,
    network: Network,
    everyToken: Boolean = false,
): List<AssetOption> {
    val native = value.native
    val nativeOption = AssetOption(
        asset = TransferAsset.Native,
        symbol = network.nativeSymbol,
        name = network.nativeName,
        icon = network.nativeIcon,
        decimals = NATIVE_DECIMALS,
        balance = (native as? BalanceReading.Verified)?.wei?.toUInt256OrNull(),
        // A balance last verified at an older head is not one to send from: it may have changed.
        balanceText = when (native) {
            null -> CHECKING
            is BalanceReading.LastKnown -> SYNCING
            else -> balanceText(native, network)
        },
        verifiedAt = (native as? BalanceReading.Verified)?.let { block(it.blockNumber) },
        provenAt = (native as? BalanceReading.Verified)?.provenAt,
        valuation = value.nativeValue,
        warning = null,
    )
    val tokens = value.tokens.mapNotNull { (holding, valuation) ->
        val token = holding.token
        val reading = holding.reading
        val verified = reading as? TokenBalanceReading.Verified
        if (!everyToken && token.address != chosen && (verified == null || verified.units.isZero)) {
            return@mapNotNull null
        }
        val symbol = displaySymbol(token.symbol)
        AssetOption(
            asset = TransferAsset.Erc20Token(token),
            symbol = symbol,
            name = displayName(token.name),
            icon = token.icon,
            decimals = token.decimals,
            balance = verified?.let { UInt256.parse(it.units.decimal) },
            balanceText = when (reading) {
                is TokenBalanceReading.Verified -> formatUnits(reading.units.decimal, token.decimals, symbol)
                null, TokenBalanceReading.Syncing, is TokenBalanceReading.LastKnown -> CHECKING
                TokenBalanceReading.NotAToken -> "Not a token"
                is TokenBalanceReading.Unavailable -> "Not verified"
            },
            verifiedAt = verified?.blockNumber?.let(::block),
            provenAt = verified?.provenAt,
            valuation = valuation,
            warning = if (holding.sharesBuiltInSymbol) "Not the built-in $symbol: another contract" else null,
        )
    }
    return listOf(nativeOption) + tokens
}

/** What the typed recipient [text] is, as far as is known yet. */
internal sealed interface RecipientState {
    val text: String

    /** Nothing typed. */
    data object Empty : RecipientState {
        override val text = ""
    }

    /** A name, being resolved. */
    data class Resolving(override val text: String) : RecipientState

    /** [text] is [result]. */
    data class Done(override val text: String, val result: RecipientLookup) : RecipientState
}

/**
 * The recipient as [typed] (the text, trimmed, and what asks to resolve it again when it changes:
 * how often the user asked; on Watch, how often its registry was asked again), resolved by
 * [lookUp]: an address at once, a name once the typing paused, so that a name is not resolved for
 * every letter of it. While the light client is still syncing, it is looked up again by itself
 * every [SYNC_RETRY_MS] until it can be answered (docs/simple-ui.md, *A read refused while syncing
 * says so*).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun recipientStates(
    typed: Flow<Pair<String, Any?>>,
    lookUp: suspend (String) -> RecipientLookup,
): Flow<RecipientState> = typed
    .distinctUntilChanged()
    .transformLatest { (text, _) ->
        if (text.isEmpty()) {
            emit(RecipientState.Empty)
            return@transformLatest
        }
        // An address has no dot; ResolveRecipient takes anything with one as a name.
        if ('.' in text) {
            emit(RecipientState.Resolving(text))
            delay(NAME_PAUSE_MS)
        }
        var result = lookUp(text)
        emit(RecipientState.Done(text, result))
        while (result.syncing) {
            delay(SYNC_RETRY_MS)
            result = lookUp(text)
            emit(RecipientState.Done(text, result))
        }
    }

/** Whether this lookup could not be answered because the light client is still syncing. */
private val RecipientLookup.syncing: Boolean
    get() = this is RecipientLookup.Unavailable && reason == ReadUnavailable.SYNCING

/**
 * Why asking again for [result] by hand changes nothing, for its greyed-out Try again: the light
 * client is still syncing, and the name is looked up again by itself. Null where asking may help.
 */
internal fun retryLookupWaits(result: RecipientLookup?): String? = RETRY_WAITS.takeIf { result?.syncing == true }

private const val RETRY_WAITS = "The light client is still syncing. The name is looked up again by itself every few " +
    "seconds until it has caught up."

/** [text], a typed recipient, as it is resolved: an all-caps copy upper-cases the `0x` prefix too. */
internal fun recipientText(text: String): String = if (text.startsWith("0X")) "0x" + text.drop(2) else text

/** A recipient resolved again before a quote, from the name it was resolved from. */
internal sealed interface ResolvedAgain {

    /** [found]: the name still has the address shown, at [found]'s block. */
    data class Same(val found: RecipientLookup.Found) : ResolvedAgain

    /** It moved, or cannot be verified again right now: [message], and nothing is signed for it. */
    data class Changed(val message: String) : ResolvedAgain
}

/**
 * [found] resolved again by [lookUp] when it came from a name: what is signed is the address the
 * name has now, and one that moved is said, not signed. One typed as an address stands as it is.
 * [said] words why it changed: for the name, and whether it could not be verified again
 * ([unverifiable]) rather than moved; a recipient's words unless a caller has its own.
 */
internal suspend fun resolveAgain(
    found: RecipientLookup.Found,
    lookUp: suspend (String) -> RecipientLookup,
    said: (name: String, unverifiable: Boolean) -> String = ::recipientChanged,
): ResolvedAgain {
    val name = found.name ?: return ResolvedAgain.Same(found)
    val again = lookUp(name)
    if (again is RecipientLookup.Found && again.address == found.address) return ResolvedAgain.Same(again)
    if (again.syncing) return ResolvedAgain.Changed(stillSyncing(name))
    return ResolvedAgain.Changed(said(name, again is RecipientLookup.Unavailable))
}

/** Why [name] is not taken again now: the light client is still syncing, never "this time". */
private fun stillSyncing(name: String): String =
    "The light client is still syncing, so “$name” can't be checked again yet. Try again once it has caught up."

/** Why a recipient's [name] is not signed for again: it could not be verified again ([unverifiable]), or it moved. */
private fun recipientChanged(name: String, unverifiable: Boolean): String = if (unverifiable) {
    "“$name” could not be resolved with verified calls again just now, so nothing can be " +
        "signed for it: review it again in a moment."
} else {
    "“$name” no longer resolves to the address shown: check the recipient."
}

/**
 * The recipient [found] as the form shows it: in full, with the name it was resolved from and its
 * name among [accounts], and [note] said of its address (the account itself, the token's contract).
 */
internal fun recipientItem(found: RecipientLookup.Found, accounts: List<Account>, note: (Address) -> String?) =
    RecipientItem(
        address = Eip55.format(found.address),
        name = found.name,
        resolvedAt = found.blockNumber?.let { "Resolved at finalized block ${grouped(it)}" },
        label = accounts.firstOrNull { it.address == found.address }?.label,
        note = note(found.address),
    )

/** What the recipient field says under [text], and whether that is an error. */
internal fun recipientNote(text: String, lookup: RecipientState?): Pair<String?, Boolean> = when (lookup) {
    null, RecipientState.Empty -> null to false
    is RecipientState.Resolving -> "Resolving the name with verified calls…" to false
    is RecipientState.Done -> when (val result = lookup.result) {
        is RecipientLookup.Found -> null to false
        RecipientLookup.NotAnAddress -> when {
            '.' in text -> NOT_A_NAME to true
            // Not while an address or a name is still being typed.
            text.length < ADDRESS_LENGTH && HEX_SO_FAR.matches(text) -> null to false
            !text.startsWith("0x", ignoreCase = true) && LABEL_SO_FAR.matches(text) ->
                "A name ends in its domain, as in name.eth." to false
            else -> NOT_AN_ADDRESS to true
        }
        RecipientLookup.ChecksumMismatch -> CHECKSUM to true
        RecipientLookup.ZeroAddress -> ZERO_ADDRESS to true
        RecipientLookup.OffchainOnly -> OFFCHAIN to true
        RecipientLookup.NoAddress -> "This name has no address." to true
        is RecipientLookup.NoNamesOn -> noNamesText(result.network) to true
        is RecipientLookup.Unavailable -> nameUnavailable(result.reason) to true
    }
}

/** Why a name typed on [network] is refused: names are not resolved there (docs/networks.md, *Names*). */
internal fun noNamesText(network: Network): String =
    "Names are not resolved on ${network.displayName} yet: enter the address."

/** The label of a field that takes an address, and a name where [network] resolves names. */
internal fun addressFieldLabel(network: Network): String = if (network.ensNames) "Address or ENS name" else "Address"

/** What a recipient field says under it on [network] while nothing is typed. */
internal fun recipientHint(network: Network): String = if (network.ensNames) {
    "An address, an ENS name, or one of your accounts below"
} else {
    "An address, or one of your accounts below: names are not resolved on ${network.displayName} yet"
}

/** An amount typed, in an asset's smallest units, or why it is none (both null while still being typed). */
internal class TypedAmount(val units: UInt256?, val error: String?)

/** [text] as an amount of [option], typed in its units. */
internal fun typedAmount(text: String, option: AssetOption?): TypedAmount {
    val typed = text.trim()
    if (typed.isEmpty() || option == null) return TypedAmount(null, null)
    val units = UInt256.parseUnits(typed, option.decimals)
    return when {
        units == null -> TypedAmount(null, amountError(typed, option))
        units.isZero -> TypedAmount(null, "Enter an amount above zero.")
        else -> TypedAmount(units, null)
    }
}

private fun amountError(typed: String, option: AssetOption): String? {
    val fraction = typed.substringAfter('.', missingDelimiterValue = "")
    return when {
        // "5." on its way to "5.5".
        option.decimals > 0 && typed.endsWith('.') &&
            UInt256.parseUnits(typed.dropLast(1), option.decimals) != null -> null
        ',' in typed -> "Use a dot for the decimals: 0.5, not 0,5."
        DECIMAL.matches(typed) && fraction.length > option.decimals -> if (option.decimals == 0) {
            "${option.symbol} has no decimals: whole numbers only."
        } else {
            "${option.symbol} has ${option.decimals} decimals: at most ${option.decimals} digits after the dot."
        }
        DECIMAL.matches(typed) -> "That is more than any balance can be."
        else -> "Not an amount: digits, with a dot for the decimals (0.5)."
    }
}

/**
 * What [amount] of [option] is worth in [currency], as a hint beside the amount: the verified
 * balance's value scaled to the amount — the same price, display only, never part of the amount
 * (CLAUDE.md, *Data sources*).
 */
internal fun amountHint(amount: UInt256?, option: AssetOption?, currency: DisplayCurrency, network: Network): String? {
    amount ?: return null
    val valued = option?.valuation as? Valuation.Valued ?: return null
    val balance = option.balance?.takeUnless { it.isZero } ?: return null
    val share = amount.toDecimal().toDouble() / balance.toDecimal().toDouble()
    // A hint, so "≈" leads it; a value at a pool's average already starts with one.
    return valuationText(valued.copy(amount = valued.amount * share), currency, network)
        ?.let { if (it.startsWith("≈")) it else "≈ $it" }
}

/** Why a name could not be resolved with verified calls, display-ready. */
internal fun nameUnavailable(reason: ReadUnavailable): String = when (reason) {
    ReadUnavailable.NO_ENGINE -> "There is no light client on this device, so the name cannot be resolved."
    ReadUnavailable.NETWORK_OFF -> "The network's light client is not running, so the name cannot be resolved."
    ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so the name cannot be resolved."
    ReadUnavailable.SYNCING ->
        "The light client is still syncing, so the name can't be resolved yet. It is looked up again once it has " +
            "caught up."
    ReadUnavailable.UNPROVEN -> "The name could not be resolved with verified calls this time."
    ReadUnavailable.REFUSED -> "The light client on this device cannot resolve names."
}

/**
 * What the most a quoted [transaction]'s fee can be is made of, at [level], from the verified fee
 * history up to [feeBlock]: what every review of a transaction from an account held here says.
 */
internal fun feeDetail(level: FeeLevel, transaction: Eip1559Transaction, feeBlock: Long): String =
    "${levelName(level)} fee: ${grouped(transaction.gasLimit)} gas × at most " +
        "${transaction.maxFeePerGas.formatUnits(GWEI_DECIMALS)} gwei, from the verified fee history " +
        "up to block ${grouped(feeBlock)}. Only what the block charges is paid."

/** A fee level's name, as the fee choice shows it. */
internal fun levelName(level: FeeLevel): String = when (level) {
    FeeLevel.SLOW -> "Slow"
    FeeLevel.NORMAL -> "Normal"
    FeeLevel.FAST -> "Fast"
}

/** The block a balance before a transaction was verified at, as a review says it. */
internal fun verifiedAt(block: Long?) = block?.let { "verified at block ${grouped(it)}" } ?: "verified"

/** [units] in whole units with [decimals], exact: what a review shows and what is signed. */
internal fun exactAmount(units: UInt256, decimals: Int, symbol: String) = "${units.formatUnits(decimals)} $symbol"

private fun block(number: Long) = "block ${grouped(number)}"

private const val GWEI_DECIMALS = 9

/**
 * The oldest a quote may be at the tap that signs it; an older one is made again first, and said
 * ([STALE_QUOTE]): a quote is never signed stale (docs/wallet-ux.md, screen 8).
 */
internal val QUOTE_MAX_AGE = 60.seconds

/** What a review says under its unlock once a stale quote was made again at the tap that would sign it. */
internal const val STALE_QUOTE = "The quote was over a minute old, so it was made again: check it, then sign."

/**
 * The least time the biometric prompt is given to be answered in: a quote with less of
 * [QUOTE_MAX_AGE] left at the tap is made again first, as a stale one is.
 */
internal val MIN_PROMPT_TIME = 15.seconds

/**
 * The deadline of the biometric prompt that signs a quote made at [quotedAt]
 * ([Unlock.WithBiometrics.notAfter]): the moment it turns [QUOTE_MAX_AGE] old, absolute, so that
 * nothing between the tap and the prompt stretches it and the quote is never signed older; or
 * null when less than [MIN_PROMPT_TIME] is left of it, and the quote is to be made again first
 * ([PROMPT_TOO_LATE]).
 */
internal fun promptDeadline(quotedAt: TimeMark): TimeMark? =
    (quotedAt + QUOTE_MAX_AGE).takeIf { -it.elapsedNow() >= MIN_PROMPT_TIME }

/**
 * What a review says under its unlock once the tap that opens the biometric prompt came too
 * late in the quote's minute for the prompt ([promptDeadline] null) and the quote was made again.
 */
internal const val PROMPT_TOO_LATE =
    "Too little of the quote's minute was left for the fingerprint or face check, so the quote was made again: " +
        "check it, then sign."

/**
 * What a review says under its unlock once a biometric prompt was not answered within its deadline
 * ([promptDeadline], [Signing.TimedOut]) and the quote was made again.
 */
internal const val PROMPT_TIMED_OUT =
    "The fingerprint or face check took longer than the quote could wait, so nothing was signed and the quote " +
        "was made again: check it, then sign."

/**
 * What a review says under its unlock once the Safe's check before a signature used up what was
 * left of the quote's minute (less the prompt's least time, for the prompt) and the quote was
 * made again: no key was asked for, and no prompt shown.
 */
internal const val CHECK_TOOK_THE_TIME =
    "Checking the Safe on chain took the time the quote had left, so nothing was signed and the quote was made " +
        "again: check it, then sign."

/** What a balance not read yet shows. */
internal const val CHECKING = "Checking…"

/** What the zero address as a recipient says. */
internal const val ZERO_ADDRESS =
    "The zero address: nobody holds its key, so whatever is sent to it is lost. It is refused."

private const val ADDRESS_LENGTH = 42

/** How long the typing must pause before a name is resolved. */
private const val NAME_PAUSE_MS = 600L

/** How often a name is looked up again while the light client is still syncing. */
internal const val SYNC_RETRY_MS = 5_000L
private val HEX_SO_FAR = Regex("^(0|0[xX][0-9a-fA-F]*)$")
private val LABEL_SO_FAR = Regex("^[A-Za-z0-9-]+$")
private val DECIMAL = Regex("^(0|[1-9][0-9]*)(\\.[0-9]+)?$")

private const val NOT_AN_ADDRESS = "Not an address: an address is 0x and 40 hexadecimal digits."
private const val NOT_A_NAME =
    "Not an ENS name Serotine can resolve: for now, names of letters (a–z), digits and hyphens."
private const val CHECKSUM = "The capitals do not match this address's checksum: check it for a typo."
private const val OFFCHAIN =
    "Only an off-chain gateway gives this name's address, and Serotine asks none: it cannot be resolved here."

/** A Uniswap fee tier's [pips] as a percentage: 3 000 is "0.3 %". */
internal fun feeTierText(pips: Int): String = UInt256.of(pips.toLong()).formatUnits(4) + " %"
