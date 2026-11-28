package gg.dekiru.musicplayer.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.ui.TetsubiColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The art as a bitmap whose pixels can be read (a software bitmap on Android), through the app's image cache. */
internal expect suspend fun loadReadableArt(context: PlatformContext, url: String, authorization: String): ImageBitmap?

/** The art shrunk to [side]×[side], as ARGB pixels. */
private fun samplePixels(image: ImageBitmap, side: Int = ArtPalette.SAMPLE_SIZE): IntArray {
    val small = ImageBitmap(side, side)
    Canvas(small).drawImageRect(
        image, IntOffset.Zero, IntSize(image.width, image.height),
        IntOffset.Zero, IntSize(side, side), Paint().apply { filterQuality = FilterQuality.Medium },
    )
    return IntArray(side * side).also { small.readPixels(it) }
}

/**
 * Now Playing's progress colour from the shown picture ([itemId]: the
 * track's own or the album's; [localPath]: a downloaded one, used instead),
 * as iOS. Null (the usual blue) until it's worked out, or when the art has
 * no suitable colour.
 */
@Composable
fun rememberArtAccent(api: JellyfinApi, itemId: String?, localPath: String? = null): Color? {
    val context = LocalPlatformContext.current
    var accent by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(itemId, localPath) {
        accent = itemId?.let { id ->
            // The same size Now Playing shows, so normally already cached.
            val url = localPath ?: api.imageUrl(id, 600)
            val image = loadReadableArt(context, url, api.authorizationHeader()) ?: return@let null
            artAccent(image)?.let { Color(it.red.toFloat(), it.green.toFloat(), it.blue.toFloat()) }
        }
    }
    return accent
}

/**
 * The colour Now Playing and the mini player use for progress, active toggle icons and the artist
 * link: the art's accent ([ArtPalette] already lifts it to a legible brightness), or the brand blue
 * until it's worked out or when the art has none.
 */
fun resolveAccent(art: Color?): Color = art ?: TetsubiColors.blue

/** [resolveAccent] fading as the art changes (as the background does). */
@Composable
fun rememberAnimatedAccent(art: Color?): Color =
    animateColorAsState(resolveAccent(art), tween(300), label = "accent").value

/**
 * The art at `url` through the app's image cache, as a bitmap whose pixels
 * can be read; null when it can't be had. For the home-screen widget, which
 * saves a small copy and works out the key colour from it.
 */
suspend fun loadArtImage(context: PlatformContext, url: String, authorization: String): ImageBitmap? =
    loadReadableArt(context, url, authorization)

/** The art's key colour, worked out exactly as Now Playing does; null when it has none. */
suspend fun artAccent(image: ImageBitmap): ArtPalette.Rgb? =
    withContext(Dispatchers.Default) { ArtPalette.accentColor(samplePixels(image)) }

