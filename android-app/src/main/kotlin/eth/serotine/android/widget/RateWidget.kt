package eth.serotine.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import eth.serotine.android.MainActivity
import eth.serotine.android.R
import eth.serotine.android.SerotineApplication
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.RatePair
import eth.serotine.domain.model.RateSnapshot
import eth.serotine.ui.ratewidget.Trend
import eth.serotine.ui.ratewidget.rateWidgetContent

/**
 * The home-screen widget with the exchange rate last verified (docs/wallet-ux.md, *Home-screen
 * widget*): drawn from the rate kept on file, never by starting the light client. Android asks
 * it to draw on its own (`updatePeriodMillis` is 0, so never on a timer); new rates reach it
 * from [RateRefreshJob], on an unmetered network, and from the app while it is open
 * ([SerotineApplication.holdEngine]), and from its refresh button, which reads it now on any
 * network, a spinner in the button's place meanwhile ([SerotineApplication.refreshRateWidgetsNow]).
 * Placing the first one schedules the job and removing the last one cancels it. A tap anywhere
 * else opens the app. Each widget shows one rate, of a network's native currency or of a price of
 * its tokens, in a currency or the display currency, chosen when it is placed
 * ([RateWidgetConfigureActivity], kept in [RateWidgetPairs]; docs/wallet-ux.md, *Home-screen widget*).
 */
class RateWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val pending = goAsync()
            (context.applicationContext as SerotineApplication).refreshRateWidgetsNow { pending.finish() }
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext as SerotineApplication
        val pending = goAsync()
        app.drawRateWidgets { pending.finish() }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        RateWidgetPairs(context).forget(appWidgetIds)
        // The pairs the widgets left show are followed from here on, not the removed one's.
        (context.applicationContext as SerotineApplication).rateWidgetsChanged()
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        // A launcher restored from a backup gives the widgets new ids: each keeps its pair.
        RateWidgetPairs(context).moved(oldWidgetIds, newWidgetIds)
        (context.applicationContext as SerotineApplication).rateWidgetsChanged()
    }

    override fun onEnabled(context: Context) {
        (context.applicationContext as SerotineApplication).rateWidgetPlaced()
    }

    override fun onDisabled(context: Context) {
        (context.applicationContext as SerotineApplication).rateWidgetsRemoved()
    }

    companion object {
        /** What the refresh button sends this receiver; only this app's own PendingIntent can. */
        private const val ACTION_REFRESH = "eth.serotine.android.widget.REFRESH"

        /** The ids of the widgets on the home screen. */
        fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, RateWidget::class.java))

        /** Whether a widget is on a home screen: the only case anything is read for one. */
        fun placed(context: Context): Boolean = ids(context).isNotEmpty()

        /** The pairs the placed widgets show, each once: what a refresh reads. */
        fun pairs(context: Context): Set<RatePair> {
            val pairs = RateWidgetPairs(context)
            return ids(context).mapTo(LinkedHashSet()) { pairs.of(it) }
        }

        /**
         * Draws every widget placed with its pair's rate in [rates] (none kept yet: absent or
         * null) and its watched asset's bundled icon key in [icons] (absent or null: no icon
         * shown), saying so where that pair's network is not [on], with the spinner in the
         * refresh button's place while a refresh the user asked for is on its way
         * ([SerotineApplication.refreshShown]), unless [refreshing] says otherwise. Any thread.
         */
        fun draw(
            context: Context,
            rates: Map<RatePair, RateSnapshot?>,
            icons: Map<RatePair, String?>,
            on: Set<Network>,
            refreshing: Boolean? = null,
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val pairs = RateWidgetPairs(context)
            val spinner = refreshing ?: (context.applicationContext as SerotineApplication).refreshShown
            for (id in ids(context)) {
                val pair = pairs.of(id)
                val views = views(context, rates[pair], icons[pair], pair, pair.network in on, spinner)
                manager.updateAppWidget(id, views)
            }
        }

        private fun views(
            context: Context,
            snapshot: RateSnapshot?,
            icon: String?,
            pair: RatePair,
            on: Boolean,
            refreshing: Boolean,
        ): RemoteViews {
            // The date with the time: a widget is not redrawn at midnight, so a time alone could
            // pass yesterday's rate off as today's.
            val readAt = snapshot?.let {
                DateUtils.formatDateTime(
                    context,
                    it.readAt * 1_000,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH or
                        DateUtils.FORMAT_NO_YEAR,
                )
            }.orEmpty()
            val content = rateWidgetContent(snapshot, pair, readAt, on, icon)
            val change = when (content.trend) {
                Trend.UP -> R.id.widget_change_up
                Trend.DOWN -> R.id.widget_change_down
                Trend.FLAT, Trend.UNKNOWN -> R.id.widget_change_flat
            }.takeIf { content.change != null }
            val iconRes = content.icon?.let(WidgetAssetIcons::of)
            return RemoteViews(context.packageName, R.layout.widget_rate).apply {
                setTextViewText(R.id.widget_pair, content.pair)
                setTextViewText(R.id.widget_rate, content.rate)
                setTextViewText(R.id.widget_note, content.note)
                for (id in CHANGE_VIEWS) {
                    setViewVisibility(id, if (id == change) View.VISIBLE else View.GONE)
                    if (id == change) setTextViewText(id, content.change)
                }
                setViewVisibility(R.id.widget_icon, if (iconRes != null) View.VISIBLE else View.GONE)
                if (iconRes != null) setImageViewResource(R.id.widget_icon, iconRes)
                setViewVisibility(R.id.widget_refresh, if (refreshing) View.GONE else View.VISIBLE)
                setViewVisibility(R.id.widget_refreshing, if (refreshing) View.VISIBLE else View.GONE)
                setOnClickPendingIntent(R.id.widget_refresh, refresh(context))
                setOnClickPendingIntent(R.id.widget_root, openApp(context))
            }
        }

        private fun refresh(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RateWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private val CHANGE_VIEWS = listOf(R.id.widget_change_up, R.id.widget_change_down, R.id.widget_change_flat)
    }
}
