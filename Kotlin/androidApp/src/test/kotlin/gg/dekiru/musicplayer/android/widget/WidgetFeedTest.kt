package gg.dekiru.musicplayer.android.widget

import androidx.media3.common.util.UnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals

/** The key colour as the widget's snapshot stores it. */
@UnstableApi
class WidgetFeedTest {

    @Test
    fun keyColourIsSavedAsRgb() {
        assertEquals(0xFF0000, WidgetFeed.rgb(1.0, 0.0, 0.0))
        assertEquals(0x039FD6, WidgetFeed.rgb(3 / 255.0, 159 / 255.0, 214 / 255.0))
        // Out of range channels are clamped.
        assertEquals(0x00FF00, WidgetFeed.rgb(-0.2, 1.4, 0.0))
    }

    @Test
    fun updatesAtMostOnceASecond() {
        assertEquals(1_000L, WidgetFeed.THROTTLE_MILLIS)
    }
}
