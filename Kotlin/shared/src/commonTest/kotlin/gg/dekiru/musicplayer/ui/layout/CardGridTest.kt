package gg.dekiru.musicplayer.ui.layout

import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardGridTest {
    // Content widths: 358 is a 390 dp phone less 2 x 16; 658 is a 706 dp window less 2 x 24; 976 is the Expanded content width.
    private fun columns(width: Float, density: Density, gutter: Float) = gridColumns(width, density, gutter)

    @Test
    fun mediumDensityColumns() {
        assertEquals(2, columns(358f, Density.M, 12f))
        assertEquals(4, columns(658f, Density.M, 16f))
        assertEquals(4, columns(706f, Density.M, 16f))
        assertEquals(5, columns(976f, Density.M, 20f))
    }

    @Test
    fun smallDensityColumns() {
        assertEquals(3, columns(358f, Density.S, 12f))
        assertEquals(5, columns(658f, Density.S, 16f))
        // Taken as a raw content width, 706 fits a sixth 104 dp card exactly.
        assertEquals(6, columns(706f, Density.S, 16f))
        assertEquals(8, columns(976f, Density.S, 20f))
    }

    @Test
    fun largeDensityColumns() {
        assertEquals(2, columns(358f, Density.L, 12f))
        assertEquals(3, columns(658f, Density.L, 16f))
        assertEquals(3, columns(706f, Density.L, 16f))
        assertEquals(4, columns(976f, Density.L, 20f))
    }

    @Test
    fun neverFewerThanTwoColumns() {
        assertEquals(2, columns(200f, Density.L, 12f))
        assertEquals(2, columns(0f, Density.M, 12f))
    }

    @Test
    fun cardsFillTheContentWidthExactly() {
        for (density in Density.entries) for (width in listOf(200f, 358f, 658f, 976f, 1500f)) {
            val columns = columns(width, density, 16f)
            val card = cardWidth(width, columns, 16f)
            assertEquals(width, card * columns + 16f * (columns - 1), 0.01f)
        }
        assertEquals(173f, cardWidth(358f, 2, 12f), 0.001f)
    }

    @Test
    fun cardsAreNeverNarrowerThanTheDensityAsksFor() {
        for (density in Density.entries) for (width in listOf(358f, 658f, 976f, 1500f)) {
            val columns = columns(width, density, 16f)
            // Above the two-column floor, the card is at least the minimum.
            if (columns > 2) assertTrue(cardWidth(width, columns, 16f) >= density.minCard)
        }
    }

    @Test
    fun shelfCardsPeekOnCompactAndFillTheGridElsewhere() {
        // Compact: two whole cards and 35% of a third reach the window edge (390 wide).
        val compact = shelfCardWidth(358f, 2, 12f, 16f, peek = true)
        assertEquals(390f, 16f + 2 * compact + 2 * 12f + SHELF_PEEK * compact, 0.01f)
        // Medium and Expanded: exactly the grid's card.
        assertEquals(cardWidth(658f, 4, 16f), shelfCardWidth(658f, 4, 16f, 24f, peek = false), 0.001f)
    }

    @Test
    fun densityIsRemembered() {
        val prefs = InMemoryPreferences()
        assertEquals(Density.M, DensityState(prefs).density)
        DensityState(prefs).density = Density.L
        assertEquals("L", prefs.getString(DensityState.KEY))
        assertEquals(Density.L, DensityState(prefs).density)
    }

    @Test
    fun anUnknownOrOldValueIsMedium() {
        val prefs = InMemoryPreferences()
        prefs.putString(DensityState.KEY, "XL")
        assertEquals(Density.M, DensityState(prefs).density)
        // The old GridZoom column count is ignored.
        val old = InMemoryPreferences()
        old.putString("library.gridColumns", "6")
        assertEquals(Density.M, DensityState(old).density)
    }

    @Test
    fun minimumCardSizes() {
        assertEquals(104f, Density.S.minCard)
        assertEquals(148f, Density.M.minCard)
        assertEquals(200f, Density.L.minCard)
    }

    @Test
    fun listsFlowIntoTwoColumnsFrom700() {
        assertEquals(1, listColumns(390f))
        assertEquals(1, listColumns(699f))
        assertEquals(2, listColumns(700f))
        assertEquals(2, listColumns(1280f))
    }

    @Test
    fun theStripKeepsAnEightDpGapFromTheGrid() {
        // Strip 22 + gap 8 = 30 on the right everywhere: wider than every page margin (16, 24, 32 except Expanded's 32).
        assertEquals(30f, gridEndMargin(16f, 22f), 0.001f)
        assertEquals(30f, gridEndMargin(24f, 22f), 0.001f)
        assertEquals(32f, gridEndMargin(32f, 22f), 0.001f)
        // No strip: just the margin.
        assertEquals(16f, gridEndMargin(16f), 0.001f)
        assertEquals(658f, gridContentWidth(706f, 24f), 0.001f)
        // Compact: 390 - 16 - 30.
        assertEquals(344f, gridContentWidth(390f, 16f, 22f), 0.001f)
        assertEquals(2, columns(gridContentWidth(390f, 16f, 22f), Density.M, 12f))
        // Medium: 706 - 24 - 30 = 652, still four columns.
        assertEquals(652f, gridContentWidth(706f, 24f, 22f), 0.001f)
        assertEquals(4, columns(gridContentWidth(706f, 24f, 22f), Density.M, 16f))
        // Expanded: the 32 margin already holds strip + gap.
        assertEquals(976f, gridContentWidth(1040f, 32f, 22f), 0.001f)
    }

    @Test
    fun pageControlsAreInlineFrom720() {
        assertEquals(false, gg.dekiru.musicplayer.ui.components.showsInlineControls(390f))
        assertEquals(false, gg.dekiru.musicplayer.ui.components.showsInlineControls(719f))
        assertEquals(true, gg.dekiru.musicplayer.ui.components.showsInlineControls(720f))
        assertEquals(true, gg.dekiru.musicplayer.ui.components.showsInlineControls(1280f))
    }

    @Test
    fun aControlMenuTicksTheSelectedChoiceAndSelectsOnTap() {
        var picked = ""
        val menu = gg.dekiru.musicplayer.ui.components.controlMenu("Sort", listOf("Name", "Year"), "Year", { it }) { picked = it }
        assertEquals("Year", menu.value)
        assertEquals(listOf(false, true), menu.choices.map { it.selected })
        menu.choices[0].onSelect()
        assertEquals("Name", picked)
    }
}
