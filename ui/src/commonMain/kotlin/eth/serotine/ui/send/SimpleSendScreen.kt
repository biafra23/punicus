package eth.serotine.ui.send

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.LightClientItem

/**
 * Send in simple mode (docs/simple-ui.md, S1 and S2): [SendScreen] for the same [state] with simple
 * mode's chrome, no light-client strip, no block in the form (an asset's balance and a name's
 * resolution say none), and the simple review, its detail behind *Details*. Every check, refusal and
 * greyed-out reason is the same as in Detailed view.
 * [syncLine] is its network's sync line (docs/simple-ui.md, *The sync line*), drawn while it says something.
 */
@Composable
fun SimpleSendScreen(
    state: SendUiState,
    onAssetChange: (contract: String?) -> Unit,
    onToChange: (String) -> Unit,
    onPickAccount: (address: String) -> Unit,
    onRetryLookup: () -> Unit,
    onAmountChange: (String) -> Unit,
    onMax: () -> Unit,
    onFeeChange: (FeeLevel) -> Unit,
    onReview: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
    onRetry: () -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    syncLine: LightClientItem? = null,
) = SendScreen(
    state = state.simple(),
    chrome = SendChrome.Simple(syncLine),
    onAssetChange = onAssetChange,
    onToChange = onToChange,
    onPickAccount = onPickAccount,
    onRetryLookup = onRetryLookup,
    onAmountChange = onAmountChange,
    onMax = onMax,
    onFeeChange = onFeeChange,
    onReview = onReview,
    onEdit = onEdit,
    onRefresh = onRefresh,
    onPasswordChange = onPasswordChange,
    onSign = onSign,
    onSignWithBiometrics = onSignWithBiometrics,
    onRetry = onRetry,
    onOpenTransfer = onOpenTransfer,
    onBack = onBack,
    modifier = modifier,
)

/** [this] with no block in its form: the review keeps its blocks for its *Details*. */
private fun SendUiState.simple(): SendUiState = when (this) {
    is SendUiState.Compose -> copy(
        form = form.copy(
            assets = form.assets.map { it.copy(verifiedAt = null) },
            asset = form.asset?.copy(verifiedAt = null),
            recipient = form.recipient?.copy(resolvedAt = null),
            error = form.simpleError ?: form.error,
        ),
    )
    else -> this
}
