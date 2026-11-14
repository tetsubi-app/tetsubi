package gg.dekiru.musicplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import coil3.compose.setSingletonImageLoaderFactory
import gg.dekiru.musicplayer.ui.AppBackground
import gg.dekiru.musicplayer.ui.TetsubiTheme
import gg.dekiru.musicplayer.ui.SignedInApp
import gg.dekiru.musicplayer.ui.components.tetsubiImageLoader
import gg.dekiru.musicplayer.ui.signin.SignInScreen

/**
 * The whole app, shared by Android and Linux. Restores the saved login
 * before the first frame (no network, so no sign-in flash); signed out, it
 * shows the sign-in screen.
 */
@Composable
fun App(session: AppSession) {
    setSingletonImageLoaderFactory { context -> tetsubiImageLoader(context, session.environment.cacheDir) }
    remember(session) { session.restore() }
    val api by session.api.collectAsState()

    TetsubiTheme {
        AppBackground {
            val signedIn = api
            if (signedIn == null) {
                SignInScreen(session.authenticator, onConnected = session::signIn)
            } else {
                SignedInApp(session, signedIn)
            }
        }
    }
}
