package gg.dekiru.musicplayer.ui.components

import androidx.compose.runtime.Composable

/**
 * Calls [onShake] when the device is shaken, while this is on screen (as
 * `onShake` on iOS). Returns false where there's no motion sensor (desktop),
 * so screens can leave out "Shake it".
 */
@Composable
expect fun ShakeEffect(onShake: () -> Unit): Boolean
