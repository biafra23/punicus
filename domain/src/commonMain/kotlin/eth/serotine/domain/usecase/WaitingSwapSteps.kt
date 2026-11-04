package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.WaitingSwap
import eth.serotine.domain.repository.WaitingSwaps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The swaps of [AccountId] whose next step waits for the user, as they change (docs/simple-ui.md, H5). */
class ObserveWaitingSwaps(private val waitingSwaps: WaitingSwaps) {
    operator fun invoke(account: AccountId): Flow<List<WaitingSwap>> =
        waitingSwaps.observe().map { swaps -> swaps.filter { it.account == account } }
}

/** The waiting swap of an account selling a token, or null: what Continue quotes again. */
class FindWaitingSwap(private val waitingSwaps: WaitingSwaps) {
    suspend operator fun invoke(account: AccountId, sell: Address): WaitingSwap? =
        waitingSwaps.observe().first().firstOrNull { it.account == account && it.sell == sell }
}

/** Keeps a swap whose approval was just sent: Continue, in the account's Activity, brings its next step back. */
class KeepWaitingSwap(private val waitingSwaps: WaitingSwaps) {
    suspend operator fun invoke(swap: WaitingSwap): Boolean = waitingSwaps.keep(swap)
}

/**
 * Lets the waiting swap of an account and a token go: once a swap or an order selling that token is
 * sent from the account, or when the user dismisses it.
 */
class DropWaitingSwap(private val waitingSwaps: WaitingSwaps) {
    suspend operator fun invoke(account: AccountId, sell: Address): Boolean = waitingSwaps.drop(account, sell)
}
