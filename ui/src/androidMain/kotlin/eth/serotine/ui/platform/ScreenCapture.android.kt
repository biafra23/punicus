package eth.serotine.ui.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.util.WeakHashMap

/**
 * `FLAG_SECURE` on the window it is drawn in, while [blockCapture]: no screenshots, no screen
 * recording or casting, and a blank app-switcher snapshot. In a dialog that is two windows, the
 * dialog's own and the activity's under it (below). And, either way, the Compose view kept out
 * of the structure an autofill service reads, so a password manager can neither fill nor offer
 * to save what the screen's fields hold. Both are undone when the last caller leaves
 * composition.
 */
@Composable
internal actual fun protectFromScreenCapture(blockCapture: Boolean): Boolean {
    val view = LocalView.current
    val activityWindow = remember(view) { view.context.findActivity()?.window }
    // Inside a dialog the window to protect is the dialog's own (a password field in a dialog, issue #252). The
    // dialog's root layout provides it, the parent of the view the dialog's content composes into.
    val dialogWindow = remember(view) { (view.parent as? DialogWindowProvider)?.window }
    DisposableEffect(view, dialogWindow, activityWindow, blockCapture) {
        // In a dialog the activity's window is held too: Compose's Dialog applies SecureFlagPolicy.Inherit again
        // whenever its call recomposes, clearing the dialog's flag while the activity's window lacks it. Counted
        // per window, so this hold and a protected field of the screen behind share one count, released in any
        // order.
        val windows = listOfNotNull(dialogWindow ?: activityWindow, activityWindow.takeIf { dialogWindow != null })
        holdAutofill(view)
        if (blockCapture) windows.forEach(::holdSecure)
        onDispose {
            if (blockCapture) windows.forEach(::releaseSecure)
            releaseAutofill(view)
        }
    }
    return (dialogWindow ?: activityWindow) != null && blockCapture
}

/**
 * A view kept out of autofill, how many composed callers hold it, and what it was before.
 * Counted, because two protected screens overlap during a navigation transition: the one
 * leaving must not lift the protection from the one arriving.
 */
private class AutofillHold(val before: Int) {
    var holders = 0
}

/**
 * `FLAG_SECURE` held on a window, by how many composed callers, and whether the window carried
 * it before: one carrying it already (a host-wide setting, say) keeps it. Counted per window,
 * not per view: a screen's field and a dialog over it both hold the activity's window.
 */
private class SecureHold(val before: Boolean) {
    var blockers = 0
}

// Touched only from composition effects, which run on the main thread.
private val autofillHolds = WeakHashMap<View, AutofillHold>()
private val secureHolds = WeakHashMap<Window, SecureHold>()

private fun holdAutofill(view: View) {
    val hold = autofillHolds.getOrPut(view) {
        AutofillHold(before = view.importantForAutofill).also {
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
    }
    hold.holders++
}

private fun releaseAutofill(view: View) {
    val hold = autofillHolds[view] ?: return
    if (--hold.holders > 0) return
    autofillHolds.remove(view)
    view.importantForAutofill = hold.before
}

private fun holdSecure(window: Window) {
    val hold = secureHolds.getOrPut(window) {
        SecureHold(before = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }
    if (hold.blockers++ == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

private fun releaseSecure(window: Window) {
    val hold = secureHolds[window] ?: return
    if (--hold.blockers > 0) return
    secureHolds.remove(window)
    if (!hold.before) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** `FLAG_SECURE` blocks the capture, so there is nothing to warn about. */
@Composable
actual fun ScreenCaptureWarnings() = Unit
