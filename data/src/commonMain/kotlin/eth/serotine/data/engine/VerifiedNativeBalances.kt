package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [NativeBalances] over a verified light client: each balance proven by
 * [VerifiedChain.proveBalance] and labelled with the block its proof anchors to, polled,
 * cached and refused as [ProvenReads] describes, and proven again only at a head that [activity]
 * cannot vouch for: one whose blocks since the proof it did not all check for a change to the
 * account itself; while no head can be read, the last proof,
 * made in this process or kept in [LastBalancesFile] from an earlier one, as
 * [BalanceReading.LastKnown]. The port never fails, and no answer from
 * anywhere else stands in.
 */
class VerifiedNativeBalances internal constructor(
    private val served: Network,
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
    /** Where the last balances are kept across restarts; null keeps them for this process only. */
    lastBalances: LastBalancesFile? = null,
    /** What vouches for an unchanged account between heads; none: a proof at every head. */
    activity: AddressActivity? = null,
    /** When its balances are proven again: at every head when null; on demand in simple mode ([ProofSchedule]). */
    schedule: ProofSchedule? = null,
) : NativeBalances {

    private val reads = ProvenReads<Address, Wei>(
        chain,
        dispatcher,
        pollInterval,
        kept = lastBalances?.kept<Address, Wei>(
            id = { "${served.chainId}:native:${it.hex}" },
            units = { it.decimal },
            parse = Wei::parse,
        )
            ?: KeptProofs.none(),
        offersLastKnownWhenStopped = true,
        schedule = schedule,
        unchangedSince = { address, provenAt, provenHash, head ->
            activity?.unchangedSince(address, AddressActivity.Kind.ACCOUNT, provenAt, provenHash, head) == true
        },
    ) { verified, address ->
        verified.proveBalance(address)?.let { proven ->
            Wei.parse(proven.wei)?.let { EngineReading.Proven(it, proven.blockNumber, proven.blockHash) }
        }
    }

    override fun observeBalance(network: Network, address: Address): Flow<BalanceReading> {
        if (network != served) return flowOf(BalanceReading.Unavailable(ReadUnavailable.NO_ENGINE))
        return reads.observe(address).map { reading ->
        when (reading) {
            EngineReading.Syncing -> BalanceReading.Syncing
            is EngineReading.Proven -> BalanceReading.Verified(reading.value, reading.blockNumber, reading.provenAt)
            is EngineReading.LastKnown -> BalanceReading.LastKnown(reading.value, reading.blockNumber, reading.asOf)
            is EngineReading.Unavailable -> BalanceReading.Unavailable(reading.reason)
        }
    }
    }
}
