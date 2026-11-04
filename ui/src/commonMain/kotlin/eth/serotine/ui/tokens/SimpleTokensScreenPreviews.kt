package eth.serotine.ui.tokens

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Settings → Tokens in simple mode in Android Studio's preview pane, one preview per state it draws
// differently from Detailed view. Built from display-ready values, never from the ViewModel.
// SimpleTokensScreenPreviewsTest renders each one in simpleTokensScreenPreviews, at the end. The
// contract addresses are EIP-55's own examples, not the tokens' own.

private fun builtIn(symbol: String, name: String, address: String, icon: String) = TokenItem(
    symbol,
    name,
    address,
    icon,
    builtIn = true,
    status = "Built in · checked against its contract at block 8,412,307",
    problem = false,
    hidden = false,
    simpleStatus = "Built in · checked against its contract",
)

private val state = TokensUiState(
    sections = listOf(
        TokenSection(
            NetworkLabel(1, "Ethereum", testnet = false),
            listOf(
                builtIn("USDC", "USDC", "0x52908400098527886E0F7030069857D2E4169EE7", "usdc"),
                builtIn("LINK", "ChainLink Token", "0x8617E340B3D01FA5F11F306F4090FD50E238070D", "link"),
            ),
        ),
    ),
)

@Composable
private fun SimpleTokensPreview(darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleTokensScreen(
            state,
            onBack = {},
            onAddToken = {},
            onCopy = {},
            onSetHidden = { _, _, _ -> },
            onRemove = { _, _ -> },
        )
    }
}

/** Each token's check with no block. */
@Preview
@Composable
private fun SimpleTokensListPreview() = SimpleTokensPreview()

@Preview
@Composable
private fun SimpleTokensDarkPreview() = SimpleTokensPreview(darkTheme = true)

/** Every preview above, which SimpleTokensScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleTokensScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SimpleTokensListPreview() },
    { SimpleTokensDarkPreview() },
)
