package eth.serotine.ui.swap

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.LightClientItem

/**
 * Swap in simple mode (docs/simple-ui.md, W1 to W4): [SwapScreen] for the same [state] with simple
 * mode's chrome: no light-client strip, no block in the form, each review numbered as its step
 * ("Confirm step 1 of 2") with its detail (routes, blocks, the fee's make-up) behind *Details*, and
 * the wait for the approval's block between the steps. Every check, refusal, greyed-out reason and
 * the venue the user chooses are the same as in Detailed view.
 * [syncLine] is its network's sync line (docs/simple-ui.md, *The sync line*), drawn while it says something.
 */
@Composable
fun SimpleSwapScreen(
    state: SwapUiState,
    onSellChange: (contract: String?) -> Unit,
    onBuyChange: (contract: String?) -> Unit,
    onAmountChange: (String) -> Unit,
    onMax: () -> Unit,
    onToleranceChange: (String) -> Unit,
    onFeeChange: (FeeLevel) -> Unit,
    onVenueChange: (SwapVenue) -> Unit,
    onSwapOnUniswap: () -> Unit,
    onReview: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
    onRetry: () -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
    onOpenOrder: (uid: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    syncLine: LightClientItem? = null,
) = SwapScreen(
    state = state.simple(),
    chrome = SwapChrome.Simple(syncLine),
    onSellChange = onSellChange,
    onBuyChange = onBuyChange,
    onAmountChange = onAmountChange,
    onMax = onMax,
    onToleranceChange = onToleranceChange,
    onFeeChange = onFeeChange,
    onVenueChange = onVenueChange,
    onSwapOnUniswap = onSwapOnUniswap,
    onReview = onReview,
    onEdit = onEdit,
    onRefresh = onRefresh,
    onPasswordChange = onPasswordChange,
    onSign = onSign,
    onSignWithBiometrics = onSignWithBiometrics,
    onRetry = onRetry,
    onOpenTransfer = onOpenTransfer,
    onOpenOrder = onOpenOrder,
    onBack = onBack,
    modifier = modifier,
)

/** [this] with no block in its form: the reviews keep their blocks for their *Details*. */
private fun SwapUiState.simple(): SwapUiState = when (this) {
    is SwapUiState.Compose -> copy(
        form = form.copy(
            sellOptions = form.sellOptions.map { it.copy(verifiedAt = null) },
            sell = form.sell?.copy(verifiedAt = null),
            buyOptions = form.buyOptions.map { it.copy(verifiedAt = null) },
            buy = form.buy?.copy(verifiedAt = null),
            error = form.simpleError ?: form.error,
        ),
    )
    else -> this
}
