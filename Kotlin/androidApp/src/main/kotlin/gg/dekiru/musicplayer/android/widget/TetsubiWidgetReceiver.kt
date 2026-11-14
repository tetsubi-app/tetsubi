package gg.dekiru.musicplayer.android.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.media3.common.util.UnstableApi

/**
 * The small (2×2) widget in the widget picker. Both sizes are the same
 * [TetsubiWidget], laid out for the space it's given; there are two so the
 * picker offers each size, as iOS.
 */
@UnstableApi
class SmallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TetsubiWidget()
}

/** The medium (4×2) widget in the widget picker. */
@UnstableApi
class MediumWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TetsubiWidget()
}
