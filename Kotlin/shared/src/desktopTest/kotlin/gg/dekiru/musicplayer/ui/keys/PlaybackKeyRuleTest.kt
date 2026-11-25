package gg.dekiru.musicplayer.ui.keys

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackKeyRuleTest {
    private fun action(code: Int, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false, meta: Boolean = false) =
        PlaybackKeyRule.action(code, ctrl, shift, alt, meta)

    @Test
    fun spaceAndPausePlayOrPause() {
        assertEquals(PlaybackKeyAction.TOGGLE, action(KeyEvent.VK_SPACE))
        assertEquals(PlaybackKeyAction.TOGGLE, action(KeyEvent.VK_PAUSE))
        assertEquals(PlaybackKeyAction.TOGGLE, action(KeyEvent.VK_P, ctrl = true))
        assertEquals(PlaybackKeyAction.TOGGLE, PlaybackKeyRule.action(0, false, false, false, false, mediaPlayPause = true))
    }

    @Test
    fun ctrlArrowsSkip() {
        assertEquals(PlaybackKeyAction.NEXT, action(KeyEvent.VK_RIGHT, ctrl = true))
        assertEquals(PlaybackKeyAction.PREVIOUS, action(KeyEvent.VK_LEFT, ctrl = true))
    }

    @Test
    fun otherCombinationsAreLeftAlone() {
        // Shift+Space, Ctrl+Space (input method switching), Alt+Space (window menu), Super+Space.
        assertNull(action(KeyEvent.VK_SPACE, shift = true))
        assertNull(action(KeyEvent.VK_SPACE, ctrl = true))
        assertNull(action(KeyEvent.VK_SPACE, alt = true))
        assertNull(action(KeyEvent.VK_SPACE, meta = true))
        // Plain P and arrows, Ctrl+Shift+P, Ctrl+Shift+arrows (selection).
        assertNull(action(KeyEvent.VK_P))
        assertNull(action(KeyEvent.VK_RIGHT))
        assertNull(action(KeyEvent.VK_P, ctrl = true, shift = true))
        assertNull(action(KeyEvent.VK_LEFT, ctrl = true, shift = true))
        assertNull(action(KeyEvent.VK_ENTER))
    }
}
