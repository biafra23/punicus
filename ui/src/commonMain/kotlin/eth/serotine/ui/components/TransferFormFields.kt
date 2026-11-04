package eth.serotine.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eth.serotine.domain.repository.FeeLevel

// The transfer form (docs/wallet-ux.md, screen 7) as Send and Send from a Safe draw it, and the
// pieces of their reviews they share.

/** Test tag of the transfer form's asset dropdown, for UI tests: its options are [ASSET_OPTION_TAG]'s. */
const val SEND_ASSET_TAG = "send-asset"

/** Test tag of the recipient field, for UI tests. */
const val SEND_TO_TAG = "send-to"

/** Test tag of the amount field, for UI tests. */
const val SEND_AMOUNT_TAG = "send-amount"

/** Test tag of the Review button, for UI tests. */
const val SEND_REVIEW_TAG = "send-review"

/** Test tag of the recipient's full address on the compose step and the review, for UI tests. */
const val SEND_RECIPIENT_TAG = "send-recipient"

/**
 * The transfer form's fields (docs/wallet-ux.md, screen 7) — from, asset, to and amount — as Send
 * and a Safe's proposal of a transfer both draw them, in the caller's column. The keyboard's Done
 * key on the amount does what [ReviewButton] does: [onReview].
 */
@Composable
internal fun TransferFormFields(
    form: TransferForm,
    onAssetChange: (String?) -> Unit,
    onToChange: (String) -> Unit,
    onPickAccount: (String) -> Unit,
    onRetryLookup: () -> Unit,
    onAmountChange: (String) -> Unit,
    onMax: () -> Unit,
    onReview: () -> Unit,
) {
    val amountField = remember { FocusRequester() }
    Heading("From")
    FromLine(form.from)
    Heading("Asset")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AssetDropdown(
            options = form.assets,
            selected = form.asset,
            placeholder = "Choose the asset to send",
            enabled = !form.quoting,
            tag = SEND_ASSET_TAG,
        ) { onAssetChange(it.contract) }
        form.assetNote?.let { Note(it) }
    }
    Heading("To")
    RecipientField(form, onToChange, amountField)
    form.recipient?.let { RecipientCard(it) }
    if (form.canRetryLookup) RetryLookupButton(form.retryLookupWaits, onRetryLookup)
    if (form.accounts.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            form.accounts.forEach { account ->
                AssistChip(
                    onClick = { onPickAccount(account.address) },
                    enabled = !form.quoting,
                    label = { Text("${account.label} · ${shortAddress(account.address)}") },
                    leadingIcon = { Blockie(account.address, size = 18.dp) },
                )
            }
        }
    }
    Heading("Amount")
    AmountField(form, amountField, onAmountChange, onMax, onReview)
}

/** Review, which quotes the form's transfer: "Checking on chain…" while it does. */
@Composable
internal fun ReviewButton(form: TransferForm, onReview: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onReview,
        enabled = form.canReview,
        modifier = modifier.testTag(SEND_REVIEW_TAG),
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

/**
 * What every asset picker says under an asset's symbol: its verified balance and, if it has one,
 * its warning. The asset dropdowns of the transfer and swap forms share it, so they word the
 * verification alike.
 */
@Composable
internal fun AssetBalanceLines(asset: AssetChoice) {
    Text(
        asset.verifiedAt?.let { "${asset.balance} · verified at $it" }
            ?: asset.age?.let { "${asset.balance} · $it" }
            ?: asset.balance,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    asset.warning?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun RecipientField(form: TransferForm, onToChange: (String) -> Unit, amountField: FocusRequester) {
    OutlinedTextField(
        value = form.to,
        onValueChange = onToChange,
        modifier = Modifier.fillMaxWidth().testTag(SEND_TO_TAG),
        enabled = !form.quoting,
        label = { Text(form.toLabel) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        singleLine = true,
        // The blockie once the text is an address, typed or resolved.
        leadingIcon = form.recipient?.let { recipient -> { Blockie(recipient.address, size = 24.dp) } },
        isError = form.toError,
        supportingText = { Text(form.toNote ?: form.toHint) },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
        ).next(),
        keyboardActions = nextTo(amountField),
    )
}

/** The recipient in full, with what is known of it: the name it was resolved from, its name in the wallet. */
@Composable
internal fun RecipientCard(recipient: RecipientItem) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountAvatar(recipient.address, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val title = listOfNotNull(recipient.name, recipient.label?.let { "$it, in your wallet" })
                if (title.isNotEmpty()) Text(title.joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                // In full: this is the address the user confirms, and what is signed.
                SelectionContainer {
                    Text(
                        recipient.address,
                        Modifier.testTag(SEND_RECIPIENT_TAG),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                recipient.resolvedAt?.let { Note(it) }
                recipient.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AmountField(
    form: TransferForm,
    focus: FocusRequester,
    onAmountChange: (String) -> Unit,
    onMax: () -> Unit,
    onReview: () -> Unit,
) {
    OutlinedTextField(
        value = form.amount,
        onValueChange = onAmountChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(SEND_AMOUNT_TAG),
        enabled = !form.quoting && form.asset != null,
        label = { Text(form.asset?.let { "Amount in ${it.symbol}" } ?: "Amount") },
        singleLine = true,
        trailingIcon = { TextButton(onClick = onMax, enabled = form.canMax) { Text("Max") } },
        isError = form.amountError != null,
        supportingText = (form.amountError ?: form.amountHint)?.let { text -> { Text(text) } },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Decimal).done(),
        keyboardActions = submitOnDone(form.canReview, onReview),
    )
}

/** The account a transfer is from: its blockie, its name and its short address. */
@Composable
internal fun FromLine(from: SendFrom) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AccountAvatar(from.address, size = 32.dp)
        Column {
            Text(from.label)
            Text(
                shortAddress(from.address),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** A section's heading on the transfer screens. */
@Composable
internal fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** A small note on the transfer screens. */
@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Fee: Slow | Normal | Fast (docs/wallet-ux.md, screen 7), from the verified fee history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeeChoice(fee: FeeLevel, enabled: Boolean, onFeeChange: (FeeLevel) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        FeeLevel.entries.forEachIndexed { index, level ->
            SegmentedButton(
                selected = fee == level,
                onClick = { onFeeChange(level) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, FeeLevel.entries.size),
                label = { Text(levelName(level)) },
            )
        }
    }
}

/**
 * Try again for a name that could not be resolved: [onRetry], or, while [waits] says why asking by
 * hand changes nothing (the light client is still syncing), greyed out, and a tap says so (owner
 * ruling, 2026-10-18, #55).
 */
@Composable
internal fun RetryLookupButton(waits: String?, onRetry: () -> Unit) {
    // Asked about this reason alone: once it is gone, a later one is never shown unasked.
    var asking by remember(waits) { mutableStateOf(false) }
    if (waits == null) {
        TextButton(onClick = onRetry) { Text("Try again") }
        return
    }
    TextButton(onClick = { asking = true }, modifier = Modifier.unavailable(true)) {
        Text("Try again", color = Unavailable.contentColor())
    }
    if (asking) UnavailableDialog("Try again", waits, onDismiss = { asking = false })
}
