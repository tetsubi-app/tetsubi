package gg.dekiru.musicplayer.ui.nowplaying

import androidx.compose.runtime.Composable

/**
 * Opens the system's output picker (Bluetooth speakers, headphones, wired):
 * Android's output switcher. Null where there's none (desktop). Google
 * Cast has its own button ([rememberCastPicker]).
 */
@Composable
expect fun rememberOutputSwitcher(): (() -> Unit)?

/**
 * Whether music goes anywhere but this device's own speaker (Bluetooth,
 * wired or USB headphones, HDMI): the Output button then takes the art's
 * colour, as iOS's AirPlay button. Kept up to date while shown.
 */
@Composable
expect fun rememberExternalOutput(): Boolean
