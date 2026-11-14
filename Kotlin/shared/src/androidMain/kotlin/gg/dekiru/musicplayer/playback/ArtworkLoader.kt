package gg.dekiru.musicplayer.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Album art for the notification and lock screen, through the app's image
 * loader: same caches as the screens, and it sends the `Authorization`
 * header Jellyfin needs (Media3's own loader can't).
 */
@UnstableApi
class ArtworkLoader(private val context: Context, private val authorization: () -> String?) : BitmapLoader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        BitmapFactory.decodeByteArray(data, 0, data.size)?.let { Futures.immediateFuture(it) }
            ?: Futures.immediateFailedFuture(IOException("Couldn't decode artwork"))

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        scope.launch {
            val request = ImageRequest.Builder(context)
                .data(uri.toString())
                .allowHardware(false) // the notification needs a software bitmap
                .size(512)
                .apply { authorization()?.let { httpHeaders(NetworkHeaders.Builder().set("Authorization", it).build()) } }
                .build()
            val result = SingletonImageLoader.get(context).execute(request)
            if (result is SuccessResult) future.set(result.image.toBitmap()) else future.setException(IOException("No artwork"))
        }
        return future
    }
}
