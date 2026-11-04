package eth.serotine.domain.model

/**
 * How the wallet presents what it knows (owner's decisions, 2026-10-26; docs/simple-ui.md): the
 * same values, verified the same way, in one of two views.
 */
enum class InterfaceMode {
    /** The default in every build: ages instead of blocks, a freshness line instead of the strip. */
    SIMPLE,

    /** Detailed view, opt-in in Settings: the light client's state, blocks and proofs on every screen. */
    DETAILED,
}
