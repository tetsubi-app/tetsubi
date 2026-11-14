package gg.dekiru.musicplayer.ui.nowplaying

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

internal actual suspend fun loadReadableArt(context: PlatformContext, url: String, authorization: String): ImageBitmap? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .allowHardware(false) // pixels can't be read from a hardware bitmap
        .httpHeaders(NetworkHeaders.Builder().set("Authorization", authorization).build())
        .build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    return result.image.toBitmap().asImageBitmap()
}
