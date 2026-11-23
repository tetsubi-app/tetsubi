package gg.dekiru.musicplayer.android.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.util.UnstableApi
import gg.dekiru.musicplayer.android.MainActivity
import gg.dekiru.musicplayer.android.R
import gg.dekiru.musicplayer.widget.WidgetContent

/**
 * The home-screen widget (as iOS's): small, the art, title, finish time
 * and Play/Pause; medium, the art with title, artist, album, duration and
 * finish time, and Previous, Play/Pause and Next (no skips in Maniac
 * Mode). Dark and rounded like the mini player, the button and a soft
 * tint in the art's key colour. Tapping anywhere else opens the app on
 * Now Playing; with nothing to show, the Tetsubi mark and "Nothing playing"
 * (or "Open Tetsubi to sign in").
 *
 * Laid out for its real size ([SizeMode.Exact], [WidgetLayout]): on a big
 * home screen the art and text grow with it. It only reads what the app
 * saved ([WidgetFiles]); the app redraws it when that changes, and once a
 * minute while paused so the finish time keeps up ([WidgetFeed]). No
 * progress bar, as iOS.
 */
@UnstableApi
class TetsubiWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val files = WidgetFiles(context)
        provideContent {
            val changes by WidgetFeed.changes.collectAsState()
            val running by WidgetFeed.running.collectAsState()
            val minute by WidgetFeed.minute.collectAsState()
            val shown = remember(changes, running, minute) {
                WidgetContent.of(files.read(), playerAlive = running, now = System.currentTimeMillis())
            }
            val artFile = (shown as? WidgetContent.Track)?.artFile
            val art = remember(artFile) { artFile?.let { files.roundedArt(it, ART_CORNER) } }
            WidgetView(shown, art)
        }
    }

    companion object {
        /** The saved picture's corners, as a share of its side ([WidgetLayout.ART_CORNER]). */
        private const val ART_CORNER = WidgetLayout.ART_CORNER
    }
}

/** The mini player's dark blue. */
private val BASE = Color(0xFF101A33)
private val TEXT = Color.White
/** Text over the small widget's art and fade. */
private val OVER_ART = Color(0xE6FFFFFF)
private val OVER_ART_DIM = Color(0xBFFFFFFF)
private val SECONDARY = Color.White.copy(alpha = 0.7f)
private val TERTIARY = Color.White.copy(alpha = 0.5f)

/** The Play button's symbol on the key colour (which is always bright). */
private val ON_ACCENT = Color(0xFF000B25)

private fun color(rgb: Int) = Color(0xFF000000.toInt() or rgb)

/** `accent` washed into the dark background (iOS lays it over at 35 % fading to 5 %; this is about the middle). */
internal fun tinted(accent: Color, amount: Float = 0.2f) = Color(
    red = BASE.red + (accent.red - BASE.red) * amount,
    green = BASE.green + (accent.green - BASE.green) * amount,
    blue = BASE.blue + (accent.blue - BASE.blue) * amount,
)

/** Opens the app: on Now Playing when something's loaded, else Home. */
internal fun openAppIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)

@Composable
private fun WidgetView(shown: WidgetContent, art: Bitmap?) {
    val context = LocalContext.current
    val size = LocalSize.current
    val accent = (shown as? WidgetContent.Track)?.let { color(it.accentRGB) } ?: color(WidgetContent.TETSUBI_BLUE)
    val label = when (shown) {
        is WidgetContent.Track -> shown.description
        is WidgetContent.Message -> shown.text
    }
    val configuration = context.resources.configuration
    val layout = WidgetLayout.of(
        size.width.value,
        size.height.value,
        fontScale = configuration.fontScale,
        skips = (shown as? WidgetContent.Track)?.showSkips ?: true,
        phone = WidgetLayout.isPhone(size.width.value, configuration.smallestScreenWidthDp),
    )
    Box(
        background(BASE)
            .appWidgetBackground()
            .fillMaxSize()
            .clickable(actionStartActivity(openAppIntent(context)))
            .semantics { contentDescription = label },
    ) {
        // The art's colour washed over the navy, strongest at the top left, as iOS
        // (a flat mix of a warm colour into navy looked grey on the Pad).
        Image(
            ImageProvider(washBitmap(accent)), contentDescription = null,
            contentScale = ContentScale.FillBounds, modifier = GlanceModifier.fillMaxSize(),
        )
        when (shown) {
            is WidgetContent.Message -> MessageView(shown.text, minOf(size.width, size.height))
            is WidgetContent.Track -> {
                // The device's 12/24-hour setting.
                val clock = DateFormat.getTimeFormat(context)
                val format = { at: Long -> clock.format(java.util.Date(at)) }
                if (layout.medium) {
                    MediumView(shown, art, accent, layout, layout.mediumTimes(shown.mediumTimeLines(layout.twoLineTimes, format)))
                } else {
                    SmallView(shown, art, accent, layout, shown.timeLines(format))
                }
            }
        }
    }
}

/** A diagonal wash of [accent], 35 % opaque at the top left to 5 % at the bottom right (iOS's gradient). */
private fun washBitmap(accent: Color): Bitmap {
    val side = 96
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val argb = accent.toArgb()
    fun withAlpha(alpha: Float) = (argb and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
    val paint = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, side.toFloat(), side.toFloat(), withAlpha(0.35f), withAlpha(0.05f), android.graphics.Shader.TileMode.CLAMP,
        )
    }
    android.graphics.Canvas(bitmap).drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)
    return bitmap
}

/**
 * The rounded background in one plain colour. From Android 12 a colour
 * with the system's widget corners; a tinted drawable becomes an image
 * behind the content, which HyperOS's launcher showed as plain grey.
 * Before 12 Glance can't round a colour, so the rounded drawable, tinted.
 */
private fun background(color: Color): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        GlanceModifier
            .background(ColorProvider(color))
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
    } else {
        GlanceModifier.background(ImageProvider(R.drawable.widget_background), colorFilter = ColorFilter.tint(ColorProvider(color)))
    }

/** Nothing to show: the Tetsubi mark and the message, centred, sized for the widget. */
@Composable
private fun MessageView(text: String, side: Dp) {
    val mark = (side * 0.34f).coerceIn(44.dp, 120.dp)
    val textSize = (side.value * 0.07f).coerceIn(13f, 22f)
    Column(
        GlanceModifier.fillMaxSize().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(ImageProvider(R.drawable.splash_disc), contentDescription = null, modifier = GlanceModifier.size(mark))
        Spacer(GlanceModifier.height(mark * 0.18f))
        Text(text, style = TextStyle(color = ColorProvider(SECONDARY), fontSize = textSize.sp), maxLines = 2)
    }
}

/** Small: the art fills the widget; play/pause at the top right; the lines that fit over a dark fade at the bottom. */
@Composable
private fun SmallView(track: WidgetContent.Track, art: Bitmap?, accent: Color, layout: WidgetLayout, timeLines: Pair<String?, String?>) {
    Box(GlanceModifier.fillMaxSize()) {
        if (art != null) {
            Image(ImageProvider(art), contentDescription = null, contentScale = ContentScale.Crop, modifier = GlanceModifier.fillMaxSize())
            Image(ImageProvider(fade), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = GlanceModifier.fillMaxSize())
        }
        Column(GlanceModifier.fillMaxSize().padding(layout.padding.dp)) {
            Row(GlanceModifier.fillMaxWidth()) {
                // No art to fill the widget with: the placeholder at the top left.
                if (art == null) Art(null, layout.art.dp, layout.artCorner.dp)
                Spacer(GlanceModifier.defaultWeight())
                PlayPauseButton(track.isPlaying, track.isStarting, accent, layout.play.dp)
            }
            Spacer(GlanceModifier.defaultWeight())
            Text(track.title, style = TextStyle(color = ColorProvider(TEXT), fontSize = layout.titleSp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            if (layout.showArtist && track.artist.isNotBlank()) {
                Text(track.artist, style = TextStyle(color = ColorProvider(OVER_ART), fontSize = layout.secondarySp.sp), maxLines = 1)
            }
            if (layout.showAlbum && track.album.isNotBlank()) {
                Text(track.album, style = TextStyle(color = ColorProvider(OVER_ART_DIM), fontSize = layout.secondarySp.sp), maxLines = 1)
            }
            // Just the finish time (no duration on this size); nothing once it's out of date.
            val finish = timeLines.second
            if (layout.showTimes && finish != null) {
                Text(finish, style = TextStyle(color = ColorProvider(OVER_ART_DIM), fontSize = layout.timesSp.sp), maxLines = 1)
            }
        }
    }
}

/** Clear at the top to 85 % black at the bottom, from a quarter of the way down (iOS's fade), so text over the art reads. */
private val fade: Bitmap by lazy {
    val w = 8
    val h = 128
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val paint = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, h * 0.25f, 0f, h.toFloat(), 0x00000000, (0.85f * 255).toInt() shl 24, android.graphics.Shader.TileMode.CLAMP,
        )
    }
    android.graphics.Canvas(bitmap).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    bitmap
}

/**
 * Medium: art the full height on the left; title, artist, album, the times and
 * the buttons beside it. `times`: one line ("Duration: …   Ends: …"), or on a
 * phone the duration and the finish time on lines of their own.
 */
@Composable
private fun MediumView(track: WidgetContent.Track, art: Bitmap?, accent: Color, layout: WidgetLayout, times: List<String>) {
    Row(GlanceModifier.fillMaxSize().padding(layout.padding.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(art, layout.art.dp, layout.artCorner.dp)
        Spacer(GlanceModifier.width(layout.gap.dp))
        Column(GlanceModifier.defaultWeight().height(layout.art.dp)) {
            Text(track.title, style = TextStyle(color = ColorProvider(TEXT), fontSize = layout.titleSp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            if (layout.showArtist && track.artist.isNotBlank()) {
                Text(track.artist, style = TextStyle(color = ColorProvider(SECONDARY), fontSize = layout.secondarySp.sp), maxLines = 1)
            }
            if (layout.showAlbum && track.album.isNotBlank()) {
                Text(track.album, style = TextStyle(color = ColorProvider(TERTIARY), fontSize = layout.secondarySp.sp), maxLines = 1)
            }
            for (line in times) {
                Text(line, style = TextStyle(color = ColorProvider(TERTIARY), fontSize = layout.timesSp.sp), maxLines = 1)
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (track.showSkips) {
                    IconButton(R.drawable.ic_widget_previous, "Previous", actionRunCallback<PreviousAction>(), layout.skip.dp)
                    Spacer(GlanceModifier.width(layout.buttonGap.dp))
                }
                PlayPauseButton(track.isPlaying, track.isStarting, accent, layout.play.dp)
                if (track.showSkips) {
                    Spacer(GlanceModifier.width(layout.buttonGap.dp))
                    IconButton(R.drawable.ic_widget_next, "Next", actionRunCallback<NextAction>(), layout.skip.dp)
                }
            }
        }
    }
}

/** The saved picture, or the Tetsubi mark on a dark square when there's none. */
@Composable
private fun Art(art: Bitmap?, size: Dp, corner: Dp) {
    if (art != null) {
        Image(ImageProvider(art), contentDescription = null, contentScale = ContentScale.Crop, modifier = GlanceModifier.size(size).cornerRadius(corner))
    } else {
        Box(
            GlanceModifier.size(size).cornerRadius(corner)
                .background(ImageProvider(R.drawable.widget_art_placeholder), colorFilter = ColorFilter.tint(ColorProvider(BASE))),
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(R.drawable.splash_disc), contentDescription = null, modifier = GlanceModifier.size(size * 0.6f))
        }
    }
}

/** Play or Pause: a round button in the key colour. */
@Composable
private fun PlayPauseButton(isPlaying: Boolean, isStarting: Boolean, accent: Color, size: Dp) {
    Box(
        GlanceModifier.size(size)
            .background(ImageProvider(R.drawable.widget_circle), colorFilter = ColorFilter.tint(ColorProvider(accent)))
            .clickable(actionRunCallback<PlayPauseAction>()),
        contentAlignment = Alignment.Center,
    ) {
        // Starting: a spinner in the same circle (a press then does nothing).
        if (isStarting) {
            CircularProgressIndicator(color = ColorProvider(ON_ACCENT), modifier = GlanceModifier.size(size * 0.5f))
        } else {
            Image(
                ImageProvider(if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play),
                contentDescription = if (isPlaying) "Pause" else "Play",
                colorFilter = ColorFilter.tint(ColorProvider(ON_ACCENT)),
                modifier = GlanceModifier.size(size * 0.55f),
            )
        }
    }
}

/** Previous or Next: a white symbol with a round touch area. */
@Composable
private fun IconButton(icon: Int, label: String, action: Action, size: Dp) {
    Box(GlanceModifier.size(size).clickable(action), contentAlignment = Alignment.Center) {
        Image(
            ImageProvider(icon),
            contentDescription = label,
            colorFilter = ColorFilter.tint(ColorProvider(TEXT)),
            modifier = GlanceModifier.size(size * 0.6f),
        )
    }
}
