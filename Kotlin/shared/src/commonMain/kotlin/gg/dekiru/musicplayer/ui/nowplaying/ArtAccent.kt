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
import gg.dekiru.musicplayer.logLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The art as a bitmap whose pixels can be read (a software bitmap on Android), through the app's image cache. */
internal expect suspend fun loadReadableArt(context: PlatformContext, url: String, authorization: String): ImageBitmap?

/** The art shrunk to [side]×[side], as ARGB pixels. */
internal fun samplePixels(image: ImageBitmap, side: Int = ArtPalette.SAMPLE_SIZE): IntArray {
    val small = ImageBitmap(side, side)
    Canvas(small).drawImageRect(
        image, IntOffset.Zero, IntSize(image.width, image.height),
        IntOffset.Zero, IntSize(side, side), Paint().apply { filterQuality = FilterQuality.Medium },
    )
    return IntArray(side * side).also { small.readPixels(it) }
}

/** What the accent work found for one picture, and why (for the `art accent:` log line). */
internal data class AccentResolution(val color: ArtPalette.Rgb?, val reason: String, val sampled: Int)

/** The accent for a picture's sampled [pixels] (null: the picture couldn't be had). Pure, so it is tested. */
internal fun resolveArtAccent(pixels: IntArray?): AccentResolution {
    if (pixels == null) return AccentResolution(null, "noBitmap", 0)
    val color = ArtPalette.accentColor(pixels)
    return AccentResolution(color, if (color == null) "noSuitableColour" else "ok", pixels.size)
}

private fun hex(value: Double): String = (value * 255).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')

/** `art accent: item=… source=file|server result=#RRGGBB|none reason=… sampled=…`: identifiers and numbers only. */
internal fun artAccentLine(itemId: String, fromFile: Boolean, r: AccentResolution): String =
    "art accent: item=$itemId source=${if (fromFile) "file" else "server"} " +
        "result=${r.color?.let { "#" + hex(it.red) + hex(it.green) + hex(it.blue) } ?: "none"} reason=${r.reason} sampled=${r.sampled}"

/** The accent worked out for one (item, file) pair; compared with the current pair so a late or stale one is never shown. */
private data class ResolvedAccent(val itemId: String?, val localPath: String?, val color: Color?)

/**
 * Now Playing's progress colour from the shown picture ([itemId]: the
 * track's own or the album's; [localPath]: a downloaded one, used instead),
 * as iOS. Null (the usual blue) while it is worked out, when the picture
 * can't be had, or when it has no suitable colour. It is never the previous
 * track's colour: a result only counts for the (id, path) it was worked out
 * for. Each resolution writes one `art accent:` log line.
 */
@Composable
fun rememberArtAccent(api: JellyfinApi, itemId: String?, localPath: String? = null): Color? =
    rememberArtAccentResult(api, itemId, localPath).color

/**
 * What [rememberArtAccent] knows for the current picture: [resolved] is false while it is still
 * being worked out (then [color] is null), true once it is known ([color] null: no suitable colour).
 */
internal data class ArtAccentResult(val resolved: Boolean, val color: Color?)

@Composable
internal fun rememberArtAccentResult(api: JellyfinApi, itemId: String?, localPath: String? = null): ArtAccentResult {
    val context = LocalPlatformContext.current
    var resolved by remember { mutableStateOf<ResolvedAccent?>(null) }
    LaunchedEffect(itemId, localPath) {
        if (itemId == null) {
            resolved = ResolvedAccent(null, localPath, null)
            return@LaunchedEffect
        }
        // The same size Now Playing shows, so normally already cached.
        val url = localPath ?: api.imageUrl(itemId, 600)
        val pixels = try {
            loadReadableArt(context, url, api.authorizationHeader())?.let { samplePixels(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val result = withContext(Dispatchers.Default) { resolveArtAccent(pixels) }
        logLine("Tetsubi", artAccentLine(itemId, localPath != null, result))
        resolved = ResolvedAccent(itemId, localPath, result.color?.let { Color(it.red.toFloat(), it.green.toFloat(), it.blue.toFloat()) })
    }
    return resolved?.takeIf { it.itemId == itemId && it.localPath == localPath }
        ?.let { ArtAccentResult(true, it.color) } ?: ArtAccentResult(false, null)
}

/**
 * The colour to draw for [result], fading (300 ms) between pictures: while the next picture's accent
 * is still being worked out the previous colour is held (no flash through blue), and a picture with no
 * suitable colour fades to the brand blue.
 */
@Composable
internal fun rememberHeldAccent(result: ArtAccentResult): Color {
    var held by remember { mutableStateOf(TetsubiColors.blue) }
    LaunchedEffect(result) { if (result.resolved) held = resolveAccent(result.color) }
    return animateColorAsState(held, tween(300), label = "accent").value
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
    withContext(Dispatchers.Default) { resolveArtAccent(samplePixels(image)).color }

