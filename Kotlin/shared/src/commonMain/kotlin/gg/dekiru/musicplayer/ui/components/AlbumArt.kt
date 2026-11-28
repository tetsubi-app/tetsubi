package gg.dekiru.musicplayer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.TetsubiHttp
import okio.Path.Companion.toPath

/**
 * Coil's image loader: album art through the app's HTTP client, decoded
 * images in memory (15% of the app's memory) and the files on disk (200 MB
 * in `<cache>/AlbumArt`, as on iOS), so art shows offline and on relaunch.
 */
fun tetsubiImageLoader(context: PlatformContext, cacheDir: String): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(KtorNetworkFetcherFactory(TetsubiHttp.client)) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.toPath() / "AlbumArt").maxSizeBytes(200L * 1024 * 1024).build() }
        .crossfade(true)
        .build()

/**
 * An item's cover art: a square, centre-cropped, with rounded corners
 * (as `AlbumArtView` on iOS). The pink/blue loading outline while it loads;
 * a music note on grey when there's no art. `maxWidth` is the size asked
 * of the server, in pixels. `localPath`: a downloaded picture, shown
 * instead of the server's.
 */
@Composable
fun AlbumArt(
    api: JellyfinApi,
    itemId: String,
    size: Dp,
    cornerRadius: Dp = 10.dp,
    maxWidth: Int = 300,
    modifier: Modifier = Modifier,
    localPath: String? = null,
    /** What shows when there is no picture (artists use the lighter surface, not grey). */
    placeholderColor: Color = Color.Gray.copy(alpha = 0.35f),
) {
    val context = LocalPlatformContext.current
    val request = remember(api, itemId, maxWidth, localPath) { artRequest(context, api, itemId, maxWidth, localPath) }
    val painter = rememberAsyncImagePainter(request)
    val state by painter.state.collectAsState()
    val shape = RoundedCornerShape(cornerRadius)

    Box(modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        // Always drawn: Coil starts loading once the image has a size.
        Image(painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        when (state) {
            is AsyncImagePainter.State.Loading, AsyncImagePainter.State.Empty -> LoadingOutline(cornerRadius)
            is AsyncImagePainter.State.Error -> ArtPlaceholder(size, placeholderColor)
            else -> Unit
        }
    }
}

/** The server's picture of `itemId` (with the auth header), or the file at `localPath` when given. */
fun artRequest(context: PlatformContext, api: JellyfinApi, itemId: String, maxWidth: Int, localPath: String? = null): ImageRequest =
    if (localPath != null) {
        ImageRequest.Builder(context).data(localPath).build()
    } else {
        ImageRequest.Builder(context)
            .data(api.imageUrl(itemId, maxWidth))
            .httpHeaders(NetworkHeaders.Builder().set("Authorization", api.authorizationHeader()).build())
            .build()
    }

/** No art: grey with a music note. */
@Composable
private fun ArtPlaceholder(size: Dp, color: Color) {
    Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) {
        Text("♪", color = Color.White.copy(alpha = 0.55f), fontSize = (maxOf(size.value * 0.28f, 16f)).sp)
    }
}
