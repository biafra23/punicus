package eth.serotine.domain.model

/**
 * What the wallet lets the user do while a light client catches up (owner rulings, 2026-11-06;
 * docs/lenient-mode.md): the same verified values either way, and nothing ever sent before a
 * check at a fresh verified head.
 */
enum class SyncPolicy {
    /** The default in every build: what needs fresh state waits for it, as it always did. */
    STRICT,

    /**
     * Settings → Advanced → *Sign while syncing*: a transaction may be drafted and signed on the
     * state last verified, labelled with its age, and waits in the outbox for the check.
     */
    LENIENT,
}
