package eth.serotine.domain.repository

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.model.UInt256
import kotlinx.coroutines.flow.Flow

/**
 * The port for the swaps whose next step waits for the user (docs/simple-ui.md, *Between the steps*):
 * an approval of the token was sent from this device, and the step after it, another approval or
 * the swap, is not sent yet. The account's Activity offers each with Continue, which quotes it again.
 * Kept as the swap's inputs, never as a quote or a signature: what Continue quotes is read fresh.
 * One per account and token sold: a newer one of the same takes the older one's place. :data keeps
 * them in a file of their own.
 */
interface WaitingSwaps {
    /**
     * Every swap waiting, re-emitted on each change; empty when none is, and when the record cannot be
     * read (a convenience: no swap depends on it). Never throws.
     */
    fun observe(): Flow<List<WaitingSwap>>

    /** Keeps [swap], in place of one of the same account and token; false when it could not be saved. Never throws. */
    suspend fun keep(swap: WaitingSwap): Boolean

    /** Lets the swap of [account] selling [sell] go; false when that could not be saved. Never throws. */
    suspend fun drop(account: AccountId, sell: Address): Boolean
}

/**
 * A swap from [account] of [amount] of the token at [sell] (in its own units) for [buy] (a token's
 * address, null: the network's currency), on [venue] at [tolerance], whose approval [approval] (its
 * transaction hash) was sent: step [step] of [steps] waits, the swap itself or, after an allowance
 * set to zero first, the approval of the whole amount.
 */
data class WaitingSwap(
    val account: AccountId,
    val sell: Address,
    val buy: Address?,
    val amount: UInt256,
    val tolerance: SlippageTolerance,
    val venue: SwapVenue,
    val approval: String,
    val step: Int,
    val steps: Int,
) {
    init {
        require(step in 2..steps) { "a waiting step follows one that was sent" }
    }
}
