package eth.serotine.ui.swap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AmountLine
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.components.FeeChoice
import eth.serotine.ui.components.FromLine
import eth.serotine.ui.components.Heading
import eth.serotine.ui.components.HiddenDetails
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NotSentFields
import eth.serotine.ui.components.Note
import eth.serotine.ui.components.RouteSteps
import eth.serotine.ui.components.Spender
import eth.serotine.ui.components.SwapFormFields
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnderLightClientStrip
import eth.serotine.ui.components.UnderLightClientStripIfAny
import eth.serotine.ui.components.UnlockFields
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.mode.LocalInterfaceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/** Test tag of the Review button, for UI tests. */
const val SWAP_REVIEW_TAG = "swap-review"

/** Test tag of the reviewed minimum, for UI tests. */
const val SWAP_MINIMUM_TAG = "swap-minimum"

/** Test tag of the review's price impact, for UI tests. */
const val SWAP_IMPACT_TAG = "swap-impact"

/** Test tag of the wallet password field, for UI tests. */
const val SWAP_PASSWORD_TAG = "swap-password"

/** Test tag of the button that signs, for UI tests. */
const val SWAP_SIGN_TAG = "swap-sign"

/** Test tag of the button that opens the transaction holding the swap back, for UI tests. */
const val SWAP_EARLIER_TAG = "swap-earlier"

/** Test tag of the venue choice's CoW button, for UI tests. */
const val SWAP_VENUE_COW_TAG = "swap-venue-cow"

/** Test tag of the button that takes the same swap to Uniswap after CoW could not quote it, for UI tests. */
const val SWAP_UNISWAP_INSTEAD_TAG = "swap-uniswap-instead"

/** Test tag of the button that opens a CoW order's status, for UI tests. */
const val SWAP_OPEN_ORDER_TAG = "swap-open-order"

/** Test tag of the line that says the swap waits for its approval's block, for UI tests. */
const val SWAP_AWAITING_TAG = "swap-awaiting"

/** Test tag of the button that leaves the swap while its approval waits, for UI tests. */
const val SWAP_LATER_TAG = "swap-later"

/** Test tag of simple mode's Details button, for UI tests. */
const val SWAP_DETAILS_TAG = "swap-details"

/** Test tag of the button that leaves a placed CoW order's screen, for UI tests. */
const val SWAP_DONE_TAG = "swap-done"

/**
 * Binds [SwapScreen], or in simple mode [SimpleSwapScreen], to its ViewModel. A swap broadcast ends
 * the screen through [onSent] with its hash, and [onOpenTransfer] opens the status of a transaction
 * that holds the swap back (its approval, an earlier swap, one that may still be on its way);
 * [onOpenOrder] opens a CoW order's status, the one placed or an earlier one that holds the swap
 * back; the screen stopping being shown becomes [SwapViewModel.onHidden] (the password goes) and
 * being shown again [SwapViewModel.onShown] (the review is quoted anew).
 */
@Composable
fun SwapRoute(
    viewModel: SwapViewModel,
    /** The strip of the network this screen is about: its account's. */
    lightClient: StateFlow<LightClientItem>,
    onBack: () -> Unit,
    onSent: (hash: String) -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
    onOpenOrder: (uid: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Main.immediate: as on Send, an edit reaches its field in the same frame.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onShown() }
    val sent = (state as? SwapUiState.Sent)?.hash
    val currentOnSent by rememberUpdatedState(onSent)
    LaunchedEffect(sent) {
        if (sent != null) currentOnSent(sent)
    }
    // The same state in the view the user chose (docs/simple-ui.md).
    when (LocalInterfaceMode.current) {
        InterfaceMode.SIMPLE -> SimpleSwapScreen(
            state = state,
            onSellChange = viewModel::onSellChange,
            onBuyChange = viewModel::onBuyChange,
            onAmountChange = viewModel::onAmountChange,
            onMax = viewModel::onMax,
            onToleranceChange = viewModel::onToleranceChange,
            onFeeChange = viewModel::onFeeChange,
            onVenueChange = viewModel::onVenueChange,
            onSwapOnUniswap = viewModel::swapOnUniswap,
            onReview = viewModel::review,
            onEdit = viewModel::edit,
            onRefresh = viewModel::refresh,
            onPasswordChange = viewModel::onPasswordChange,
            onSign = viewModel::sign,
            onSignWithBiometrics = viewModel::signWithBiometrics,
            onRetry = viewModel::retry,
            onOpenTransfer = onOpenTransfer,
            onOpenOrder = onOpenOrder,
            onBack = onBack,
            modifier = modifier,
            syncLine = lightClient.collectAsStateWithLifecycle().value,
        )
        // The strip in Detailed view; in simple mode the same item, drawn as the sync line while it says something.
        InterfaceMode.DETAILED -> SwapScreen(
            state = state,
            chrome = SwapChrome.Strip(lightClient.collectAsStateWithLifecycle().value),
            onSellChange = viewModel::onSellChange,
            onBuyChange = viewModel::onBuyChange,
            onAmountChange = viewModel::onAmountChange,
            onMax = viewModel::onMax,
            onToleranceChange = viewModel::onToleranceChange,
            onFeeChange = viewModel::onFeeChange,
            onVenueChange = viewModel::onVenueChange,
            onSwapOnUniswap = viewModel::swapOnUniswap,
            onReview = viewModel::review,
            onEdit = viewModel::edit,
            onRefresh = viewModel::refresh,
            onPasswordChange = viewModel::onPasswordChange,
            onSign = viewModel::sign,
            onSignWithBiometrics = viewModel::signWithBiometrics,
            onRetry = viewModel::retry,
            onOpenTransfer = onOpenTransfer,
            onOpenOrder = onOpenOrder,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

/**
 * Swap (docs/swaps.md; screens 22 and 23) as a pure function of [state]: the compose step — the
 * venue, sell, buy, amount, tolerance and fee — then the review of the approval the swap needs
 * first, if any, and of the swap on Uniswap or the order on CoW, each with the unlock that signs it;
 * the wait for the approval's block between them (step 1 of 2, then step 2); and a CoW order once
 * handed to CoW. Its [chrome] is Detailed view's light-client strip and full reviews, or simple
 * mode's ([SimpleSwapScreen]): no strip, and the reviews' detail behind *Details*.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SwapScreen(
    state: SwapUiState,
    chrome: SwapChrome,
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
) {
    val review = state as? Signable
    // As on Send: back from a review is to the compose step, until bytes may be out there, and
    // waits while a signature runs.
    val toCompose = review != null && review.notSent?.canRetry != true
    BackHandler(enabled = review != null) {
        when {
            review?.signing == true -> Unit
            toCompose -> onEdit()
            else -> onBack()
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = if (toCompose) onEdit else onBack, enabled = review?.signing != true) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        when (state) {
                            is SwapUiState.ApprovalReview -> "Approve"
                            is SwapUiState.ApprovalPending -> "Swapping"
                            is SwapUiState.SwapReview -> "Review swap"
                            is SwapUiState.CowReview -> "Review order"
                            is SwapUiState.Sent -> "Sent"
                            is SwapUiState.OrderPlaced -> "Order"
                            else -> "Swap"
                        },
                    )
                },
                actions = {
                    Text(
                        state.network,
                        Modifier.padding(end = 16.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        },
    ) { padding ->
        val simple = chrome is SwapChrome.Simple
        val body: @Composable BoxScope.() -> Unit = {
            when (state) {
                is SwapUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SwapUiState.Sent -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("Sent", Modifier.padding(top = 12.dp))
                }
                is SwapUiState.CannotSwap -> Text(
                    state.message,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
                is SwapUiState.Compose -> ComposeContent(
                    state,
                    onSellChange,
                    onBuyChange,
                    onAmountChange,
                    onMax,
                    onToleranceChange,
                    onFeeChange,
                    onVenueChange,
                    onSwapOnUniswap,
                    onReview,
                    onOpenTransfer,
                    onOpenOrder,
                )
                is SwapUiState.ApprovalPending -> ReviewColumn {
                    Text(state.title, style = MaterialTheme.typography.titleLarge)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Waiting for a block to have it…", Modifier.testTag(SWAP_AWAITING_TAG))
                    }
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { onOpenTransfer(state.hash) }, Modifier.testTag(SWAP_EARLIER_TAG)) {
                        Text("Open the approval")
                    }
                    OutlinedButton(onClick = onBack, Modifier.align(Alignment.End).testTag(SWAP_LATER_TAG)) {
                        Text("Later")
                    }
                }
                is SwapUiState.ApprovalReview -> ReviewColumn {
                    StepLabel(state.step)
                    state.earlierFailed?.let { failed ->
                        Text(failed.note, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(
                            onClick = { onOpenTransfer(failed.hash) },
                            modifier = Modifier.testTag(SWAP_EARLIER_TAG),
                        ) {
                            Text("Open the earlier approval")
                        }
                    }
                    if (simple) SimpleApprovalContent(state) else ApprovalContent(state)
                    ReviewFooter(
                        state,
                        if (simple) "Confirm ${state.step.lowercase()}" else "Sign and send the approval",
                        onRefresh,
                        onPasswordChange,
                        onSign,
                        onSignWithBiometrics,
                        onRetry,
                    )
                }
                is SwapUiState.SwapReview -> ReviewColumn {
                    state.step?.let { StepLabel(it) }
                    if (simple) SimpleSwapContent(state) else SwapContent(state)
                    ReviewFooter(
                        state,
                        when {
                            !simple -> "Sign and swap"
                            state.step != null -> "Confirm ${state.step.lowercase()}"
                            else -> "Confirm swap"
                        },
                        onRefresh,
                        onPasswordChange,
                        onSign,
                        onSignWithBiometrics,
                        onRetry,
                    )
                }
                is SwapUiState.CowReview -> ReviewColumn {
                    state.step?.let { StepLabel(it) }
                    if (simple) SimpleCowContent(state) else CowContent(state)
                    if (state.uniswapGivesMore) {
                        OutlinedButton(
                            onClick = onSwapOnUniswap,
                            enabled = state.idle,
                            modifier = Modifier.testTag(SWAP_UNISWAP_INSTEAD_TAG),
                        ) {
                            Text("Swap on Uniswap instead")
                        }
                    }
                    ReviewFooter(
                        state,
                        when {
                            !simple -> "Sign and send to CoW"
                            state.step != null -> "Confirm ${state.step.lowercase()}"
                            else -> "Confirm order"
                        },
                        onRefresh,
                        onPasswordChange,
                        onSign,
                        onSignWithBiometrics,
                        onRetry,
                    )
                }
                is SwapUiState.OrderPlaced -> ReviewColumn {
                    Text(state.title, style = MaterialTheme.typography.titleLarge)
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    Heading("Order")
                    SelectionContainer {
                        Text(state.uid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { onOpenOrder(state.uid) }, Modifier.testTag(SWAP_OPEN_ORDER_TAG)) {
                        Text("Open the order")
                    }
                    Button(onClick = onBack, Modifier.align(Alignment.End).testTag(SWAP_DONE_TAG)) { Text("Done") }
                }
            }
        }
        when (chrome) {
            is SwapChrome.Strip -> UnderLightClientStrip(chrome.item, Modifier.padding(padding), body)
            is SwapChrome.Simple -> UnderLightClientStripIfAny(null, Modifier.padding(padding), chrome.sync, body)
        }
    }
}

/**
 * What Swap shows of its network's light client and how it reviews: Detailed view's [Strip] of
 * [Strip.item] with the full reviews, or simple mode's [Simple], with neither the strip nor a block
 * in sight and each review's detail behind *Details* (docs/simple-ui.md, W1 to W4).
 */
sealed interface SwapChrome {
    /** Detailed view: the light client's strip and the full reviews. */
    data class Strip(val item: LightClientItem) : SwapChrome

    /** Simple mode: no strip but the [sync] line while it has something to say, and the simple reviews. */
    data class Simple(val sync: LightClientItem? = null) : SwapChrome
}

@Composable
private fun ComposeContent(
    state: SwapUiState.Compose,
    onSellChange: (String?) -> Unit,
    onBuyChange: (String?) -> Unit,
    onAmountChange: (String) -> Unit,
    onMax: () -> Unit,
    onToleranceChange: (String) -> Unit,
    onFeeChange: (FeeLevel) -> Unit,
    onVenueChange: (SwapVenue) -> Unit,
    onSwapOnUniswap: () -> Unit,
    onReview: () -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
    onOpenOrder: (uid: String) -> Unit,
) {
    val form = state.form
    val cow = state.venue.chosen == SwapVenue.COW
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("From")
        FromLine(form.from)
        Heading("Swap on")
        VenueChoice(state.venue, enabled = !form.quoting, onVenueChange)
        Note(
            if (cow) {
                "Through CoW's servers, which learn this swap. CoW's estimate is shown beside the verified " +
                    "Uniswap quote, and the order never signs less than that quote, less its fee and the tolerance."
            } else {
                "Verified on chain: Uniswap's pools, read through the light client. Nothing goes to a server."
            },
        )
        SwapFormFields(form, onSellChange, onBuyChange, onAmountChange, onMax, onToleranceChange, onReview = onReview)
        Heading("Fee")
        FeeChoice(state.fee, enabled = !form.quoting, onFeeChange)
        Note(
            if (cow) {
                "A CoW order pays its network fee out of the trade. This fee is for an approval, if one is needed."
            } else {
                "The fee is paid in the network's own currency; the review shows the most it can be."
            },
        )
        form.info?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.uniswapInstead) {
            OutlinedButton(onClick = onSwapOnUniswap, Modifier.testTag(SWAP_UNISWAP_INSTEAD_TAG)) {
                Text("Swap on Uniswap instead")
            }
        }
        state.liveOrder?.let { uid ->
            OutlinedButton(onClick = { onOpenOrder(uid) }, Modifier.testTag(SWAP_OPEN_ORDER_TAG)) {
                Text("Open the order")
            }
        }
        state.earlier?.let { earlier ->
            OutlinedButton(onClick = { onOpenTransfer(earlier.hash) }, Modifier.testTag(SWAP_EARLIER_TAG)) {
                Text(earlier.label)
            }
        }
        Button(
            onClick = onReview,
            enabled = form.canReview,
            modifier = Modifier.align(Alignment.End).testTag(SWAP_REVIEW_TAG),
        ) {
            if (form.quoting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Checking on chain…")
            } else {
                Text("Review")
            }
        }
    }
}

/**
 * The venues a swap can go to: Uniswap and CoW Protocol, the [venue] chosen selected. CoW while it
 * is not offered is greyed out, and a tap on it says why (CLAUDE.md, *Conventions*).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VenueChoice(venue: VenueItem, enabled: Boolean, onVenueChange: (SwapVenue) -> Unit) {
    var notice by remember { mutableStateOf<String?>(null) }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = venue.chosen == SwapVenue.UNISWAP,
            onClick = { onVenueChange(SwapVenue.UNISWAP) },
            enabled = enabled,
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            label = { Text("Uniswap") },
        )
        val cowUnavailable = venue.cowUnavailable
        SegmentedButton(
            selected = venue.chosen == SwapVenue.COW,
            onClick = { if (cowUnavailable != null) notice = cowUnavailable else onVenueChange(SwapVenue.COW) },
            enabled = enabled,
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            modifier = Modifier.testTag(SWAP_VENUE_COW_TAG).unavailable(cowUnavailable != null),
            label = {
                Text(
                    "CoW Protocol",
                    color = if (cowUnavailable != null) Unavailable.contentColor() else Color.Unspecified,
                )
            },
        )
    }
    notice?.let { reason -> UnavailableDialog("CoW Protocol", reason, onDismiss = { notice = null }) }
}

/**
 * The order on CoW: what it sells, CoW's estimate beside the verified Uniswap net, the minimum it
 * signs and until when, and what CoW learns.
 */
@Composable
private fun CowContent(state: SwapUiState.CowReview) {
    Heading("You sell")
    AmountLine(state.sell.icon, state.sell.symbol, state.sold)
    Heading("CoW's estimate, not verified")
    AmountLine(state.buy.icon, state.buy.symbol, state.estimate)
    Note(state.estimateNote)
    Heading("Uniswap, verified")
    AmountLine(state.buy.icon, state.buy.symbol, state.uniswapNet)
    Note(state.uniswapNote)
    Text(
        state.comparison,
        color = if (state.uniswapGivesMore) MaterialTheme.colorScheme.error else Color.Unspecified,
        style = MaterialTheme.typography.bodyMedium,
    )
    Heading("You receive")
    SelectionContainer {
        Text(state.minimum, Modifier.testTag(SWAP_MINIMUM_TAG), style = MaterialTheme.typography.titleMedium)
    }
    Note(state.minimumNote)
    Note(state.validUntil)
    Heading("Settled by")
    Spender(state.settlement)
    Heading("From, and paid to")
    FromLine(state.from)
    Heading("Network fee")
    Text("None from this account: CoW takes it out of the trade, as the estimate says.")
    Heading("Verified balance")
    state.balances.forEach { Balance(it) }
    state.pending?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Heading("What CoW learns")
    Note(state.disclosure)
}

@Composable
private fun ReviewColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

/** The approval the swap needs first: the token, the amount, the router it lets take it. */
@Composable
private fun ApprovalContent(state: SwapUiState.ApprovalReview) {
    Text(
        "Before the swap, ${state.spender.name} needs your approval to take the ${state.token.symbol} it sells: " +
            "its own transaction, for this amount exactly.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Heading("Allow")
    Spender(state.spender)
    Heading("To take")
    AmountLine(state.token.icon, state.token.symbol, state.amount)
    Note("It may take ${state.allowance} now.")
    state.resetNote?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Heading("From")
    FromLine(state.from)
    Fee(state.maxFee, state.feeDetail)
    Heading("Verified simulation")
    Balance(state.balance)
    Text(state.simulation, style = MaterialTheme.typography.bodyMedium)
}

/** The swap: what it sells, what it returns and at least, through which pools, until when. */
@Composable
private fun SwapContent(state: SwapUiState.SwapReview) {
    Heading("You sell")
    AmountLine(state.sell.icon, state.sell.symbol, state.sold)
    Heading("You receive")
    AmountLine(state.buy.icon, state.buy.symbol, state.receive)
    Note(state.ranAt)
    SelectionContainer {
        Text(
            state.minimum,
            Modifier.testTag(SWAP_MINIMUM_TAG),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    Note(state.tolerance)
    Heading("Route")
    RouteSteps(state.route)
    Note(state.routeNote)
    state.netNote?.let { Note(it) }
    Text(state.priceImpact, Modifier.testTag(SWAP_IMPACT_TAG), style = MaterialTheme.typography.bodyMedium)
    state.priceHint?.let { Note(it) }
    Heading("Through")
    Spender(state.spender)
    state.allowanceNote?.let { Note(it) }
    Note(state.deadline)
    Heading("From, and paid to")
    FromLine(state.from)
    Fee(state.maxFee, state.feeDetail)
    Heading("Verified simulation")
    state.balances.forEach { Balance(it) }
    state.pending?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun Fee(maxFee: String, detail: String) {
    Heading("Network fee")
    Text("At most $maxFee")
    Note(detail)
}

@Composable
private fun Balance(change: BalanceChange) {
    Column {
        Text(change.what, style = MaterialTheme.typography.labelLarge)
        Text("${change.before} → ${change.after}")
        Note(change.verifiedAt)
    }
}

/** Refresh, then the unlock that signs, or what became of a signature not broadcast. */
@Composable
private fun ReviewFooter(
    state: Signable,
    signLabel: String,
    onRefresh: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
    onRetry: () -> Unit,
) {
    TextButton(onClick = onRefresh, enabled = state.idle) {
        if (state.refreshing) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
        }
        Spacer(Modifier.size(8.dp))
        Text("Refresh the quote")
    }
    HorizontalDivider()
    val notSent = state.notSent
    if (notSent != null) {
        NotSentFields(notSent, state.signing, onRetry)
    } else {
        UnlockFields(
            unlock = state.unlock,
            signing = state.signing,
            canSign = state.canSign,
            canSignWithBiometrics = state.canSignWithBiometrics,
            signLabel = signLabel,
            signingLabel = "Signing and sending…",
            onPasswordChange = onPasswordChange,
            onSign = onSign,
            onSignWithBiometrics = onSignWithBiometrics,
            passwordTag = SWAP_PASSWORD_TAG,
            signTag = SWAP_SIGN_TAG,
        )
    }
}

/** Which step of the swap a review is ("Step 1 of 2"): said above it in either view. */
@Composable
private fun StepLabel(step: String) {
    Text(step, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/** A line of a simple review: what it is, and its value at the end. */
@Composable
private fun ReviewLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A check made on chain, said in words (docs/simple-ui.md, W2). */
@Composable
private fun Checked(text: String) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** A simple review's detail, behind *Details*: the routes, the blocks, the fee's make-up. */
@Composable
private fun Details(content: @Composable ColumnScope.() -> Unit) = HiddenDetails(SWAP_DETAILS_TAG, content)

/**
 * The approval in simple mode (docs/simple-ui.md, W2): what it allows, for this swap only, and its
 * fee, with step 2 named after it; the router's address, the allowance now and the simulation
 * behind *Details*.
 */
@Composable
private fun SimpleApprovalContent(state: SwapUiState.ApprovalReview) {
    Text(state.headline, style = MaterialTheme.typography.titleLarge)
    Text("${state.scope} Fee at most ${state.maxFee}.", style = MaterialTheme.typography.bodyMedium)
    state.resetNote?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Heading("From")
    FromLine(state.from)
    Checked("Checked on chain: this approval should go through. It moves nothing.")
    Text(state.next, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Details {
        Spender(state.spender)
        Note("It may take ${state.allowance} now.")
        Note(state.feeDetail)
        Balance(state.balance)
        Note(state.simulation)
    }
}

/**
 * The swap on Uniswap in simple mode (docs/simple-ui.md, W2 and W3): what it sells for at least
 * what, the price checked on chain, the tolerance and the price impact, the fee and the balances
 * after; the route, the router, the deadline and the blocks behind *Details*.
 */
@Composable
private fun SimpleSwapContent(state: SwapUiState.SwapReview) {
    AmountLine(state.sell.icon, state.sell.symbol, state.sold)
    Text("for at least", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TokenIcon(state.buy.icon, state.buy.symbol, size = 40.dp)
        SelectionContainer {
            Text(state.minimum, Modifier.testTag(SWAP_MINIMUM_TAG), style = MaterialTheme.typography.headlineSmall)
        }
    }
    Note(state.tolerance)
    Checked("Price checked on chain")
    Text(state.priceImpact, Modifier.testTag(SWAP_IMPACT_TAG), style = MaterialTheme.typography.bodyMedium)
    state.priceHint?.let { Note(it) }
    Heading("From, and paid to")
    FromLine(state.from)
    ReviewLine("Network fee, at most", state.maxFee)
    state.balances.forEach { ReviewLine("${it.short} after", it.after) }
    state.pending?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Details {
        Heading("Expected")
        AmountLine(state.buy.icon, state.buy.symbol, state.receive)
        Note(state.ranAt)
        Heading("Route")
        RouteSteps(state.route)
        Note(state.routeNote)
        state.netNote?.let { Note(it) }
        Heading("Through")
        Spender(state.spender)
        state.allowanceNote?.let { Note(it) }
        Note(state.deadline)
        Note(state.feeDetail)
        state.balances.forEach { Balance(it) }
    }
}

/**
 * The order on CoW in simple mode (docs/simple-ui.md, W4): what it sells for at least what, CoW's
 * estimate marked as not checked on chain beside the verified Uniswap quote, and what CoW learns
 * (owner ruling, 2026-10-22); the settlement contract, the validity and the balances behind *Details*.
 */
@Composable
private fun SimpleCowContent(state: SwapUiState.CowReview) {
    AmountLine(state.sell.icon, state.sell.symbol, state.sold)
    Text("for at least", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TokenIcon(state.buy.icon, state.buy.symbol, size = 40.dp)
        SelectionContainer {
            Text(state.minimum, Modifier.testTag(SWAP_MINIMUM_TAG), style = MaterialTheme.typography.headlineSmall)
        }
    }
    ReviewLine("CoW expects, not checked on chain", state.estimate)
    ReviewLine("Uniswap, checked on chain", state.uniswapNet)
    Text(
        state.comparison,
        color = if (state.uniswapGivesMore) MaterialTheme.colorScheme.error else Color.Unspecified,
        style = MaterialTheme.typography.bodyMedium,
    )
    Checked("Checked on chain: the minimum is never below what Uniswap gives, less the gas and your tolerance")
    Text(
        "No network fee from this account: CoW takes it out of the trade.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Heading("From, and paid to")
    FromLine(state.from)
    state.pending?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Heading("What CoW learns")
    Note(state.disclosure)
    Details {
        Note(state.estimateNote)
        Note(state.uniswapNote)
        Note(state.minimumNote)
        Note(state.validUntil)
        Heading("Settled by")
        Spender(state.settlement)
        state.balances.forEach { Balance(it) }
    }
}

