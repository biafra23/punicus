package eth.serotine.ui.activity

/**
 * Everything an account's Activity renders, the Activity tab of its detail (docs/wallet-ux.md,
 * screen 25; issue #85): the transactions this device sent for it and, for a Safe, its own events
 * on chain, by date, newest first unless the user turned the order round ([ordered]). Display-ready.
 */
sealed interface ActivityUiState {

    /** Nothing read yet. */
    data object Loading : ActivityUiState

    /**
     * The [rows], by date, newest first as built (none: nothing listed yet, which [empty] says),
     * and [coverage], what the list covers and what it leaves out; [simpleCoverage], the same in
     * simple mode's words, with no block number (docs/simple-ui.md, H5: "Activity since you added
     * this account"). Above them, the swaps whose next step [waiting] for the user, each with
     * Continue, in the same place whichever way the rows run; and first of all, what the account has
     * [queued] in lenient mode ("Waiting to send", "Needs your signature again";
     * docs/lenient-mode.md, *Where it shows*), each opening its status.
     */
    data class Shown(
        val rows: List<ActivityRow>,
        val coverage: String,
        val empty: String = "Nothing sent from this device yet.",
        val simpleCoverage: String = coverage,
        val waiting: List<WaitingStep> = emptyList(),
        val queued: List<ActivityRow> = emptyList(),
    ) : ActivityUiState

    /** The history cannot be read right now, for the reason [message] gives. */
    data class Unavailable(val message: String) : ActivityUiState
}

/**
 * One transaction sent from this device, or one event of a Safe on chain: its [key] in the list,
 * what it did ([title]: "Sent 0.5 ETH", "Received 0.1 ETH"), who or what else it concerns
 * ([detail]: "To Bob · 0x12…5678", "Treasury · Safe nonce 4"), the EIP-55 address its blockie is of
 * ([about]), when it happened ([time]: when a peer took a sent transaction, or when an event's block
 * was produced as its verified header says, and the block alone, "Block 9,415,302", from an engine
 * that does not report the time), and how it ended as this device saw it ([status], "In block
 * 9,412,118", and its [state]). [opens] is what a tap opens the detail of (docs/wallet-ux.md,
 * screen 26): the transaction, and for an event its log. Simple mode says no block number
 * (docs/simple-ui.md, H5): [simpleTime] is when, where that is known (null for an event dated by its
 * block alone), and [simpleStatus] how it ended in words ("Confirmed", "Final", "Failed"), null where
 * that is [status] itself: so a copy that changes [status] (a caution, a warning) never leaves an
 * older simple status behind.
 */
data class ActivityRow(
    val key: String,
    val title: String,
    val detail: String,
    val about: String,
    val time: String,
    val status: String,
    val state: ActivityState,
    val opens: ActivityKey,
    val simpleTime: String? = time,
    val simpleStatus: String? = null,
)

/**
 * Which row of an account's Activity a detail is of: the transaction [hash] (`0x` and 64 lower-case
 * hex digits), and for an event of the log index the [logIndex] of its log in the block; null for a
 * transaction sent from this device. For a CoW order signed here ([cowOrder]), [hash] is its UID,
 * and a tap opens the order's status instead; for a transfer queued in lenient mode ([queued]), its
 * id, and a tap opens its status (docs/wallet-ux.md, screen 9).
 */
data class ActivityKey(
    val hash: String,
    val logIndex: Int? = null,
    val cowOrder: Boolean = false,
    val queued: Boolean = false,
)

/**
 * A swap whose next step waits for the user (docs/simple-ui.md, H5): what it swaps ([title], "Swap 40
 * USDC → ETH"), where it stands ([message], "Step 1 done. Step 2 is waiting for you.", said from the
 * approval's verified receipt and never claimed without one), and the token it sells ([sell], its
 * address), which Continue opens Swap with and Dismiss lets go of. In words, with no block number, in
 * both views.
 */
data class WaitingStep(val title: String, val message: String, val sell: String)

/** How a sent transaction ended, as an [ActivityRow] marks it. */
enum class ActivityState {
    /** In a block, its receipt verified, and it succeeded (finalized or not yet). */
    DONE,

    /** No block seen to include it yet. */
    WAITING,

    /** In a block, and it reverted: its fee paid, nothing else done. */
    FAILED,

    /** No peer confirmed its broadcast, and no block seen to include it. */
    UNCONFIRMED,

    /** Its nonce was used while the app was not watching: by it, unless another took its place. */
    UNKNOWN,

    /** Done on chain, and what it did can move a Safe's funds without its owners. */
    WARNING,

    /**
     * Done on chain, received from an address the user has not sent to and that is none of their
     * accounts: it may be a look-alike, planted for them to copy.
     */
    CAUTION,
}

/**
 * Which way an Activity's rows run (docs/wallet-ux.md, screen 25): the top bar's menu offers the
 * other one while the tab is shown.
 */
enum class ActivityOrder {
    /** The newest row on top: the default. */
    NEWEST_FIRST,

    /** The oldest row on top. */
    OLDEST_FIRST,
}

/**
 * This Activity with its rows running the way [order] says: as built for [ActivityOrder.NEWEST_FIRST],
 * turned round for [ActivityOrder.OLDEST_FIRST]. The cards of the swaps waiting for a step stay on top.
 */
fun ActivityUiState.ordered(order: ActivityOrder): ActivityUiState = when {
    order == ActivityOrder.NEWEST_FIRST || this !is ActivityUiState.Shown -> this
    else -> copy(rows = rows.asReversed())
}

/**
 * This Activity in simple mode's words (docs/simple-ui.md, H5): each row's [ActivityRow.simpleTime]
 * (none for an event dated only by its block) and [ActivityRow.simpleStatus] in place of its time
 * and status, and [ActivityUiState.Shown.simpleCoverage] in place of the coverage.
 */
internal fun ActivityUiState.simple(): ActivityUiState = when (this) {
    is ActivityUiState.Shown -> copy(
        rows = rows.map { it.copy(time = it.simpleTime.orEmpty(), status = it.simpleStatus ?: it.status) },
        queued = queued.map { it.copy(time = it.simpleTime.orEmpty(), status = it.simpleStatus ?: it.status) },
        coverage = simpleCoverage,
    )
    else -> this
}
