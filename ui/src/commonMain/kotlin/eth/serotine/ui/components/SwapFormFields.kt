package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Test tag of the field that opens the menu of the assets to sell, for UI tests. */
const val SWAP_SELL_TAG = "swap-sell"

/** Test tag of the field that opens the menu of the assets to buy, for UI tests. */
const val SWAP_BUY_TAG = "swap-buy"

/** Test tag of the amount field, for UI tests. */
const val SWAP_AMOUNT_TAG = "swap-amount"

/** Test tag of the tolerance field, for UI tests. */
const val SWAP_TOLERANCE_TAG = "swap-tolerance"

/** Test tag of the compose step's estimate of what the swap returns, for UI tests. */
const val SWAP_ESTIMATE_TAG = "swap-estimate"

/** Test tag of the compose step's exchange rate, for UI tests. */
const val SWAP_RATE_TAG = "swap-rate"

/** Test tag of the button that turns the exchange rate the other way round, for UI tests. */
const val SWAP_INVERT_RATE_TAG = "swap-invert-rate"

/**
 * The swap form (screen 22), display-ready: [from] is fixed; [sellOptions] the account's assets
 * with their verified balances, [sell] the chosen one; [buyOptions] the assets the wallet lists
 * (its native currency, the built-in tokens and those the user added), [buy] the chosen one;
 * [amount] typed in [sell]'s units ([amountError] why it is no amount), with [amountHint] its
 * value (display only) and [canMax]; [tolerance] the slippage tolerance typed as a percentage
 * ([toleranceError] why it is none). [note] says what holds the pair back, [quoting] while the
 * review is quoted, [error] why the last one was refused, [canReview] when it can be asked for.
 * [fixed] when the pair and the amount are set and only the tolerance may change: a Safe's swap
 * proposed again in place of one queued. [simpleError] is [error] in simple mode's words, with no
 * block, null where it is [error] itself. [estimate] is what the swap would return and its rate, from a
 * verified quote, once the pair and the amount are set; null until they are.
 */
data class SwapForm(
    val network: String,
    val from: SendFrom,
    val sellOptions: List<AssetChoice>,
    val sell: AssetChoice?,
    val buyOptions: List<AssetChoice>,
    val buy: AssetChoice?,
    val amount: String,
    val tolerance: String,
    val amountError: String? = null,
    val amountHint: String? = null,
    val canMax: Boolean = false,
    val toleranceError: String? = null,
    val note: String? = null,
    val quoting: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val canReview: Boolean = false,
    val fixed: Boolean = false,
    val simpleError: String? = null,
    val estimate: SwapEstimateItem? = null,
)

/**
 * What the swap being composed would return (docs/swaps.md, *The compose step's estimate*;
 * docs/simple-ui.md, W1), display-ready: [label] heads it ("You receive", or on CoW what Uniswap
 * would return); [receive] the verified quote's amount ("≈ 2499.98 USDC") and [minimum] the least
 * the swap may return at the tolerance typed; [rate] what one unit sold returns ("1 WETH ≈ 2499.98
 * USDC") and [inverseRate] the other way round; [impact] a warning when the amount moves the pools'
 * price far; [note] where the figures come from (its block) or why there are none, and
 * [simpleNote] the same in simple mode's words, with no block, null where it is [note] itself.
 * [checking] while the quote is read. Display only: the review quotes the swap again, and only what
 * it shows is signed.
 */
data class SwapEstimateItem(
    val label: String,
    val note: String,
    val receive: String? = null,
    val minimum: String? = null,
    val rate: String? = null,
    val inverseRate: String? = null,
    val impact: String? = null,
    val checking: Boolean = false,
    val simpleNote: String? = null,
)

/**
 * The swap form's fields (screen 22): the asset sold, the amount, the asset bought and the
 * tolerance; all but the tolerance held while [SwapForm.fixed]. Swap and a Safe's swap share them.
 * Each asset is chosen from a dropdown menu, which takes one line of the screen however many
 * assets the wallet lists.
 * The keyboard's Next key on the amount goes to the tolerance, whose Done does what their Review
 * button does: [onReview].
 */
@Composable
internal fun SwapFormFields(
    form: SwapForm,
    onSellChange: (String?) -> Unit,
    onBuyChange: (String?) -> Unit,
    onAmountChange: (String) -> Unit,
    onMax: () -> Unit,
    onToleranceChange: (String) -> Unit,
    onReview: () -> Unit,
) {
    val toleranceField = remember { FocusRequester() }
    Heading("Sell")
    val assetsEnabled = !form.quoting && !form.fixed
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AssetDropdown(
            options = form.sellOptions,
            selected = form.sell,
            placeholder = "Choose the asset to sell",
            enabled = assetsEnabled,
            tag = SWAP_SELL_TAG,
        ) { onSellChange(it.contract) }
        form.note?.let { Note(it) }
    }
    Heading("Amount")
    OutlinedTextField(
        value = form.amount,
        onValueChange = onAmountChange,
        modifier = Modifier.fillMaxWidth().testTag(SWAP_AMOUNT_TAG),
        enabled = !form.quoting && !form.fixed && form.sell != null,
        label = { Text(form.sell?.let { "Amount of ${it.symbol} to sell" } ?: "Amount") },
        singleLine = true,
        trailingIcon = { TextButton(onClick = onMax, enabled = form.canMax && !form.fixed) { Text("Max") } },
        isError = form.amountError != null,
        supportingText = (form.amountError ?: form.amountHint)?.let { text -> { Text(text) } },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Decimal).next(),
        keyboardActions = nextTo(toleranceField),
    )
    Heading("Buy")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AssetDropdown(
            options = form.buyOptions,
            selected = form.buy,
            placeholder = "Choose the asset to buy",
            enabled = assetsEnabled,
            tag = SWAP_BUY_TAG,
        ) { onBuyChange(it.contract) }
        Note("Only the assets your wallet lists: what a swap buys shows among the account's assets and can be sent.")
    }
    form.estimate?.let { SwapEstimateFields(it, pair = form.sell?.contract to form.buy?.contract) }
    Heading("Slippage tolerance")
    OutlinedTextField(
        value = form.tolerance,
        onValueChange = onToleranceChange,
        modifier = Modifier.fillMaxWidth().focusRequester(toleranceField).testTag(SWAP_TOLERANCE_TAG),
        enabled = !form.quoting,
        label = { Text("Tolerance in %") },
        singleLine = true,
        isError = form.toleranceError != null,
        supportingText = {
            Text(form.toleranceError ?: "How far below the verified quote the swap may return: 0.1 % to 5 %.")
        },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Decimal).done(),
        keyboardActions = submitOnDone(form.canReview, onReview),
    )
}

/**
 * What the swap would return, the least it may at the tolerance, and the exchange rate, which a
 * tap on the button beside it turns the other way round, until the [pair] changes; or that the
 * quote is being read, or why there is none.
 */
@Composable
private fun SwapEstimateFields(estimate: SwapEstimateItem, pair: Any?) {
    var inverted by remember(pair) { mutableStateOf(false) }
    Heading(estimate.label)
    Column(Modifier.testTag(SWAP_ESTIMATE_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (estimate.checking) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Checking on chain…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        estimate.receive?.let {
            SelectionContainer { Text(it, style = MaterialTheme.typography.headlineSmall) }
        }
        estimate.minimum?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        estimate.rate?.let { rate ->
            val inverse = estimate.inverseRate
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (inverted && inverse != null) inverse else rate,
                    Modifier.weight(1f, fill = false).testTag(SWAP_RATE_TAG),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (inverse != null) {
                    IconButton(onClick = { inverted = !inverted }, Modifier.testTag(SWAP_INVERT_RATE_TAG)) {
                        Icon(SerotineIcons.Swap, contentDescription = "Show the rate the other way round")
                    }
                }
            }
        }
        estimate.impact?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Note(estimate.note)
    }
}
