package gg.dekiru.musicplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import gg.dekiru.musicplayer.resources.Res
import gg.dekiru.musicplayer.resources.background
import org.jetbrains.compose.resources.painterResource

/**
 * The app's background picture (purple to navy, the same image as iOS's `AppBackground`),
 * filling the screen, on navy. Like `AppBackgroundView` on iOS; the blurred
 * album-art version comes with playback (Phase A3).
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(TetsubiColors.background)) {
        Image(
            painter = painterResource(Res.drawable.background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        content()
    }
}
