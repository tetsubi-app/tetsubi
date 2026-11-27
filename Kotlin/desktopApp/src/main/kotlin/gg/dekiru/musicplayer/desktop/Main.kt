package gg.dekiru.musicplayer.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import gg.dekiru.musicplayer.App
import gg.dekiru.musicplayer.AppSession
import gg.dekiru.musicplayer.data.auth.Authenticator
import gg.dekiru.musicplayer.data.auth.FileSecretStore
import gg.dekiru.musicplayer.data.auth.desktopDeviceName
import gg.dekiru.musicplayer.data.settings.Preferences
import gg.dekiru.musicplayer.data.settings.ScreenArea
import gg.dekiru.musicplayer.data.settings.WindowBounds
import gg.dekiru.musicplayer.data.settings.WindowPlacementRules
import gg.dekiru.musicplayer.data.settings.desktopEnvironment
import gg.dekiru.musicplayer.resources.Res
import gg.dekiru.musicplayer.resources.disc
import org.jetbrains.compose.resources.painterResource
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Toolkit

/** The connected screens' usable areas (less taskbar, dock or menu bar), in points. */
private fun screenAreas(): List<ScreenArea> = try {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        val configuration = device.defaultConfiguration
        val bounds = configuration.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        ScreenArea(
            bounds.x + insets.left, bounds.y + insets.top,
            bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom,
        )
    }
} catch (_: Exception) {
    emptyList() // headless or no display: the centred default
}

private fun initialWindowState(preferences: Preferences): WindowState {
    val saved = WindowPlacementRules.resolve(WindowBounds.decode(preferences.getString(WindowBounds.KEY)), screenAreas())
    return if (saved == null) {
        WindowState(
            position = WindowPosition(Alignment.Center),
            size = DpSize(WindowPlacementRules.DEFAULT_WIDTH.dp, WindowPlacementRules.DEFAULT_HEIGHT.dp),
        )
    } else {
        WindowState(
            placement = if (saved.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = WindowPosition(saved.x.dp, saved.y.dp),
            size = DpSize(saved.width.dp, saved.height.dp),
        )
    }
}

/** Writes the window's floating bounds (kept while maximised, so un-maximising returns to them) and whether it is maximised. */
private fun saveWindow(preferences: Preferences, state: WindowState, lastFloating: WindowBounds?): WindowBounds? {
    val maximized = state.placement == WindowPlacement.Maximized
    val position = state.position
    val bounds = if (!maximized && position is WindowPosition.Absolute) {
        WindowBounds(position.x.value.toInt(), position.y.value.toInt(), state.size.width.value.toInt(), state.size.height.value.toInt())
    } else {
        lastFloating?.copy(maximized = maximized)
    }
    if (bounds != null) preferences.putString(WindowBounds.KEY, bounds.copy(maximized = maximized).encode())
    return bounds?.copy(maximized = false)
}

@OptIn(FlowPreview::class)
fun main() {
    val environment = desktopEnvironment()
    val session = AppSession(Authenticator(FileSecretStore.default(), desktopDeviceName()), environment)
    application {
        val state = remember { initialWindowState(environment.preferences) }
        val floating = remember {
            arrayOf(WindowBounds.decode(environment.preferences.getString(WindowBounds.KEY))?.copy(maximized = false))
        }
        // Saved a moment after a resize or move stops, and again when the window closes.
        LaunchedEffect(state) {
            snapshotFlow { Triple(state.size, state.position, state.placement) }
                .debounce(500)
                .collect { floating[0] = saveWindow(environment.preferences, state, floating[0]) }
        }
        Window(
            onCloseRequest = {
                floating[0] = saveWindow(environment.preferences, state, floating[0])
                // The queue's last point, written before the app goes (a second at most).
                runBlocking { withTimeoutOrNull(1_000) { session.saveQueue() } }
                // Stops libmpv cleanly before the app goes.
                (environment.playerEngine as? AutoCloseable)?.close()
                exitApplication()
            },
            title = "Tetsubi",
            state = state,
            icon = painterResource(Res.drawable.disc),
        ) {
            // Narrow enough for a phone-sized window (the layout follows the width, as on Android).
            window.minimumSize = Dimension(WindowPlacementRules.MIN_WIDTH, WindowPlacementRules.MIN_HEIGHT)
            App(session)
        }
    }
}
