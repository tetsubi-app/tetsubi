package gg.dekiru.musicplayer.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowBoundsTest {
    private val main = ScreenArea(0, 25, 1920, 1055)
    private val right = ScreenArea(1920, 0, 1280, 1024)

    @Test
    fun roundTripsAndRejectsGarbage() {
        val b = WindowBounds(10, -20, 800, 600, maximized = true)
        assertEquals(b, WindowBounds.decode(b.encode()))
        assertEquals(WindowBounds(1, 2, 3, 4), WindowBounds.decode("1,2,3,4"))
        assertNull(WindowBounds.decode(null))
        assertNull(WindowBounds.decode("1,2,x,4"))
        assertNull(WindowBounds.decode("1,2,3"))
    }

    @Test
    fun visibleBoundsAreKept() {
        val b = WindowBounds(100, 100, 900, 700)
        assertEquals(b, WindowPlacementRules.resolve(b, listOf(main)))
    }

    @Test
    fun noSavedBoundsOrScreensMeansDefault() {
        assertNull(WindowPlacementRules.resolve(null, listOf(main)))
        assertNull(WindowPlacementRules.resolve(WindowBounds(0, 0, 900, 700), emptyList()))
    }

    @Test
    fun aDisconnectedScreenFallsBackToDefault() {
        assertNull(WindowPlacementRules.resolve(WindowBounds(2500, 100, 900, 700), listOf(main)))
        assertNull(WindowPlacementRules.resolve(WindowBounds(-3000, 100, 900, 700), listOf(main)))
    }

    @Test
    fun barelyVisibleFallsBackButEnoughIsPulledIn() {
        assertNull(WindowPlacementRules.resolve(WindowBounds(1900, 100, 900, 700), listOf(main)))
        // Half off the right edge: moved back inside.
        assertEquals(WindowBounds(1020, 100, 900, 700), WindowPlacementRules.resolve(WindowBounds(1500, 100, 900, 700), listOf(main)))
        // Title bar above the menu bar: moved down.
        assertEquals(WindowBounds(100, 25, 900, 700), WindowPlacementRules.resolve(WindowBounds(100, -300, 900, 700), listOf(main)))
    }

    @Test
    fun neverBelowTheMinimumAndNeverBiggerThanTheScreen() {
        assertEquals(WindowBounds(50, 50, 360, 480), WindowPlacementRules.resolve(WindowBounds(50, 50, 100, 100), listOf(main)))
        assertEquals(WindowBounds(0, 25, 1920, 1055), WindowPlacementRules.resolve(WindowBounds(-200, 0, 5000, 5000), listOf(main)))
    }

    @Test
    fun usesTheScreenItMostlySitsOn() {
        val b = WindowBounds(1800, 100, 900, 700)
        // Mostly on the right-hand screen: pulled fully onto it.
        assertEquals(WindowBounds(1920, 100, 900, 700), WindowPlacementRules.resolve(b, listOf(main, right)))
        assertEquals(WindowBounds(2000, 100, 900, 700), WindowPlacementRules.resolve(WindowBounds(2000, 100, 900, 700), listOf(main, right)))
    }

    @Test
    fun keepsTheMaximisedFlag() {
        assertEquals(true, WindowPlacementRules.resolve(WindowBounds(0, 25, 900, 700, true), listOf(main))?.maximized)
    }
}
