package gg.dekiru.musicplayer.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import gg.dekiru.musicplayer.App
import gg.dekiru.musicplayer.AppSession
import gg.dekiru.musicplayer.data.auth.Authenticator
import gg.dekiru.musicplayer.data.auth.desktopDeviceName
import gg.dekiru.musicplayer.data.auth.desktopSecretStore
import gg.dekiru.musicplayer.data.settings.desktopEnvironment
import gg.dekiru.musicplayer.playback.mpris.MprisBridge
import gg.dekiru.musicplayer.resources.Res
import gg.dekiru.musicplayer.resources.disc
import org.jetbrains.compose.resources.painterResource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Dimension
import java.awt.Frame
import java.io.File

fun main() {
    val environment = desktopEnvironment()
    // The keyring (Secret Service) where there is one, else a private file.
    val session = AppSession(Authenticator(desktopSecretStore(), desktopDeviceName()), environment)

    // Set once the window is up; MPRIS's Raise and Quit use them (on the main thread).
    var mainWindow: ComposeWindow? = null
    var quit: (() -> Unit)? = null

    // Media keys and the desktop's now-playing panel (MPRIS); null if there's no session bus.
    val mpris = MprisBridge.start(
        session,
        environment.playerEngine,
        File(environment.cacheDir),
        onRaise = {
            mainWindow?.apply {
                if (extendedState and Frame.ICONIFIED != 0) extendedState = extendedState and Frame.ICONIFIED.inv()
                isVisible = true
                toFront()
                requestFocus()
            }
        },
        onQuit = { quit?.invoke() },
    )

    application {
        quit = {
            mpris?.close()
            // The queue's last point, written before the app goes (a second at most).
            runBlocking { withTimeoutOrNull(1_000) { session.saveQueue() } }
            // Stops libmpv cleanly before the app goes.
            (environment.playerEngine as? AutoCloseable)?.close()
            exitApplication()
        }
        Window(
            onCloseRequest = { quit?.invoke() },
            title = "Tetsubi",
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
            icon = painterResource(Res.drawable.disc),
        ) {
            mainWindow = window
            // Same minimum as the Mac app.
            window.minimumSize = Dimension(820, 560)
            App(session)
        }
    }
}
