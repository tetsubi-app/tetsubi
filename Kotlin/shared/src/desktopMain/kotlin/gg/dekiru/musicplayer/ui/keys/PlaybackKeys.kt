package gg.dekiru.musicplayer.ui.keys

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import gg.dekiru.musicplayer.playback.PlayerController

/**
 * The window's playback keys. Holding a key acts once: AWT gives no repeat
 * flag, so a key counts as held from its press until its release (AWT asks
 * X11 for detectable auto-repeat, so a held key sends presses and one
 * release). A held key's repeats are still consumed.
 */
class PlaybackKeys {
    private val held = mutableSetOf<Int>()

    /** For the window's `onKeyEvent`: true when the event was a playback key. */
    fun handle(event: KeyEvent, player: PlayerController?): Boolean {
        val code = event.key.nativeKeyCode
        if (event.type == KeyEventType.KeyUp) held.remove(code)
        val action = PlaybackKeyRule.action(
            awtKeyCode = code,
            ctrl = event.isCtrlPressed,
            shift = event.isShiftPressed,
            alt = event.isAltPressed,
            meta = event.isMetaPressed,
            mediaPlayPause = event.key == Key.MediaPlayPause,
        ) ?: return false
        if (event.type != KeyEventType.KeyDown) return true
        val p = player?.takeIf { it.currentTrack != null } ?: return false
        if (!held.add(code)) return true
        when (action) {
            PlaybackKeyAction.TOGGLE -> p.togglePlayPause()
            // Both do nothing in Maniac Mode, as the buttons.
            PlaybackKeyAction.NEXT -> p.skipToNext()
            PlaybackKeyAction.PREVIOUS -> p.skipToPrevious()
        }
        return true
    }

    /** The window lost the keyboard: a key let go elsewhere sends no release here. */
    fun reset() = held.clear()
}
