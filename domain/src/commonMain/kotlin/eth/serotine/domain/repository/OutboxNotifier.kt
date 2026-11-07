package eth.serotine.domain.repository

/**
 * The port through which the user hears what became of a queued transaction, or of a held Safe
 * signature, while the app is not in front (docs/lenient-mode.md, *The outbox*, *Safe transactions
 * in lenient mode*): the platform's notifications. Each host implements it with its own, as
 * [UpgradeNotifier]; the decision what to say and when is the domain's (`FollowOutbox`). What it is
 * told is public once sent, and the conflict's reason; never the signed bytes of a transaction not
 * sent, nor a held signature not checked yet: one told of a conflict has none, and one told checked
 * is in the Safe's queue, public data, by then. A notifier shows what it does and where, never a
 * signature.
 */
interface OutboxNotifier {
    /**
     * [queued] was handed to the network as [hash] (a peer took it, or none answered and it may have
     * reached one: its status says which). Never throws: where the platform cannot show it, it is
     * dropped; the app's own screens still say it.
     */
    fun sent(queued: QueuedTransaction, hash: String)

    /** [queued] no longer stands, for [conflict]: its signature was deleted, and it asks to be signed again. */
    fun conflict(queued: QueuedTransaction, conflict: QueueConflict)

    /**
     * [held] passed its check at a fresh verified head: it is in the Safe's queue now, and its QR code
     * and text are offered there. Never throws.
     */
    fun heldChecked(held: HeldSafeSignature)

    /**
     * [held] no longer stands, for [conflict]: its signature was deleted, and it was never shown. A
     * proposal asks to be signed again; a signer no longer an owner is discarded. Never throws.
     */
    fun heldConflict(held: HeldSafeSignature, conflict: HeldConflict)

    companion object {
        /** Tells nothing: a host without notifications, or a test. */
        val None: OutboxNotifier = object : OutboxNotifier {
            override fun sent(queued: QueuedTransaction, hash: String) = Unit

            override fun conflict(queued: QueuedTransaction, conflict: QueueConflict) = Unit

            override fun heldChecked(held: HeldSafeSignature) = Unit

            override fun heldConflict(held: HeldSafeSignature, conflict: HeldConflict) = Unit
        }
    }
}
