package gg.dekiru.musicplayer.data.settings

import kotlin.math.max
import kotlin.math.min

/** A window's position and size in screen points (dp), plus whether it was maximised. */
data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean = false) {
    fun encode(): String = "$x,$y,$width,$height,${if (maximized) 1 else 0}"

    companion object {
        const val KEY = "window.bounds"

        fun decode(text: String?): WindowBounds? {
            val parts = text?.split(',')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
            if (parts.size < 4) return null
            return WindowBounds(parts[0], parts[1], parts[2], parts[3], parts.getOrNull(4) == 1)
        }
    }
}

/** A screen's usable area (its bounds less the taskbar, dock or menu bar), in the same points. */
data class ScreenArea(val x: Int, val y: Int, val width: Int, val height: Int)

/** Where the window opens: the saved bounds if they still show on a screen, else null (centred, default size). */
object WindowPlacementRules {
    const val MIN_WIDTH = 360
    const val MIN_HEIGHT = 480
    const val DEFAULT_WIDTH = 1100
    const val DEFAULT_HEIGHT = 760

    /** At least this much of the window must be on one screen (points), so its title bar can still be grabbed. */
    const val MIN_VISIBLE_WIDTH = 120
    const val MIN_VISIBLE_HEIGHT = 60

    /**
     * [saved] made to fit [screens]: never below the minimum size, never bigger than the screen it
     * mostly sits on, moved inside that screen. Null when it doesn't show enough on any screen (or
     * there are no screens), meaning "use the centred default".
     */
    fun resolve(saved: WindowBounds?, screens: List<ScreenArea>): WindowBounds? {
        if (saved == null || screens.isEmpty()) return null
        val width = max(saved.width, MIN_WIDTH)
        val height = max(saved.height, MIN_HEIGHT)
        val best = screens.maxByOrNull { overlapArea(saved.x, saved.y, width, height, it) } ?: return null
        val visibleW = overlapWidth(saved.x, width, best.x, best.width)
        val visibleH = overlapWidth(saved.y, height, best.y, best.height)
        if (visibleW < min(MIN_VISIBLE_WIDTH, width) || visibleH < min(MIN_VISIBLE_HEIGHT, height)) return null
        // A screen smaller than the minimum keeps the minimum (the window then overhangs it).
        val w = max(MIN_WIDTH, min(width, best.width))
        val h = max(MIN_HEIGHT, min(height, best.height))
        val x = saved.x.coerceIn(best.x, max(best.x, best.x + best.width - w))
        val y = saved.y.coerceIn(best.y, max(best.y, best.y + best.height - h))
        return WindowBounds(x, y, w, h, saved.maximized)
    }

    private fun overlapWidth(start: Int, length: Int, screenStart: Int, screenLength: Int): Int =
        max(0, min(start + length, screenStart + screenLength) - max(start, screenStart))

    private fun overlapArea(x: Int, y: Int, w: Int, h: Int, s: ScreenArea): Long =
        overlapWidth(x, w, s.x, s.width).toLong() * overlapWidth(y, h, s.y, s.height)
}
