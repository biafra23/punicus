package eth.serotine.domain.model

/**
 * The fallback handlers a Safe transaction may set and still be signed, approved or executed here
 * (owner ruling, 2026-10-18, #63; CLAUDE.md, *Trust*): per network, the canonical
 * CompatibilityFallbackHandler 1.4.1, and none (the zero address, which removes the handler).
 * A fallback handler answers the EIP-1271 signature checks other contracts make for the Safe, so
 * one that accepts every signature lets anyone spend what the Safe has approved; any other is
 * refused (`SafeTxRefusal.UnpinnedFallbackHandler`).
 *
 * Kept in the domain, beside the check it serves (`SigningRequest.unsignable`), which is made
 * before any port is read. The address is safe-global/safe-deployments'
 * `src/assets/v1.4.1/compatibility_fallback_handler.json`: canonical
 * `0xfd0732Dc9E303f09fCEf3a7388Ad10A83459Ec99`, code hash
 * `0x7c6007a5d711cea8dfd5d91f5940ec29c7f200fe511eb1fc1397b367af3c42f9`, the canonical deployment
 * on Sepolia (11155111), read 2026-10-18, on Ethereum (1), with the same code hash, read
 * 2026-10-23 at block 26,256,227, and on Gnosis Chain (100), the same again, read 2026-10-23 at
 * block 48,904,746.
 */
object PinnedFallbackHandlers {

    /** CompatibilityFallbackHandler 1.4.1's canonical address, the same on every network that has it. */
    val COMPATIBILITY_1_4_1: Address = Address.of("0xfd0732dc9e303f09fcef3a7388ad10a83459ec99")

    /** The CompatibilityFallbackHandler pinned on [network]. */
    fun compatibility(network: Network): Address = when (network) {
        Network.ETHEREUM, Network.GNOSIS, Network.SEPOLIA -> COMPATIBILITY_1_4_1
    }

    /** Whether a Safe on [network] may be given [handler] (null: none) by a transaction signed here. */
    fun allowed(network: Network, handler: Address?): Boolean =
        handler == null || handler == Address.ZERO || handler == compatibility(network)
}
