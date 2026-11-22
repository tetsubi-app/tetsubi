package gg.dekiru.musicplayer.android.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The widget's measurements for its real size: it grows with the cells and nothing spills over. */
class WidgetLayoutTest {

    /** Everything in the small layout fits inside the widget. */
    private fun assertSmallFits(width: Float, height: Float, fontScale: Float = 1f) {
        val l = WidgetLayout.of(width, height, fontScale)
        assertFalse(l.medium, "$width×$height")
        assertTrue(l.padding * 2 + l.art + l.gap + l.play <= width + 0.01f, "row fits $width×$height: $l")
        val text = l.titleSp * fontScale * WidgetLayout.LINE + (if (l.showTimes) l.timesSp * fontScale * WidgetLayout.LINE else 0f)
        assertTrue(l.padding * 2 + l.art + l.gap + text <= height + 0.01f, "column fits $width×$height: $l")
    }

    /** Everything in the medium layout fits inside the widget. */
    private fun assertMediumFits(width: Float, height: Float, fontScale: Float = 1f, skips: Boolean = true, phone: Boolean = false) {
        val l = WidgetLayout.of(width, height, fontScale, skips, phone)
        assertTrue(l.medium, "$width×$height")
        assertTrue(l.padding * 2 + l.art <= height + 0.01f, "art fits $width×$height: $l")
        val column = width - 2 * l.padding - l.art - l.gap
        val buttons = if (skips) l.play + 2 * l.skip + 2 * l.buttonGap else l.play
        assertTrue(buttons <= column + 0.01f, "buttons fit $width×$height: $l")
        val lines = l.titleSp * fontScale * WidgetLayout.LINE +
            (if (l.showArtist) l.secondarySp * fontScale * WidgetLayout.LINE else 0f) +
            (if (l.showAlbum) l.secondarySp * fontScale * WidgetLayout.LINE else 0f) +
            (if (l.showTimes) l.timesSp * fontScale * WidgetLayout.LINE else 0f) +
            (if (l.showDuration) l.timesSp * fontScale * WidgetLayout.LINE else 0f)
        assertTrue(lines + l.play <= height - 2 * l.padding + 0.01f, "lines fit $width×$height: $l")
    }

    @Test
    fun aBigSquareCellGetsBigArtAndText() {
        // The Pad's 2×2: about 256 dp square.
        val l = WidgetLayout.of(256f, 256f)
        assertFalse(l.medium)
        // The art fills it; room for everything under the title (artist, album, finish), no duration.
        assertTrue(l.showArtist && l.showAlbum && l.showTimes, "all lines: $l")
        assertFalse(l.showDuration)
        assertTrue(l.titleSp >= 18f, "title ${l.titleSp}")
        assertTrue(l.play >= 60f, "button ${l.play}")
        assertTrue(l.showTimes)
        assertSmallFits(256f, 256f)
    }

    @Test
    fun aPhonesSmallWidgetStaysAsBefore() {
        val l = WidgetLayout.of(110f, 110f)
        assertFalse(l.medium)
        assertEquals(13f, l.titleSp)
        assertSmallFits(110f, 110f)
        assertSmallFits(150f, 150f)
        assertSmallFits(180f, 120f)
        assertSmallFits(120f, 180f)
        // Large text.
        assertSmallFits(110f, 110f, fontScale = 1.3f)
    }

    @Test
    fun wideIsMedium() {
        assertTrue(WidgetLayout.of(250f, 110f).medium)
        assertTrue(WidgetLayout.of(512f, 256f).medium)
        // A big square or a narrow strip stays small.
        assertFalse(WidgetLayout.of(256f, 256f).medium)
        assertFalse(WidgetLayout.of(180f, 100f).medium)
    }

    @Test
    fun aBigMediumHasTheArtTheFullHeightAndEveryLine() {
        // The Pad's 4×2.
        val l = WidgetLayout.of(512f, 256f)
        assertEquals(256f - 2 * l.padding, l.art, 0.01f)
        assertTrue(l.showArtist && l.showAlbum && l.showTimes)
        assertTrue(l.titleSp >= 20f)
        assertMediumFits(512f, 256f)
    }

    @Test
    fun aShortMediumLeavesOutTheAlbumFirst() {
        val l = WidgetLayout.of(250f, 110f)
        assertTrue(l.showArtist)
        assertFalse(l.showAlbum)
        assertMediumFits(250f, 110f)
        assertMediumFits(250f, 110f, skips = false)
        assertMediumFits(300f, 130f)
        assertMediumFits(360f, 180f)
        assertMediumFits(250f, 110f, fontScale = 1.3f)
    }

    @Test
    fun phonesAndTabletsAreToldApartByTheWidgetAndTheDevice() {
        // Phones: the reported 394 dp 4×2, and the narrowest common phone.
        assertTrue(WidgetLayout.isPhone(394f, 394))
        assertTrue(WidgetLayout.isPhone(360f, 360))
        // The Pad's 4×2 is only 512 dp wide, but it's a tablet.
        assertFalse(WidgetLayout.isPhone(512f, 800))
        // Any widget this wide keeps the tablet's layout.
        assertFalse(WidgetLayout.isPhone(600f, 411))
    }

    @Test
    fun aPhonesMediumHasSmallerTextAndEveryLine() {
        val tablet = WidgetLayout.of(394f, 190f)
        val l = WidgetLayout.of(394f, 190f, phone = true)
        assertTrue(l.medium && l.twoLineTimes)
        assertFalse(tablet.twoLineTimes || tablet.showDuration)
        // Stepped down from the tablet's sizes for the same widget, to at most 16, 14 and 12 sp.
        assertTrue(l.titleSp < tablet.titleSp && l.secondarySp < tablet.secondarySp && l.timesSp < tablet.timesSp, "$l vs $tablet")
        assertEquals(16f, l.titleSp)
        assertEquals(14f, l.secondarySp)
        assertEquals(12f, l.timesSp)
        // Title, artist, album, duration and finish time all beside the art.
        assertTrue(l.showArtist && l.showAlbum && l.showDuration && l.showTimes, "$l")
        assertMediumFits(394f, 190f, phone = true)
        assertMediumFits(360f, 170f, phone = true)
        assertTrue(WidgetLayout.of(360f, 170f, phone = true).let { it.showAlbum && it.showDuration && it.showTimes })
        assertMediumFits(360f, 150f, phone = true)
        assertMediumFits(300f, 120f, phone = true)
        assertMediumFits(394f, 190f, fontScale = 1.3f, phone = true)
        // The text column at 360 dp still holds "Duration: 1h 45m 05s" at 12 sp (about 0.6 em a character).
        val narrow = WidgetLayout.of(360f, 170f, phone = true)
        val column = 360f - 2 * narrow.padding - narrow.art - narrow.gap
        assertTrue("Duration: 1h 45m 05s".length * narrow.timesSp * 0.6f <= column, "column $column: $narrow")
    }

    @Test
    fun aPhonesMediumShowsTheTimesOnTwoLinesAndKeepsTheFinishTimeWhenShort() {
        val both = listOf("Duration: 24m 10s", "Ends: 09:48")
        assertEquals(both, WidgetLayout.of(394f, 190f, phone = true).mediumTimes(both))
        // Paused with the app closed: only the duration.
        assertEquals(listOf("Duration: 24m 10s"), WidgetLayout.of(394f, 190f, phone = true).mediumTimes(listOf("Duration: 24m 10s")))
        // Short of room: the duration's line goes before the finish time's.
        val short = WidgetLayout.of(394f, 190f, phone = true).copy(showDuration = false)
        assertEquals(listOf("Ends: 09:48"), short.mediumTimes(both))
        // A tablet: the one line.
        val line = listOf("Duration: 24m 10s   Ends: 09:48")
        assertEquals(line, WidgetLayout.of(512f, 256f).mediumTimes(line))
        assertEquals(emptyList(), WidgetLayout.of(512f, 256f).copy(showTimes = false).mediumTimes(line))
    }
}
