package eth.serotine.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.Process
import android.os.SystemClock

/**
 * Keeps the engine's networking on for the rest of the grace period after the app's last screen
 * left the foreground ([SerotineApplication.holdEngine]), so a user who switches away for a moment,
 * to copy an address say, finds the light clients still synced on return. Android 14 and later
 * freeze a process with no visible activity about 10 s after it is cached, and a frozen process
 * runs no timer and answers no peer; a running job keeps it out of that state. Expedited, so it
 * starts at once and shows no notification, which Android offers from 12 on; without it (Android
 * 10 and 11, or the expedited quota spent) the grace period is the host's timer alone. It only
 * makes sense for the process that scheduled it: never persisted, and a run in another process
 * (the one that scheduled it ended meanwhile) does nothing. The return of a screen cancels it, and
 * its end pauses the networking at once, before the process is frozen.
 */
class GracePeriodJob : JobService() {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * The run in progress; main thread only. A callback of a run that was stopped meanwhile finds
     * another one here, or none, and does nothing.
     */
    private var current: Run? = null

    private inner class Run(private val params: JobParameters, val until: Long) {
        val end = Runnable { finish() }

        fun finish() {
            if (current !== this) return
            current = null
            (application as SerotineApplication).releaseEngine(pauseAfterMs = 0)
            jobFinished(params, false)
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        // Scheduled by a process that has ended since: there is nothing left to keep warm.
        if (params.extras.getInt(EXTRA_PID, 0) != Process.myPid()) return false
        val until = params.extras.getLong(EXTRA_UNTIL, 0L)
        val left = until - SystemClock.elapsedRealtime()
        if (left <= 0) return false
        val run = Run(params, until)
        current = run
        (application as SerotineApplication).holdEngine()
        handler.postDelayed(run.end, left)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val run = current ?: return false
        current = null
        handler.removeCallbacks(run.end)
        // Stopped by a screen's return, or by the system: the pause still waits for the period's end,
        // so a stop that arrives after the user left again does not cut it short.
        val left = (run.until - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        (application as SerotineApplication).releaseEngine(pauseAfterMs = left)
        return false
    }

    companion object {
        private const val JOB_ID = 5
        private const val EXTRA_UNTIL = "until"
        private const val EXTRA_PID = "pid"

        /**
         * Asks for the grace period to last until [untilElapsedRealtime] ([SystemClock.elapsedRealtime]),
         * replacing one asked for before. Does nothing before Android 12, which has no expedited jobs.
         * Calls the system's job service: not on a main thread.
         */
        fun start(context: Context, untilElapsedRealtime: Long) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, GracePeriodJob::class.java))
                .setExpedited(true)
                .setExtras(
                    PersistableBundle().apply {
                        putLong(EXTRA_UNTIL, untilElapsedRealtime)
                        putInt(EXTRA_PID, Process.myPid())
                    },
                )
                .build()
            // A refusal (the expedited quota spent) leaves the host's timer alone to it.
            scheduler.schedule(job)
        }

        /** Ends the grace period, started or not: a screen is back. Not on a main thread. */
        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
