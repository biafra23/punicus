package eth.serotine.ui.components

import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.ui.components.LightClientHealth.NOT_RUNNING
import eth.serotine.ui.components.LightClientHealth.NOT_VERIFIED
import eth.serotine.ui.components.LightClientHealth.STALE
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * The light client's state as the status strip shows it (on Accounts and every other screen that
 * depends on the light client) and Settings' row: [text] always; [progress] (0 to 1) when there
 * is a fraction to show, null for an open-ended wait; [working] while the client is on its way
 * to serving verified reads; and its [health], the strip's colour. [simpleText] is simple mode's
 * sync line (docs/simple-ui.md, *The sync line*): what keeps the network from verified reads, in
 * plain words, or null while nothing does, or nothing needs saying (asleep, off, idle).
 */
data class LightClientItem(
    val text: String,
    val progress: Float?,
    val working: Boolean,
    val health: LightClientHealth,
    val simpleText: String? = null,
) {

    companion object {
        /**
         * [status] as the strip shows it at [now] (unix seconds), which dates the head block. A
         * stale checkpoint's line says where the user decides on it, unless [decidedHere] (the
         * screen offers the decision itself). Simple mode's line names [network].
         */
        fun from(
            status: LightClientStatus,
            now: Long,
            decidedHere: Boolean = false,
            network: String = "the network",
        ): LightClientItem = strip(status, now, decidedHere).copy(simpleText = simpleText(status, network, decidedHere))

        /** Simple mode's sync line for [status] on [network], or null when it draws none. */
        private fun simpleText(status: LightClientStatus, network: String, decidedHere: Boolean): String? =
            when (status) {
                LightClientStatus.Starting, is LightClientStatus.Bootstrapping -> "Syncing $network · starting"
                is LightClientStatus.CatchingUp ->
                    if (status.total > 0 && status.done < status.total) {
                        "Syncing $network · ${status.done} of ${status.total} periods"
                    } else {
                        "Syncing $network"
                    }
                is LightClientStatus.WaitingForHead -> when (val age = status.headAgeSeconds) {
                    null -> "Syncing $network · waiting for its first block"
                    else -> "Waiting for a new $network block · the last is ${ageOf(age)} old"
                }
                is LightClientStatus.WaitingForProofPeers -> "Looking for $network peers that can prove balances"
                is LightClientStatus.StaleAnchor -> "$network isn't syncing: its checkpoint is too old" +
                    if (decidedHere) "" else " · decide in Settings → Networks"
                LightClientStatus.Unavailable -> "No light client for $network on this device"
                is LightClientStatus.Synced, LightClientStatus.Sleeping, LightClientStatus.Off,
                LightClientStatus.Idle,
                -> null
            }?.replaceFirstChar { it.uppercase() }

        private fun strip(status: LightClientStatus, now: Long, decidedHere: Boolean): LightClientItem = when (status) {
            LightClientStatus.Starting -> LightClientItem("Starting the light client…", null, true, NOT_VERIFIED)
            is LightClientStatus.Bootstrapping -> LightClientItem(
                if (status.lookingForServers) {
                    "Bootstrapping from the trusted checkpoint · looking for light-client servers"
                } else {
                    "Bootstrapping from the trusted checkpoint"
                },
                null,
                true,
                NOT_VERIFIED,
            )
            is LightClientStatus.CatchingUp -> when {
                status.total <= 0 -> LightClientItem("Verifying sync committees", null, true, NOT_VERIFIED)
                status.done >= status.total -> LightClientItem("Finishing sync", null, true, NOT_VERIFIED)
                else -> LightClientItem(
                    "Verifying sync committees · ${status.done} of ${status.total} periods",
                    status.done.toFloat() / status.total,
                    true,
                    NOT_VERIFIED,
                )
            }
            is LightClientStatus.WaitingForHead -> when (val headAge = status.headAgeSeconds) {
                null -> LightClientItem("Synced · waiting for the first verified head", null, true, NOT_VERIFIED)
                else -> LightClientItem(
                    "Synced · the verified head is ${ageOf(headAge)} old, waiting for a fresh one",
                    null,
                    true,
                    STALE,
                )
            }
            is LightClientStatus.WaitingForProofPeers -> LightClientItem(
                "Synced to block ${grouped(status.headBlock)}${since(status.headTimestamp, now)} · " +
                    "looking for peers that serve state proofs",
                null,
                true,
                STALE,
            )
            is LightClientStatus.Synced -> LightClientItem(
                "Verified at block ${grouped(status.headBlock)}${since(status.headTimestamp, now)}",
                null,
                false,
                VERIFIED,
            )
            LightClientStatus.Sleeping ->
                LightClientItem("Light client asleep while the app is in the background", null, false, STALE)
            is LightClientStatus.StaleAnchor -> LightClientItem(
                "Not syncing: the last trusted checkpoint is ${status.anchorAgePeriods} periods old" +
                    (if (status.boundPeriods > 0) ", past the safe limit of ${status.boundPeriods}" else "") +
                    if (decidedHere) "" else " · decide in Settings → Networks",
                null,
                false,
                NOT_VERIFIED,
            )
            LightClientStatus.Unavailable ->
                LightClientItem("No light client on this device", null, false, NOT_VERIFIED)
            LightClientStatus.Off -> LightClientItem("Turned off in this wallet", null, false, NOT_RUNNING)
            LightClientStatus.Idle ->
                LightClientItem("Light client idle · it starts once an account needs it", null, false, NOT_RUNNING)
        }

        /** " · 8 s ago" for a block produced at [timestamp], or nothing when its time is unknown. */
        private fun since(timestamp: Long?, now: Long): String = timestamp?.let {
            val seconds = now - it
            if (seconds < 1) " · just now" else " · ${ageOf(seconds)} ago"
        } ?: ""
    }
}

/**
 * How far the light client is from serving verified reads, the status strip's colour (owner's
 * request, 2026-10-19). Its text always says the same in words; the colour is never all it says.
 */
enum class LightClientHealth {
    /** Green: reads are verified at a fresh verified head. */
    VERIFIED,

    /**
     * Yellow: no fresh head to read at right now, for a reason that passes on its own — the
     * verified head is older than reads allow, no peer serves state proofs, or the light
     * client is asleep while the app is in the background — so balances show as last
     * verified, dated.
     */
    STALE,

    /** Red: no verified head yet (starting, bootstrapping, catching up), sync refused, or no light client. */
    NOT_VERIFIED,

    /**
     * Grey: the light client does not run, on purpose: its network is turned off in this wallet,
     * or on with nothing needing it yet (docs/networks.md). Nothing is wrong.
     */
    NOT_RUNNING,
}

/**
 * These states as the strip shows them, dated by [clock] (unix seconds): re-dated every
 * second while one shows a head block's age, unchanged otherwise.
 */
fun Flow<LightClientStatus>.asItems(
    clock: () -> Long,
    decidedHere: Boolean = false,
    network: String = "the network",
): Flow<LightClientItem> = redatedWhile { it.dated }.map { LightClientItem.from(it, clock(), decidedHere, network) }

/** Whether this state's line shows a head block's age ("· 8 s ago"), which moves on every second. */
internal val LightClientStatus.dated: Boolean
    get() = this is LightClientStatus.Synced && headTimestamp != null ||
        this is LightClientStatus.WaitingForProofPeers && headTimestamp != null

/**
 * Each value, emitted again every second while [dated] says it shows an age, so that age is
 * re-dated on screen; once otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> Flow<T>.redatedWhile(dated: (T) -> Boolean): Flow<T> = flatMapLatest { value ->
    if (dated(value)) everySecond.map { value } else flowOf(value)
}

/** Now, in unix seconds: the clock the screens date the head block against. */
@OptIn(ExperimentalTime::class)
fun epochSecondsNow(): Long = Clock.System.now().epochSeconds

private val everySecond = flow {
    while (true) {
        emit(Unit)
        delay(1.seconds)
    }
}
