package gg.dekiru.musicplayer.ui.nowplaying

import android.content.Context
import android.util.Log
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import com.google.android.gms.cast.framework.CastContext

/**
 * Google Cast's own dialogs (as its `MediaRouteButton` opens them): the
 * list of Cast devices, or while casting the device's controls with Stop
 * casting. Nothing happens if Cast hasn't started (it never crashes).
 */
@Composable
actual fun rememberCastPicker(casting: Boolean): (() -> Unit)? {
    val context = LocalContext.current
    return remember(context, casting) { { showCastDialog(context, casting) } }
}

private fun showCastDialog(context: Context, casting: Boolean) {
    try {
        // They're AppCompat dialogs; the app's own theme isn't AppCompat. Dark, as the app.
        val themed = ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
        if (casting) {
            MediaRouteControllerDialog(themed).show()
            return
        }
        val selector = CastContext.getSharedInstance()?.mergedSelector ?: return
        MediaRouteChooserDialog(themed).apply { routeSelector = selector }.show()
    } catch (e: Exception) {
        Log.w("TetsubiCast", "Couldn't open the Cast dialog", e)
    }
}
