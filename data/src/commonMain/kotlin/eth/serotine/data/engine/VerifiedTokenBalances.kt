package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [TokenBalances] over a verified light client: the token contract's `balanceOf(holder)` run
 * as a verified call ([VerifiedChain.call]), polled, cached and refused as [ProvenReads]
 * describes, and — for the tokens in [gated] alone — called again only at a head that
 * [activity] cannot vouch for: one whose blocks since the call it did not all check for a log
 * naming the holder, as a transfer's does. That holds only for a plain ERC-20, whose balances
 * move by a logged `Transfer` naming both sides, so only the network's built-in tokens are
 * gated. A token a user adds by address is called at every head, as without [activity]: it may
 * move balances with no log naming the holder (a rebasing token, an interest-bearing aToken, a
 * streaming or vesting token, a reflection token, a `Transfer` with unindexed parties). Even a
 * built-in token behind an upgradeable proxy could change through an upgrade; that one is
 * accepted, and a kept balance is still re-proven at the holder's next transfer. A call that reverts, or returns anything but one word, is a verified "not a
 * token" ([TokenBalanceReading.NotAToken]); one the engine refuses for good is
 * [ReadUnavailable.REFUSED]; one it cannot answer right now is unproven. While no head can be
 * read, the last proof, made in this process or kept in [LastBalancesFile] from an earlier one,
 * is [TokenBalanceReading.LastKnown].
 */
class VerifiedTokenBalances internal constructor(
    private val served: Network,
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
    /** Where the last balances are kept across restarts; null keeps them for this process only. */
    lastBalances: LastBalancesFile? = null,
    /** What vouches for an unchanged balance between heads; none: a call at every head. */
    activity: AddressActivity? = null,
    /** The tokens whose balances [activity] may vouch for: the network's plain, built-in ones. */
    gated: Set<Address> = emptySet(),
    /** When its balances are proven again: at every head when null; on demand in simple mode ([ProofSchedule]). */
    schedule: ProofSchedule? = null,
) : TokenBalances {

    /** One holder's balance of one token. */
    private data class Key(val holder: Address, val token: Address)

    /** A proven balance, or null for a verified answer that is no amount (and is not kept). */
    private val reads = ProvenReads<Key, TokenUnits?>(
        chain,
        dispatcher,
        pollInterval,
        kept = lastBalances?.kept<Key, TokenUnits?>(
            id = { "${served.chainId}:token:${it.holder.hex}:${it.token.hex}" },
            units = { it?.decimal },
            parse = TokenUnits::parse,
        ) ?: KeptProofs.none(),
        offersLastKnownWhenStopped = true,
        // A call whose block the engine did not name (UNKNOWN_BLOCK, negative) is never vouched for.
        unchangedSince = { key, provenAt, provenHash, head ->
            key.token in gated &&
                activity?.unchangedSince(key.holder, AddressActivity.Kind.LOG, provenAt, provenHash, head) == true
        },
        // A kept "not a token" is asked again: only an amount stands between heads.
        atHead = { _, value, _ -> value },
        schedule = schedule,
    ) { verified, key ->
        when (val outcome = verified.call(key.token, Abi.balanceOf(key.holder))) {
            is CallOutcome.Returned -> EngineReading.Proven(
                Abi.uint256(outcome.hex)?.let(TokenUnits::parse),
                outcome.blockNumber ?: UNKNOWN_BLOCK,
                outcome.blockHash,
            )
            is CallOutcome.Reverted -> EngineReading.Proven(null, outcome.blockNumber ?: UNKNOWN_BLOCK, outcome.blockHash)
            CallOutcome.Refused -> EngineReading.Unavailable(ReadUnavailable.REFUSED)
            null -> null
        }
    }

    override fun observeBalance(network: Network, holder: Address, token: Address): Flow<TokenBalanceReading> {
        if (network != served) return flowOf(TokenBalanceReading.Unavailable(ReadUnavailable.NO_ENGINE))
        return reads.observe(Key(holder, token)).map { reading ->
            when (reading) {
                EngineReading.Syncing -> TokenBalanceReading.Syncing
                is EngineReading.Proven -> reading.value?.let {
                    TokenBalanceReading.Verified(it, named(reading.blockNumber), reading.provenAt)
                } ?: TokenBalanceReading.NotAToken
                // Only an amount is offered as last known: an older "not a token" is not repeated.
                is EngineReading.LastKnown -> reading.value?.let {
                    TokenBalanceReading.LastKnown(it, named(reading.blockNumber), reading.asOf)
                } ?: TokenBalanceReading.Syncing
                is EngineReading.Unavailable -> TokenBalanceReading.Unavailable(reading.reason)
            }
        }
    }

    /** [block], or null for [UNKNOWN_BLOCK]. */
    private fun named(block: Long): Long? = block.takeIf { it != UNKNOWN_BLOCK }

    private companion object {
        /** What the engine reports for a call whose block it did not name. */
        const val UNKNOWN_BLOCK = -1L
    }
}
