package eth.serotine.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import eth.serotine.ui.App

/** Renders the shared [App]; all state lives in the ViewModels the activity scopes. */
class MainActivity : ComponentActivity() {

    // Nothing to do with the answer: without the permission, upgrade notices are dropped and
    // the app's own screens still say them.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as SerotineApplication).graph
        setContent {
            App(graph)
        }
        // A `wc:` link, or Punicus's deep link carrying one, opened the app: not again when the activity is only recreated.
        if (savedInstanceState == null) offerDappLink(intent)
        if (savedInstanceState == null) askForNotifications()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerDappLink(intent)
    }


    /**
     * Hands a `wc:` link the activity was opened with, or the one a `serotine://wc?uri=` deep link
     * carries, to Connect a dApp, which shows it filled in and pairs only on the user's Connect
     * (docs/walletconnect.md, part 1.2a). Nothing else is read.
     */
    private fun offerDappLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        // Reopened from Recents, Android hands the first link again: an old one, never offered twice.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val link = intent.dataString ?: return
        (application as SerotineApplication).graph.incomingDappLink.offer(link)
        // The activity keeps no copy of the link, which carries its pairing's key (ruling 8).
        setIntent(Intent(intent).setData(null))
    }

    /**
     * Asks for the permission to notify (Android 13 and later), so a network upgrade can be
     * announced while the app is closed. Android itself stops showing the question once the
     * user has said no twice, so a launch after that asks nothing.
     */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
