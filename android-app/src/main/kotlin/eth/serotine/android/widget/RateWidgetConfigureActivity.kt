package eth.serotine.android.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import eth.serotine.android.R
import eth.serotine.android.SerotineApplication
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.RatePair
import eth.serotine.ui.ratewidget.WidgetPairScreen
import eth.serotine.ui.ratewidget.WidgetPairUiState
import eth.serotine.ui.ratewidget.widgetPairLoading
import eth.serotine.ui.theme.SerotineTheme
import kotlinx.coroutines.launch

/**
 * The exchange-rate widget's configuration (docs/wallet-ux.md, *Home-screen widget*): when a widget
 * is placed, the launcher opens this to ask what it shows, one step at a time
 * ([SerotineApplication.widgetPairStep]): the network, among those that are on and have accounts,
 * then a price on it (its native currency's, or one its tokens are priced by; a filter above them
 * narrows them as it is typed into), then the currency it
 * is shown in, or the display currency. The steps chosen so far outlive a rotation. The choice is
 * kept for that widget alone ([RateWidgetPairs]); another one means placing another widget.
 * Cancelled, or with nothing to choose, the widget is not placed. Exported, as the launcher, another
 * app, opens it; it acts only on a widget id of this app's own [RateWidget] whose pair is not
 * chosen yet.
 */
class RateWidgetConfigureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Until a network is chosen, leaving here places no widget.
        setResult(RESULT_CANCELED)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider
        // Only a widget of this app's being placed: one placed keeps its pair (another is another widget).
        if (provider != ComponentName(this, RateWidget::class.java) || RateWidgetPairs(this).chosen(id)) {
            finish()
            return
        }
        val app = application as SerotineApplication
        enableEdgeToEdge()
        setContent {
            // What is chosen so far, by chain id and price id: null until it is.
            var chainId by rememberSaveable { mutableStateOf<Long?>(null) }
            var baseId by rememberSaveable { mutableStateOf<String?>(null) }
            // The prices' filter, as typed; emptied with each network chosen or left.
            var query by rememberSaveable { mutableStateOf("") }
            val network = chainId?.let(Network::ofChainId)
            val base = baseId?.let { runCatching { PriceId(it) }.getOrNull() }
            // The step restored after a rotation shows at once, with nothing to choose until it is read.
            var state by remember { mutableStateOf(widgetPairLoading(network, base)) }
            LaunchedEffect(network, base) { state = app.widgetPairStep(network, base) }
            // A tap counts only on the step of what is chosen now, not on one still shown while the
            // next is read (a tap right after Back).
            val current = when (val shown = state) {
                is WidgetPairUiState.Networks -> network == null
                is WidgetPairUiState.Bases -> base == null && shown.network.chainId == network?.chainId
                is WidgetPairUiState.Quotes -> shown.network.chainId == network?.chainId && shown.base == base?.id
            }
            SerotineTheme {
                WidgetPairScreen(
                    (state as? WidgetPairUiState.Bases)?.copy(query = query) ?: state,
                    onNetwork = {
                        if (current) {
                            chainId = it
                            baseId = null
                            query = ""
                        }
                    },
                    onBase = { if (current) baseId = it },
                    onQuote = { code ->
                        if (current && network != null && base != null) choose(app, id, network, base, code)
                    },
                    onFilter = { if (current) query = it },
                    onBack = {
                        if (baseId != null) {
                            baseId = null
                        } else {
                            chainId = null
                            query = ""
                        }
                    },
                    onCancel = ::finish,
                )
            }
        }
    }

    /** Whether a choice is being stored: a second tap meanwhile does nothing. */
    private var choosing = false

    /**
     * Places the widget [id] showing [base] on [network] in the currency of [code] (null: the
     * display currency).
     */
    private fun choose(app: SerotineApplication, id: Int, network: Network, base: PriceId, code: String?) {
        val quote = code?.let { DisplayCurrency.entries.firstOrNull { it.code == code } ?: return }
        val pair = runCatching { RatePair(network, base, quote) }.getOrNull() ?: return
        if (choosing) return
        choosing = true
        lifecycleScope.launch {
            if (!app.rateWidgetConfigured(id, pair)) {
                choosing = false
                Toast.makeText(this@RateWidgetConfigureActivity, R.string.rate_widget_not_saved, Toast.LENGTH_LONG)
                    .show()
                return@launch
            }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            finish()
        }
    }
}
