package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.repository.TokenMetadata
import eth.serotine.domain.repository.TokenSettings
import eth.serotine.domain.repository.UserTokens
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Every token the wallet knows, as Settings → Tokens lists it and the account screen draws
 * on: the built-in list, each entry checked against what its contract says (docs/wallet-ux.md,
 * *Tokens*: an entry its contract contradicts is not shown), then the tokens the user added,
 * with the ones the user hid marked.
 */
class ObserveKnownTokens(
    private val catalog: TokenCatalog,
    private val userTokens: UserTokens,
    private val metadata: TokenMetadata,
) {
    /**
     * The list, re-emitted when the built-in list, a check or the settings change. A change of
     * the settings (a token hidden, added or removed) does not start the checks again.
     */
    operator fun invoke(network: Network): Flow<KnownTokenList> =
        combine(checkedBuiltIns(network), userTokens.observe(network)) { checked, settings ->
            val loaded = settings as? TokenSettings.Loaded
            val hidden = loaded?.hidden.orEmpty()
            val builtInSymbols = checked.mapTo(HashSet()) { (token, _) -> token.symbol.lowercase() }
            val listed = checked.map { (token, check) ->
                KnownToken(token, TokenOrigin.BUILT_IN, check, token.address in hidden, sharesBuiltInSymbol = false)
            }
            val added = loaded?.added.orEmpty()
                .filter { token -> checked.none { (builtIn, _) -> builtIn.address == token.address } }
                .map { token ->
                    KnownToken(
                        token,
                        TokenOrigin.ADDED,
                        check = null,
                        hidden = token.address in hidden,
                        sharesBuiltInSymbol = token.symbol.lowercase() in builtInSymbols,
                    )
                }
            KnownTokenList(
                listed + added,
                settingsUnavailable = settings is TokenSettings.Unreadable,
                settingsSetAside = loaded?.setAside == true,
            )
        }

    /** Each built-in entry with its check, re-checked only when the built-in list changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun checkedBuiltIns(network: Network): Flow<List<Pair<Token, BuiltInCheck>>> =
        catalog.observeTokens(network).flatMapLatest { builtIn ->
            if (builtIn.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(builtIn.map {
                    token -> checkOf(network, token).map { check -> token to check } }) { it.toList() }
            }
        }

    /** What the contract of the built-in [token] on [network] says, compared with what it is listed as. */
    private fun checkOf(network: Network, token: Token): Flow<BuiltInCheck> = metadata.observe(network, token.address)
        .map { reading ->
            when (reading) {
                MetadataReading.Syncing -> BuiltInCheck.Checking
                is MetadataReading.Unavailable -> BuiltInCheck.Unavailable(reading.reason)
                MetadataReading.NotAToken -> BuiltInCheck.Disagrees(saidSymbol = null, saidDecimals = null)
                is MetadataReading.Verified ->
                    if (reading.symbol == token.symbol && reading.decimals == token.decimals) {
                        BuiltInCheck.Agrees(reading.blockNumber)
                    } else {
                        BuiltInCheck.Disagrees(reading.symbol, reading.decimals)
                    }
            }
        }
        .onStart { emit(BuiltInCheck.Checking) }
}

/**
 * What [ObserveKnownTokens] emits: the [tokens]; whether the user's settings cannot be read
 * ([settingsUnavailable]: their added tokens are then missing); and whether damaged settings
 * were set aside, so the ones shown started over ([settingsSetAside]).
 */
data class KnownTokenList(
    val tokens: List<KnownToken>,
    val settingsUnavailable: Boolean = false,
    val settingsSetAside: Boolean = false,
) {
    /** The tokens rows show: not hidden, and not a built-in entry its contract contradicts. */
    val shown: List<Token>
        get() = tokens.filter { it.shown }.map { it.token }
}

/**
 * One token the wallet knows: [token] as listed or added, where it comes from ([origin]), the
 * check of a built-in entry against its contract ([check], null for an added one), whether
 * the user [hidden] it, and, for an added one, whether its symbol is a built-in token's
 * ([sharesBuiltInSymbol]): a contract may call itself anything.
 */
data class KnownToken(
    val token: Token,
    val origin: TokenOrigin,
    val check: BuiltInCheck?,
    val hidden: Boolean,
    val sharesBuiltInSymbol: Boolean,
) {
    /** Whether rows show it. */
    val shown: Boolean
        get() = !hidden && check !is BuiltInCheck.Disagrees
}

/** Where a known token comes from. */
enum class TokenOrigin {
    /** The built-in list. */
    BUILT_IN,

    /** Added by the user, by address. */
    ADDED,
}

/** The app's own check of a built-in entry against its contract. */
sealed interface BuiltInCheck {

    /** Not answered yet. */
    data object Checking : BuiltInCheck

    /** The contract says what the entry is listed as, at block [blockNumber] (null: not said). */
    data class Agrees(val blockNumber: Long?) : BuiltInCheck

    /**
     * The contract says otherwise — symbol [saidSymbol] with [saidDecimals], or it does not
     * answer as a token at all (both null) — so the entry is not shown.
     */
    data class Disagrees(val saidSymbol: String?, val saidDecimals: Int?) : BuiltInCheck

    /** No verified answer right now, for [reason]; the entry is shown meanwhile. */
    data class Unavailable(val reason: ReadUnavailable) : BuiltInCheck
}
