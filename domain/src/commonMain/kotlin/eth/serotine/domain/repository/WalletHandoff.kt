package eth.serotine.domain.repository

/**
 * The port that hands a payment link (`PaymentLinks`) to another wallet app on this device, for
 * Send from another wallet (issue #255). It is the platform's own output, so the host implements
 * it: on Android, a view intent for the link's `ethereum:` scheme, which shows the system's
 * chooser when several wallets take it. A host whose platform offers no such handoff answers
 * [HandoffAvailability.NOT_ON_THIS_DEVICE]. Nothing comes back through it: what became of the
 * transfer is read from the chain alone. Never throws.
 */
interface WalletHandoff {

    /**
     * Whether a link can be handed over now: a wallet app other than Serotine takes `ethereum:`
     * links ([HandoffAvailability.AVAILABLE]), none does ([HandoffAvailability.NO_WALLET]), or this
     * platform hands none over at all. Asked anew each time, since a wallet may be installed or
     * removed meanwhile; quick, and safe to call from the main thread.
     */
    fun availability(): HandoffAvailability

    /**
     * Opens [link], an `ethereum:` payment link, in another wallet app. [HandoffOpening.OPENED] once
     * the platform has handed it over (the user may still pick no app in the chooser, which this
     * cannot tell); [HandoffOpening.NO_WALLET] when no app takes it; [HandoffOpening.REFUSED] when
     * the app that takes it would not be opened (a handler guarded by a permission Serotine lacks).
     */
    suspend fun open(link: String): HandoffOpening
}

/** What [WalletHandoff.availability] answers. */
enum class HandoffAvailability {
    /** A wallet app other than Serotine takes `ethereum:` links on this device. */
    AVAILABLE,

    /** No app on this device takes `ethereum:` links: one has to be installed first. */
    NO_WALLET,

    /** This platform hands no payment link over (the desktop, and for now iOS). */
    NOT_ON_THIS_DEVICE,
}

/** What [WalletHandoff.open] answers. */
enum class HandoffOpening {
    /** Handed to the platform, which opened a wallet or its chooser. */
    OPENED,

    /** No app took the link: nothing was handed over. */
    NO_WALLET,

    /** An app takes the link but the platform would not open it: nothing was handed over. */
    REFUSED,
}
