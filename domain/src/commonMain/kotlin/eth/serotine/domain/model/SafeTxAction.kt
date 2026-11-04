package eth.serotine.domain.model

/**
 * What a [SafeTx] does, decoded here from its raw fields (docs/wallet-ux.md, screen 17), never
 * from a description that came with it. Decoding is strict: a call is read as a transfer, an
 * approval, a swap or a change of the Safe's settings only when its data is exactly that
 * function's encoding;
 * anything else is a [ContractCall] with its selector, and a delegate call is always a
 * [DelegateCall], whatever its data, because the Safe then runs the target's code as itself.
 */
sealed interface SafeTxAction {

    /** [amount] wei of the network's currency to [to], with no call data. */
    data class NativeTransfer(val to: Address, val amount: UInt256) : SafeTxAction

    /**
     * [amount] (in the token's smallest units) of the ERC-20 token at [token] to [to]: the
     * token's `transfer`, called with no ether. What the token is called is the token's
     * contract's claim, read separately; the transfer is to that contract's address.
     */
    data class TokenTransfer(val token: Address, val to: Address, val amount: UInt256) : SafeTxAction

    /**
     * The ERC-20 token at [token] lets [spender] take up to [amount] (in its smallest units) of the
     * Safe's: the token's `approve`, called with no ether. It moves nothing on its own; the
     * spender may take that much later (docs/swaps.md, *What both venues share*).
     */
    data class TokenApproval(val token: Address, val spender: Address, val amount: UInt256) : SafeTxAction

    /**
     * A swap through the router at [RouterSwap.router], paid to the Safe, exactly as
     * [SwapRouter02.swap] builds one ([SwapRouter02.decode]). Whether that router is Uniswap's
     * pinned one is not part of decoding: it is checked against the verified venue before the
     * swap is called one (docs/swaps.md, *A Safe's swap*).
     */
    data class Swap(val swap: RouterSwap) : SafeTxAction

    /** A change of the Safe's own settings: a call of one of its functions, on itself, with no ether. */
    data class SettingsChange(val change: SafeSettingsChange) : SafeTxAction

    /**
     * Nothing: a call of the Safe itself with no ether and no data. Executed, it uses up its
     * nonce, which is how owners reject a transaction queued at the same nonce.
     */
    data object Rejection : SafeTxAction

    /**
     * A call of the contract at [to] with [value] wei and [dataSize] bytes of data, which this
     * wallet does not decode; [selector] is the function's (8 lower-case hex digits), null when
     * the data is shorter than one.
     */
    data class ContractCall(val to: Address, val value: UInt256, val selector: String?, val dataSize: Int) :
        SafeTxAction

    /**
     * A delegate call of [to]: the Safe runs that contract's code as itself, with its storage,
     * owners and funds, and the code can change any of them. Never decoded further: the data
     * means whatever that code makes of it. [selector] and [dataSize] as for [ContractCall].
     */
    data class DelegateCall(val to: Address, val selector: String?, val dataSize: Int) : SafeTxAction

    companion object {
        /** What [tx], a transaction of the Safe at [safe], does. */
        fun of(safe: Address, tx: SafeTx): SafeTxAction {
            val data = tx.data
            val selector = data.takeIf { it.size >= AbiWords.SELECTOR }?.copyOf(AbiWords.SELECTOR)?.toHex()
            if (tx.operation == SafeOperation.DELEGATECALL) return DelegateCall(tx.to, selector, data.size)
            if (data.isEmpty()) {
                return if (tx.to == safe && tx.value.isZero) Rejection else NativeTransfer(tx.to, tx.value)
            }
            if (tx.value.isZero) {
                // A call of the Safe on itself is one of its own functions, or no transfer at all.
                if (tx.to == safe) {
                    settingsChange(data)?.let { return SettingsChange(it) }
                } else {
                    Erc20.transferOf(data)?.let { return TokenTransfer(tx.to, it.to, it.amount) }
                    Erc20.approveOf(data)?.let { return TokenApproval(tx.to, it.spender, it.amount) }
                }
            }
            if (tx.to != safe) {
                SwapRouter02.decode(TransactionCall(tx.to, tx.value, data), safe)?.let { return Swap(it) }
            }
            return ContractCall(tx.to, tx.value, selector, data.size)
        }

        /** The change of settings [data] makes as a call of the Safe on itself, or null when it makes none of them. */
        private fun settingsChange(data: ByteArray): SafeSettingsChange? {
            fun args(selector: ByteArray, words: Int) = AbiWords.staticArguments(data, selector, words)
            fun address(word: ByteArray) = AbiWords.address(word)
            fun count(word: ByteArray) = AbiWords.uint(word).toLongOrNull()

            args(SafeSettingsCalls.ADD_OWNER, 2)?.let { (owner, threshold) ->
                return SafeSettingsChange.OwnerAdded(address(owner) ?: return null, count(threshold) ?: return null)
            }
            args(SafeSettingsCalls.REMOVE_OWNER, 3)?.let { (_, owner, threshold) ->
                return SafeSettingsChange.OwnerRemoved(address(owner) ?: return null, count(threshold) ?: return null)
            }
            args(SafeSettingsCalls.SWAP_OWNER, 3)?.let { (_, old, new) ->
                return SafeSettingsChange.OwnerSwapped(address(old) ?: return null, address(new) ?: return null)
            }
            args(SafeSettingsCalls.CHANGE_THRESHOLD, 1)?.let { (threshold) ->
                return SafeSettingsChange.ThresholdChanged(count(threshold) ?: return null)
            }
            args(SafeSettingsCalls.ENABLE_MODULE, 1)?.let { (module) ->
                return SafeSettingsChange.ModuleEnabled(address(module) ?: return null)
            }
            args(SafeSettingsCalls.DISABLE_MODULE, 2)?.let { (_, module) ->
                return SafeSettingsChange.ModuleDisabled(address(module) ?: return null)
            }
            args(SafeSettingsCalls.SET_GUARD, 1)?.let { (guard) ->
                return SafeSettingsChange.GuardSet((address(guard) ?: return null).orNone())
            }
            args(SafeSettingsCalls.SET_FALLBACK_HANDLER, 1)?.let { (handler) ->
                return SafeSettingsChange.FallbackHandlerSet((address(handler) ?: return null).orNone())
            }
            return null
        }

        private fun Address.orNone(): Address? = takeIf { it != Address.ZERO }
    }
}

/**
 * A change of a Safe's own settings, decoded from a call of the Safe on itself. The linked-list
 * neighbour some of these functions also take (`prevOwner`, `prevModule`) is a detail of the
 * Safe's storage, not part of the change, and left out.
 */
sealed interface SafeSettingsChange {

    /** [owner] becomes an owner, and the threshold [threshold]. */
    data class OwnerAdded(val owner: Address, val threshold: Long) : SafeSettingsChange

    /** [owner] is no longer an owner, and the threshold becomes [threshold]. */
    data class OwnerRemoved(val owner: Address, val threshold: Long) : SafeSettingsChange

    /** [old] is replaced as an owner by [new]. */
    data class OwnerSwapped(val old: Address, val new: Address) : SafeSettingsChange

    /** The threshold becomes [threshold]. */
    data class ThresholdChanged(val threshold: Long) : SafeSettingsChange

    /** [module] is enabled: it can move the Safe's funds without the owners' signatures. */
    data class ModuleEnabled(val module: Address) : SafeSettingsChange

    /** [module] is disabled. */
    data class ModuleDisabled(val module: Address) : SafeSettingsChange

    /** [guard] checks every transaction from now on (null: no guard any more). */
    data class GuardSet(val guard: Address?) : SafeSettingsChange

    /** [handler] answers the calls the Safe has no function for (null: none any more). */
    data class FallbackHandlerSet(val handler: Address?) : SafeSettingsChange
}

/**
 * The gas and refund terms a [SafeTx] carries beside its call (docs/wallet-ux.md, screen 17),
 * when it carries any: what a plain call ([SafeTx.call], all of them zero) does not.
 * - A [gasPrice] that is not zero makes the Safe pay whoever it names a refund after the call:
 *   `(gas used + baseGas) × gasPrice` in [gasToken] (null: ether) to [receiver] (null: the
 *   account that executes it). For ether the gas price is capped at the one the execution pays,
 *   but [baseGas] is not, and in a gas token neither is: the refund is bounded only by what the
 *   Safe holds, whatever the call does. The call then gets exactly [safeTxGas]; with none, it
 *   runs out at once and fails, and the Safe still uses up the nonce and pays.
 * - A [safeTxGas] that is not zero, with no gas price, lets the call fail without reverting the
 *   execution: whoever executes it with too little gas uses up the nonce.
 */
data class SafeTxGasTerms(
    val safeTxGas: UInt256,
    val baseGas: UInt256,
    val gasPrice: UInt256,
    val gasToken: Address?,
    val receiver: Address?,
) {
    companion object {
        /** The terms [tx] carries, or null when it carries none: a plain call's. */
        fun of(tx: SafeTx): SafeTxGasTerms? {
            val terms = SafeTxGasTerms(
                safeTxGas = tx.safeTxGas,
                baseGas = tx.baseGas,
                gasPrice = tx.gasPrice,
                gasToken = tx.gasToken.takeIf { it != Address.ZERO },
                receiver = tx.refundReceiver.takeIf { it != Address.ZERO },
            )
            return terms.takeUnless { it == NONE }
        }

        private val NONE = SafeTxGasTerms(UInt256.ZERO, UInt256.ZERO, UInt256.ZERO, gasToken = null, receiver = null)
    }
}
