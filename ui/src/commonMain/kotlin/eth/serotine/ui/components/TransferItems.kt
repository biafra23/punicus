package eth.serotine.ui.components

// What the transfer form (docs/wallet-ux.md, screen 7) and the reviews of a transfer show, display-
// ready: Send's and Send from a Safe's.

/**
 * The transfer form, display-ready (docs/wallet-ux.md, screen 7), as Send and Send from a Safe
 * fill it in: [from] is fixed; [assets] are the account's, its native currency and the tokens it
 * holds, each with its verified balance; [asset] the chosen one (null while the one asked for is
 * not known yet, or is not among them: [assetNote] says which). [to] is the recipient as typed,
 * [recipient] what it is once it parses as an address or resolves as a name, and [toNote] what the
 * field says under it — an error when [toError], with [canRetryLookup] when Try again is shown,
 * greyed out with [retryLookupWaits] while asking by hand changes nothing (the light client still
 * syncing, the name looked up again by itself). [accounts] are the wallet's other accounts, to pick one as the recipient. [amount] is typed
 * in token units ([amountError] why it is no amount), with [amountHint] its value in the display
 * currency (display only) and [canMax] when the whole verified balance can be filled in.
 * [quoting] while the quote for the review is made, [error] why the last one was refused, and
 * [canReview] when it can be asked for. [toLabel] and [toHint] are the recipient field's label
 * and the line under it while nothing is said of what is typed: a name only on a network that
 * resolves names. [simpleError] is [error] in simple mode's words, with no block, null where it is
 * the same (docs/simple-ui.md).
 */
data class TransferForm(
    val network: String,
    val from: SendFrom,
    val assets: List<AssetChoice>,
    val asset: AssetChoice?,
    val to: String,
    val accounts: List<AccountChoice>,
    val amount: String,
    val assetNote: String? = null,
    val recipient: RecipientItem? = null,
    val toNote: String? = null,
    val toError: Boolean = false,
    val canRetryLookup: Boolean = false,
    val retryLookupWaits: String? = null,
    val amountError: String? = null,
    val amountHint: String? = null,
    val canMax: Boolean = false,
    val quoting: Boolean = false,
    val error: String? = null,
    val canReview: Boolean = false,
    val toLabel: String = "Address or ENS name",
    val toHint: String = "An address, an ENS name, or one of your accounts below",
    val simpleError: String? = null,
)

/** The account sent from: its [label] and EIP-55 [address]. */
data class SendFrom(val label: String, val address: String)

/**
 * One asset the account can send: its [symbol], [name] and bundled [icon] (null: a monogram), a
 * token's EIP-55 [contract] (null for the network's currency), the [balance] held ("1.5 ETH",
 * or why there is none yet), the block it was verified at ([verifiedAt], "block 9,412,118"),
 * [warning] for a token the user added whose symbol is a built-in token's, and [age], how long ago
 * the balance was proved ("updated 2 min ago"), which simple mode shows in place of the block
 * (docs/simple-ui.md); null where not known.
 */
data class AssetChoice(
    val symbol: String,
    val name: String,
    val icon: String?,
    val balance: String,
    val contract: String? = null,
    val verifiedAt: String? = null,
    val warning: String? = null,
    val age: String? = null,
)

/** One of the wallet's accounts, offered as the recipient: its [label] and EIP-55 [address]. */
data class AccountChoice(val label: String, val address: String)

/**
 * The recipient: its EIP-55 [address], in full wherever it is confirmed; the ENS [name] it was
 * resolved from, if any, and [resolvedAt], the finalized block it was resolved at; its [label]
 * when it is one of the wallet's accounts; and [note], what to know about it ("The account you
 * send from").
 */
data class RecipientItem(
    val address: String,
    val name: String? = null,
    val resolvedAt: String? = null,
    val label: String? = null,
    val note: String? = null,
)

/**
 * One balance of the verified simulation: [what] ("ETH balance, which pays the fee"), [before] and
 * [after] it, and the block the balance before was verified at ([verifiedAt]); [short], what a line
 * naming its value after calls it ("ETH balance"), [what] by default.
 */
data class BalanceChange(
    val what: String,
    val before: String,
    val after: String,
    val verifiedAt: String,
    val short: String = what,
)

/**
 * The Safe's balance of the asset as the proposal leaves it: [what] ("ETH balance of the Safe"),
 * [before] it, what the transactions queued on this device before it may move of it ([queued], null:
 * nothing), [after] it, and the block the balance was verified at ([verifiedAt]).
 */
data class SafeBalanceChange(
    val what: String,
    val before: String,
    val after: String,
    val verifiedAt: String,
    val queued: String? = null,
)
