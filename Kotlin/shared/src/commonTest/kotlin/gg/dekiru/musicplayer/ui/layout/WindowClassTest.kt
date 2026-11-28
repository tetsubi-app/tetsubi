package gg.dekiru.musicplayer.ui.layout

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowClassTest {
    @Test
    fun boundariesAre600And1024() {
        assertEquals(WindowClass.Compact, WindowClass.fromWidth(0f))
        assertEquals(WindowClass.Compact, WindowClass.fromWidth(360f))
        assertEquals(WindowClass.Compact, WindowClass.fromWidth(599.9f))
        assertEquals(WindowClass.Medium, WindowClass.fromWidth(600f))
        assertEquals(WindowClass.Medium, WindowClass.fromWidth(1023.9f))
        assertEquals(WindowClass.Expanded, WindowClass.fromWidth(1024f))
        assertEquals(WindowClass.Expanded, WindowClass.fromWidth(2560f))
    }

    @Test
    fun marginsAndGuttersPerClass() {
        assertEquals(16.dp, WindowClass.Compact.pageMargin)
        assertEquals(12.dp, WindowClass.Compact.gutter)
        assertEquals(24.dp, WindowClass.Medium.pageMargin)
        assertEquals(16.dp, WindowClass.Medium.gutter)
        assertEquals(32.dp, WindowClass.Expanded.pageMargin)
        assertEquals(20.dp, WindowClass.Expanded.gutter)
    }

    @Test
    fun wideMeansNotCompact() {
        assertFalse(WindowClass.Compact.isWide)
        assertTrue(WindowClass.Medium.isWide)
        assertTrue(WindowClass.Expanded.isWide)
    }
}
