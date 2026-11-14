package gg.dekiru.musicplayer.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import gg.dekiru.musicplayer.App
import gg.dekiru.musicplayer.AppSession
import gg.dekiru.musicplayer.data.auth.Authenticator
import gg.dekiru.musicplayer.data.auth.FileSecretStore
import gg.dekiru.musicplayer.data.auth.desktopDeviceName
import gg.dekiru.musicplayer.data.settings.desktopEnvironment
import gg.dekiru.musicplayer.resources.Res
import gg.dekiru.musicplayer.resources.disc
import org.jetbrains.compose.resources.painterResource
import java.awt.Dimension

fun main() {
    val session = AppSession(Authenticator(FileSecretStore.default(), desktopDeviceName()), desktopEnvironment())
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Tetsubi",
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
            icon = painterResource(Res.drawable.disc),
        ) {
            // Same minimum as the Mac app.
            window.minimumSize = Dimension(820, 560)
            App(session)
        }
    }
}
