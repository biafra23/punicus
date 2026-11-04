package eth.serotine.ui.tokens

import eth.serotine.ui.components.NetworkLabel

/**
 * Everything Settings → Tokens renders (docs/wallet-ux.md, screens 18 and 20): every token the
 * wallet knows, grouped by network ([sections], each network with any; docs/networks.md,
 * *Values*); [notice], why the settings cannot be changed right now, what the last change could
 * not do, or that damaged settings were set aside; and whether they can be changed at all
 * ([canChange]: not while they cannot be read, when every change would be refused).
 */
data class TokensUiState(
    val sections: List<TokenSection> = emptyList(),
    val notice: String? = null,
    val canChange: Boolean = true,
)

/** The tokens known on one [network], in its display order. */
data class TokenSection(val network: NetworkLabel, val items: List<TokenItem>)

/**
 * One known token: its [symbol], [name], EIP-55 contract [address] and icon key ([icon]; null:
 * a monogram); [builtIn] or added by the user; [status], what its own check says, which is a
 * [problem] when the entry is not shown for it; [hidden] by the user; and [warning], set when
 * an added token's symbol is a built-in token's. [simpleStatus] is [status] with no block, for
 * simple mode (docs/simple-ui.md); null where that is [status] itself.
 */
data class TokenItem(
    val symbol: String,
    val name: String,
    val address: String,
    val icon: String?,
    val builtIn: Boolean,
    val status: String,
    val problem: Boolean,
    val hidden: Boolean,
    val warning: String? = null,
    val simpleStatus: String? = null,
)
