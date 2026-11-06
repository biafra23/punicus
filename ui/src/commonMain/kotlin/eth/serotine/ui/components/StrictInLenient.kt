package eth.serotine.ui.components

/**
 * What an action that stays strict adds to its "not synced yet" in lenient mode (docs/lenient-mode.md,
 * the action table; owner rulings, 2026-11-06): that *Sign while syncing* does not reach it, and why,
 * so the user does not wait for a draft that never comes. [what] names it, [why] says why in a few
 * words. A leading space: it follows the refusal's own sentence.
 */
internal fun strictInLenientNote(what: String, why: String): String =
    " Sign while syncing does not apply to $what: $why."

/** The note of a swap, on Uniswap or CoW, and of its token approval: its amounts come from a fresh quote. */
internal val SWAPS_STAY_STRICT = strictInLenientNote("swaps", "the amounts they sign come from a fresh quote")

/** The note of a wrap or an unwrap: strict for now, to keep the first version small. */
internal val WRAPS_STAY_STRICT = strictInLenientNote("wraps and unwraps", "they stay strict for now")

/** The note of a new Safe: strict for now. */
internal val CREATE_SAFE_STAYS_STRICT = strictInLenientNote("creating a Safe", "it stays strict for now")

/** The note of a dApp's request: the dApp waits for an answer it would get too late. */
internal val DAPP_REQUESTS_STAY_STRICT = strictInLenientNote(
    "a dApp's requests",
    "the dApp waits for its answer, and a queued one would come too late",
)
