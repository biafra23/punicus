package eth.serotine.domain.model

import eth.serotine.domain.model.AbiWords.selector
import eth.serotine.domain.model.AbiWords.word

/**
 * The Safe's own functions that change its settings, called by the Safe on itself: their
 * selectors, which [SafeTxAction.of] decodes a call by, and the call data this wallet builds for
 * the ones it proposes (docs/wallet-ux.md, screen 28). What is built here decodes back, exactly,
 * to the [SafeSettingsChange] it was built for. A module enabled, which this wallet signs none of
 * (owner ruling, 2026-10-18, on #54), is decoded and never built.
 *
 * Owners and modules are linked lists in the Safe's storage, walked from the sentinel `0x1`;
 * removing or replacing an entry names the one before it ([previous]), in the order the list is
 * walked, which is the order [eth.serotine.domain.repository.SafeState] keeps them in.
 */
internal object SafeSettingsCalls {
    /** The head and tail of the Safe's linked lists: the entry before the first. */
    val SENTINEL: Address = Address.of("0x" + "0".repeat(39) + "1")

    val ADD_OWNER = selector("addOwnerWithThreshold(address,uint256)")
    val REMOVE_OWNER = selector("removeOwner(address,address,uint256)")
    val SWAP_OWNER = selector("swapOwner(address,address,address)")
    val CHANGE_THRESHOLD = selector("changeThreshold(uint256)")
    val ENABLE_MODULE = selector("enableModule(address)")
    val DISABLE_MODULE = selector("disableModule(address,address)")
    val SET_GUARD = selector("setGuard(address)")
    val SET_FALLBACK_HANDLER = selector("setFallbackHandler(address)")

    /** `addOwnerWithThreshold(owner, threshold)`. */
    fun addOwner(owner: Address, threshold: Long): ByteArray = ADD_OWNER + word(owner) + word(threshold)

    /** `removeOwner(previous, owner, threshold)`. */
    fun removeOwner(previous: Address, owner: Address, threshold: Long): ByteArray =
        REMOVE_OWNER + word(previous) + word(owner) + word(threshold)

    /** `swapOwner(previous, old, new)`. */
    fun swapOwner(previous: Address, old: Address, new: Address): ByteArray =
        SWAP_OWNER + word(previous) + word(old) + word(new)

    /** `changeThreshold(threshold)`. */
    fun changeThreshold(threshold: Long): ByteArray = CHANGE_THRESHOLD + word(threshold)

    /** `disableModule(previous, module)`. */
    fun disableModule(previous: Address, module: Address): ByteArray =
        DISABLE_MODULE + word(previous) + word(module)

    /** `setGuard(guard)`, the zero address for [guard] null: no guard any more. */
    fun setGuard(guard: Address?): ByteArray = SET_GUARD + word(guard ?: Address.ZERO)

    /** `setFallbackHandler(handler)`, the zero address for [handler] null: none any more. */
    fun setFallbackHandler(handler: Address?): ByteArray = SET_FALLBACK_HANDLER + word(handler ?: Address.ZERO)

    /**
     * The entry before [entry] in the Safe's linked [list], in its on-chain order: the [SENTINEL]
     * for the first; null when [entry] is not in it.
     */
    fun previous(list: List<Address>, entry: Address): Address? = when (val index = list.indexOf(entry)) {
        -1 -> null
        0 -> SENTINEL
        else -> list[index - 1]
    }
}
