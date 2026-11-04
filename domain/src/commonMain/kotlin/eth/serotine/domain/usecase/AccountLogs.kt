package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.HistoryStartList
import eth.serotine.domain.repository.HistoryStarts
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.LogWatch
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.TransactionHistory
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate

/**
 * [accounts], which also notes in [starts] when each account is added ([HistoryStarts.note]):
 * what the composition roots hand every use case that adds one, so none of them has to know of
 * it. Only an account [accounts] added is noted, once it is listed, in place of any start left
 * from when it was in the wallet before. What [add] answers is [accounts]' answer. An account
 * whose note could not be saved starts at the head its start is fixed at, which is still after it
 * was added; one that [FollowAccountLogs] fixes at the head before its note is made has that start
 * replaced by the note, and is fixed again from it, earlier. [clock] is in unix seconds.
 */
class HistoryNotingAccounts(
    private val accounts: AccountRepository,
    private val starts: HistoryStarts,
    private val clock: () -> Long = ::epochSecondsNow,
) : AccountRepository by accounts {

    override suspend fun add(account: Account): AccountAdd {
        val added = accounts.add(account)
        if (added == AccountAdd.Added) starts.note(account.id, clock())
        return added
    }
}

/**
 * Keeps the engine's [index] following every account of the wallet (issue #85), whatever screen
 * is open: the hosts run it while the engine runs, never `App`.
 *
 * - **Where each starts.** Once the light client is synced, each account whose start is not fixed
 *   gets its block ([HistoryStarts.fix]): the earliest the device knows of it, that is when it
 *   was added ([HistoryStart.Noted]) or when this device sent its first transaction concerning
 *   it ([concerns]), counted back from the verified head at [Network.secondsPerSlot] a block, a
 *   slot without one only adding to the count, and an hour more for a clock that is off; a
 *   verified block such a transaction was seen in, when earlier still. An account the device
 *   knows nothing earlier of (one added before starts were noted) starts at the head. Nothing
 *   before its start is followed: "since added" (owner ruling, 2026-10-20).
 * - **What is followed.** Every account from its start, as a [LogWatchKey.Mention] (the token
 *   transfers to and from it), and a Safe also as a [LogWatchKey.Emitter] (its own events).
 * - **What is dropped.** An account that left the wallet is unwatched, with what the index held
 *   of it, whatever its start, and its start forgotten once the engine took that.
 *
 * What the engine took is not asked of it again while this runs; what it did not take is asked
 * again at the next change of the light client's state (a new head, about every slot). Each
 * start asks the engine again for all of it, which costs nothing for what it holds already: so
 * does a start of the network's light client after it was turned off or went idle, since its
 * chain is then created anew (docs/networks.md, *The engine host*).
 */
class FollowAccountLogs(
    private val accounts: AccountRepository,
    private val starts: HistoryStarts,
    private val history: TransactionHistory,
    private val lightClient: LightClient,
    private val index: LogIndex,
    private val network: Network,
) {
    /** Runs until cancelled. */
    suspend operator fun invoke() {
        var taken = emptySet<LogWatch>()
        combine(
            accounts.observeAccounts(),
            starts.observe(),
            history.observe(),
            lightClient.observeStatus(network),
            ::Round,
        ).conflate().collect { round -> taken = follow(round, taken) }
    }

    /** One look at the wallet: fixes what starts it can, then asks the engine for what it has not [taken] yet. */
    private suspend fun follow(round: Round, taken: Set<LogWatch>): Set<LogWatch> {
        // A stopped light client's chain is unregistered, and a later start creates it anew:
        // nothing is taken for granted of it then, and everything is asked again.
        if (round.status == LightClientStatus.Off || round.status == LightClientStatus.Idle) return emptySet()
        // Only this network's accounts: its index, its starts, its light client (docs/networks.md).
        val listed = (round.accounts as? AccountList.Loaded)?.accounts?.filter { it.network == network } ?: return taken
        val allStarts = (round.starts as? HistoryStartList.Loaded)?.starts ?: return taken
        val held = allStarts.filterKeys { it.network == network }.mapKeys { (id, _) -> id.address }
        val synced = round.status as? LightClientStatus.Synced ?: return taken
        val listedAddresses = listed.map { it.address }.toSet()
        val fixed = held.mapNotNull { (address, start) -> (start as? HistoryStart.Fixed)?.let { address to it.block } }
            .toMap(mutableMapOf())
        // An unreadable history only leaves out what it could add: a start no earlier than the note.
        val entries = (round.history as? HistoryList.Loaded)?.entries
            .orEmpty()
            .filter { it.transfer.network == network }
        val headTime = synced.headTimestamp
        if (headTime != null) {
            listed.filter { it.address !in fixed }.forEach { account ->
                val noted = held[account.address] as? HistoryStart.Noted
                val block = startOf(account.address, noted, entries, synced.headBlock, headTime)
                if (starts.fix(AccountId(network, account.address), block)) fixed[account.address] = block
            }
        }
        val watch = listed.flatMap { account ->
            val from = fixed[account.address] ?: return@flatMap emptyList()
            listOfNotNull(
                LogWatch(LogWatchKey.Mention(account.address), from),
                LogWatch(LogWatchKey.Emitter(account.address), from).takeIf { account.kind == AccountKind.SAFE },
            )
        }.toSet()
        // Whatever its start, a noted one included: a start fixed and watched may have been
        // replaced by the note of an add that raced it. Unwatching an entry never held is nothing.
        val gone = held.filterKeys { it !in listedAddresses }
        val asked = watch - taken
        if (asked.isEmpty() && gone.isEmpty()) return taken
        val unwatch = gone.keys.flatMap { listOf(LogWatchKey.Mention(it), LogWatchKey.Emitter(it)) }
        if (!index.configure(network, asked.toList(), unwatch)) return taken
        gone.forEach { (address, start) -> starts.forget(AccountId(network, address), start) }
        return (taken + asked).filterTo(HashSet()) { it.key.address !in gone }
    }

    /**
     * The block [address]'s history starts at: counted back from [headBlock], made at [headTime],
     * to the earliest time the device knows of it ([noted], or a transaction in [entries]
     * concerning it), or the head when it knows none; or a verified block of such a transaction,
     * when earlier still.
     */
    private fun startOf(
        address: Address,
        noted: HistoryStart.Noted?,
        entries: List<HistoryEntry>,
        headBlock: Long,
        headTime: Long,
    ): Long {
        val concerning = entries.filter { it.concerns(AccountId(network, address)) }
        val earliest = (concerning.map { it.transfer.sentAt } + listOfNotNull(noted?.atSeconds)).minOrNull()
        val counted = if (earliest == null) {
            headBlock
        } else {
            val seconds = (headTime - earliest).coerceAtLeast(0) + CLOCK_MARGIN_SECONDS
            headBlock - (seconds + network.secondsPerSlot - 1) / network.secondsPerSlot
        }
        val seen = concerning.mapNotNull { it.outcome.blockNumberOrNull }.minOrNull()
        return minOf(counted, seen ?: counted).coerceAtLeast(0)
    }

    private class Round(
        val accounts: AccountList,
        val starts: HistoryStartList,
        val history: HistoryList,
        val status: LightClientStatus,
    )

    private companion object {
        /** How far the device's clock may be off from the chain's and the start still be early enough. */
        const val CLOCK_MARGIN_SECONDS = 60L * 60
    }
}
