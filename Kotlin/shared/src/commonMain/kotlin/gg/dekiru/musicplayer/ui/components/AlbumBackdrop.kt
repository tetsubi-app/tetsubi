package gg.dekiru.musicplayer.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.ui.TetsubiColors

/**
 * The PlexAmp-style "ultrablur" behind every screen while something is
 * loaded (as `AppBackgroundView` on iOS): the album art full-bleed, blurred
 * and scaled up to hide the soft edges, under a dark gradient so text stays
 * readable. The art is fetched tiny (64 px), so stretched up it is already
 * soft where `blur` does nothing (Android 8–11). Nothing when `albumId` is
 * null: the background picture shows. `localPath`: a downloaded picture,
 * used instead of the server's.
 */
@Composable
fun AlbumBackdrop(api: JellyfinApi, albumId: String?, modifier: Modifier = Modifier, localPath: String? = null) {
    val context = LocalPlatformContext.current
    // albumId: the picture shown (a track's own when it has one); a new one cross-fades.
    val shown = albumId?.let { it to localPath }
    Crossfade(shown, animationSpec = tween(400), modifier = modifier.fillMaxSize().clipToBounds(), label = "backdrop") { picture ->
        if (picture == null) return@Crossfade
        val (id, path) = picture
        val request = remember(api, id, path) { artRequest(context, api, id, 64, path) }
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().scale(1.4f).blur(60.dp),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(TetsubiColors.background.copy(alpha = 0.7f), TetsubiColors.background.copy(alpha = 0.4f), TetsubiColors.background.copy(alpha = 0f)),
                    ),
                ),
            )
        }
    }
}
