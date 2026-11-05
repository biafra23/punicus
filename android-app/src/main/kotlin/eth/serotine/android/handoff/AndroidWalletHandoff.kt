package eth.serotine.android.handoff

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import eth.serotine.domain.repository.HandoffAvailability
import eth.serotine.domain.repository.HandoffOpening
import eth.serotine.domain.repository.WalletHandoff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [WalletHandoff] on Android (issue #255): a payment link opened with a view intent for its
 * `ethereum:` scheme, which the system hands to the one wallet app that takes it, or shows its
 * chooser when several do. Started from the activity in front ([foreground]) so that the wallet
 * opens over Serotine and Back returns to it; from [context] in a new task when none is.
 *
 * Which apps take the scheme is read from the package manager, which on Android 11 and later sees
 * them only through the manifest's `<queries>` entry for `ethereum:` view intents. Serotine's own
 * apps never count as another wallet: should Serotine take `ethereum:` links itself one day (#256),
 * they are left out here, and the link must then go through a chooser that excludes them.
 */
class AndroidWalletHandoff(
    private val context: Context,
    private val foreground: () -> Activity?,
) : WalletHandoff {

    override fun availability(): HandoffAvailability = try {
        @Suppress("DEPRECATION") // the flags overload is API 33; this one covers minSdk 29 on
        // As startActivity resolves an implicit intent: only activities in the DEFAULT category count.
        val handlers = context.packageManager.queryIntentActivities(view(PROBE), PackageManager.MATCH_DEFAULT_ONLY)
        if (handlers.any { it.activityInfo?.packageName !in SEROTINE_PACKAGES }) {
            HandoffAvailability.AVAILABLE
        } else {
            HandoffAvailability.NO_WALLET
        }
    } catch (e: RuntimeException) {
        HandoffAvailability.NO_WALLET // the package manager failed: nothing to hand to
    }

    override suspend fun open(link: String): HandoffOpening = withContext(Dispatchers.Main) {
        val intent = view(link)
        try {
            val activity = foreground()
            if (activity != null) {
                activity.startActivity(intent)
            } else {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            HandoffOpening.OPENED
        } catch (e: ActivityNotFoundException) {
            HandoffOpening.NO_WALLET
        }
    }

    private fun view(link: String) = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)

    private companion object {
        /** A link of the shape every handoff has, which only the apps that take `ethereum:` answer. */
        const val PROBE = "ethereum:0x0000000000000000000000000000000000000000@1?value=0"

        /** The release and the debug app: Serotine, never another wallet. */
        val SEROTINE_PACKAGES = setOf("eth.serotine", "eth.serotine.debug")
    }
}
