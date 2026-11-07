package eth.serotine.ui.components

import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.CallRefusal
import eth.serotine.domain.usecase.SafeDeploymentRefusal
import eth.serotine.domain.usecase.SafeTxRefusal

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

/**
 * Whether a quote was refused because the light client is still syncing, at whatever depth the
 * refusal says it: its own "syncing", or a figure unavailable while syncing. The note of an action
 * that stays strict follows every one of them, never the top-level one alone.
 */
internal val CallRefusal.whileSyncing: Boolean
    get() = this == CallRefusal.Syncing || (this as? CallRefusal.Unavailable)?.reason == ReadUnavailable.SYNCING

/** [CallRefusal.whileSyncing] of a Safe transaction's refusal: its own, the Safe's, or its quote's. */
internal val SafeTxRefusal.whileSyncing: Boolean
    get() = when (this) {
        SafeTxRefusal.Syncing -> true
        is SafeTxRefusal.Unavailable -> reason == ReadUnavailable.SYNCING
        is SafeTxRefusal.SafeUnavailable -> reason == ReadUnavailable.SYNCING
        is SafeTxRefusal.NotQuoted -> refusal.whileSyncing
        else -> false
    }

/** [CallRefusal.whileSyncing] of a Safe deployment's refusal: its own, or its payer's quote's. */
internal val SafeDeploymentRefusal.whileSyncing: Boolean
    get() = when (this) {
        SafeDeploymentRefusal.Syncing -> true
        is SafeDeploymentRefusal.Unavailable -> reason == ReadUnavailable.SYNCING
        is SafeDeploymentRefusal.NotQuoted -> refusal.whileSyncing
        else -> false
    }
