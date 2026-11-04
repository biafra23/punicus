package eth.serotine.domain.model

/**
 * An Ethereum network Serotine works on, with its EIP-155 [chainId] and its native
 * currency's [nativeSymbol] (the chain registry's), [nativeName], [nativeIcon] (the key of its
 * bundled icon) and [nativePriceId], the price id of its feed; [testnet] when its prices are
 * a testnet's, which have no market value (docs/wallet-ux.md, *Prices*); [secondsPerSlot], the
 * length of its beacon slot, at most one block each; [ensNames] when ENS names are resolved on
 * it, on its own registry (Gnosis Chain has none: names are not resolved there, nor on another
 * chain for it, until the owner rules on them; docs/networks.md, *Names*). Ethereum and Gnosis Chain beside Sepolia,
 * one chain per account (owner ruling, 2026-10-22; CLAUDE.md, *Data sources*;
 * docs/networks.md), in the order the screens list them; adding another is the owner's decision.
 */
enum class Network(
    val chainId: Long,
    val displayName: String,
    val nativeSymbol: String,
    val nativeName: String,
    val nativeIcon: String,
    val nativePriceId: PriceId,
    val testnet: Boolean,
    val secondsPerSlot: Long,
    val ensNames: Boolean,
) {
    /** Ethereum itself, mainnet: real funds; its native currency is ether. */
    ETHEREUM(
        chainId = 1,
        displayName = "Ethereum",
        nativeSymbol = "ETH",
        nativeName = "Ether",
        nativeIcon = "eth",
        nativePriceId = PriceId("ETH"),
        testnet = false,
        secondsPerSlot = 12,
        ensNames = true,
    ),

    /**
     * Gnosis Chain, its own beacon chain at 5-second slots: real funds; its native currency is
     * xDAI, priced by its own feed's id (docs/networks.md, open question 3: Gnosis's DAI / USD
     * feed, there being no xDAI / USD one).
     */
    GNOSIS(
        chainId = 100,
        displayName = "Gnosis Chain",
        nativeSymbol = "XDAI",
        nativeName = "xDAI",
        nativeIcon = "xdai",
        nativePriceId = PriceId("XDAI"),
        testnet = false,
        secondsPerSlot = 5,
        ensNames = false,
    ),

    /** Ethereum's long-lived public testnet; its native currency is (Sepolia) ether. */
    SEPOLIA(
        chainId = 11_155_111,
        displayName = "Sepolia",
        nativeSymbol = "ETH",
        nativeName = "Ether",
        nativeIcon = "eth",
        nativePriceId = PriceId("ETH"),
        testnet = true,
        secondsPerSlot = 12,
        ensNames = true,
    ),
    ;

    /**
     * How long one sync-committee period lasts, the unit the light client's weak-subjectivity
     * bound is counted in: 8,192 slots on each network (256 epochs of 32 slots on Ethereum and
     * Sepolia, 512 of 16 on Gnosis Chain), so about 27.3 hours, or 11.4 on Gnosis Chain.
     */
    val syncCommitteePeriodSeconds: Long
        get() = SLOTS_PER_SYNC_COMMITTEE_PERIOD * secondsPerSlot

    companion object {
        private const val SLOTS_PER_SYNC_COMMITTEE_PERIOD = 8_192L

        /** The network whose EIP-155 chain id is [chainId], or null for one Serotine does not work on. */
        fun ofChainId(chainId: Long): Network? = entries.firstOrNull { it.chainId == chainId }
    }
}
