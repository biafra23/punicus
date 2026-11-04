package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.UpgradeNotice
import eth.serotine.domain.model.UpgradeNotice.Kind
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.UpgradeNotifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * When the user hears about an upgrade outside the app, through [notifier]: once when peers
 * first report it, again a week and a day before its activation, and once when it has
 * activated, after which nothing more is said about it. The record in [known] carries what
 * has been shown across restarts, so each notice is shown once (twice at worst, when its record
 * could not be written: told twice beats never), and what is still to come falls due while the
 * app is not running: the notifier is asked to wake it then.
 *
 * Fed by [FollowUpgradeReports] while the engine runs, and by the host's alarm through
 * [remind] while it does not. One instance per process: its calls take turns.
 */
class UpgradeNotices(
    private val network: Network,
    private val known: KnownUpgradeStore,
    private val notifier: UpgradeNotifier,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    private val lock = Mutex()

    /** The reminder last asked of the notifier, so it is asked again only when that changes. */
    private var armed: Reminder? = null

    private data class Reminder(val at: Long?)

    /**
     * Brings the record up to date with [report], the upgrade peers report now (null: none),
     * shows the notice due now, if one is, and asks the notifier for the next reminder.
     * Returns when that reminder falls (unix seconds), or null when none is to come.
     */
    suspend fun check(report: NetworkUpgrade?): Long? = lock.withLock {
        val now = clock()
        val stored = known.observe(network).first()
        val (record, notice) = plan(stored, report, now)
        // Shown before the record says so: told twice beats never. Should the app die between
        // the two, the notice is shown again; a record that cannot be stored still has its
        // notice shown; and so does one whose store surfaces the caller's cancellation as the
        // save returns, the record already written.
        notice?.let(notifier::show)
        // Peers come and go, and with them the count of networks reporting: not worth a write.
        if (record != null && record.ignoringCount() != stored?.ignoringCount()) known.save(network, record)
        val next = record?.let { nextReminder(it, now) }
        if (armed != Reminder(next)) {
            notifier.remindAt(next)
            armed = Reminder(next)
        }
        next
    }

    /** Shows the notice the record makes due now, if any, and asks for the next reminder: what a host's alarm calls. */
    suspend fun remind(): Long? = check(null)

    /**
     * Whether all is said: peers confirmed that the recorded upgrade activated, and the user
     * has been told. Then only an update of the app changes anything, and a host's background
     * look for upgrades has nothing left to find. An activation told by the clock alone is not
     * enough: the fork may have moved, and the look is what hears its new time.
     */
    suspend fun allSaid(): Boolean = known.observe(network).first()?.let {
        it.upgrade.phase == NetworkUpgrade.Phase.ACTIVE && Kind.ACTIVATED in it.shown
    } == true

    private fun KnownUpgrade.ignoringCount() = copy(upgrade = upgrade.copy(reportingNetworks = 0))

    private fun plan(stored: KnownUpgrade?, report: NetworkUpgrade?, now: Long): Pair<KnownUpgrade?, UpgradeNotice?> {
        val current = when {
            report == null -> stored
            stored != null && stored.upgrade.isSameUpgradeAs(report) ->
                stored.copy(upgrade = stored.upgrade.updatedBy(report))
            // A new upgrade, or this one moved to another time: the user hears of it afresh.
            else -> KnownUpgrade(report, shown = emptySet())
        } ?: return null to null
        val due = dueAt(current.upgrade, now)
        val unseen = due - current.shown
        if (unseen.isEmpty()) return current to null
        // Only the most urgent: a phone that was off for a week hears "within a day", not
        // three notices at once. What it passes over counts as shown.
        val record = current.copy(shown = current.shown + due)
        return record to UpgradeNotice(unseen.max(), record.upgrade)
    }

    /** Every notice whose moment has come at [now]. */
    private fun dueAt(upgrade: NetworkUpgrade, now: Long): Set<Kind> {
        if (upgrade.hasActivated(now)) return Kind.entries.toSet()
        val activation = upgrade.activationTime ?: return setOf(Kind.ANNOUNCED)
        return buildSet {
            add(Kind.ANNOUNCED)
            if (now >= activation - WEEK) add(Kind.WEEK_AHEAD)
            if (now >= activation - DAY) add(Kind.DAY_AHEAD)
        }
    }

    /** When the next notice falls due, or null: none is left, or the activation time is not known. */
    private fun nextReminder(record: KnownUpgrade, now: Long): Long? {
        val activation = record.upgrade.activationTime ?: return null
        if (record.upgrade.hasActivated(now)) return null
        val reminders = listOf(
            activation - WEEK to Kind.WEEK_AHEAD,
            activation - DAY to Kind.DAY_AHEAD,
            activation to Kind.ACTIVATED,
        )
        return reminders
            .filter { (at, kind) -> at > now && kind !in record.shown }
            .minOfOrNull { (at, _) -> at }
    }

    private companion object {
        const val DAY = 24 * 60 * 60L
        const val WEEK = 7 * DAY
    }
}
