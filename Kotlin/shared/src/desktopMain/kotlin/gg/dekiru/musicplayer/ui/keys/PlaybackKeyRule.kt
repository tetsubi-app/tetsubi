package gg.dekiru.musicplayer.ui.keys

import java.awt.event.KeyEvent as AwtKeyEvent

/** What a key press in the window does to playback. */
enum class PlaybackKeyAction { TOGGLE, NEXT, PREVIOUS }

/**
 * Which keys control playback in the Linux window, kept apart from Compose's
 * events so it can be tested. As on the Mac (`SpaceKeyRule`, the Playback
 * menu): bare Space plays or pauses, Ctrl+P too (the Mac's ⌘P), and
 * Ctrl+→ / Ctrl+← skip (⌘→ / ⌘←). The Pause key (Pause/Break, which some
 * laptops put on an F key) and a media Play/Pause key that reaches the window
 * play or pause as well.
 *
 * These see only presses nothing else took: a focused text field keeps its
 * spaces and Ctrl+arrows, a focused button its Space ([PlaybackKeys] is the
 * window's `onKeyEvent`, not `onPreviewKeyEvent`).
 */
object PlaybackKeyRule {
    fun action(
        awtKeyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
        alt: Boolean,
        meta: Boolean,
        mediaPlayPause: Boolean = false,
    ): PlaybackKeyAction? {
        if (mediaPlayPause) return PlaybackKeyAction.TOGGLE
        if (shift || alt || meta) return null
        return when (awtKeyCode) {
            AwtKeyEvent.VK_SPACE, AwtKeyEvent.VK_PAUSE -> if (ctrl) null else PlaybackKeyAction.TOGGLE
            AwtKeyEvent.VK_P -> if (ctrl) PlaybackKeyAction.TOGGLE else null
            AwtKeyEvent.VK_RIGHT -> if (ctrl) PlaybackKeyAction.NEXT else null
            AwtKeyEvent.VK_LEFT -> if (ctrl) PlaybackKeyAction.PREVIOUS else null
            else -> null
        }
    }
}
