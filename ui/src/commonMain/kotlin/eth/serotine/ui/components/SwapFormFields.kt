package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * block, null where it is [error] itself.
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
