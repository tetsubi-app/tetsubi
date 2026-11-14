package gg.dekiru.musicplayer.ui.nowplaying

import androidx.compose.runtime.Composable

@Composable
actual fun rememberOutputSwitcher(): (() -> Unit)? = null

@Composable
actual fun rememberExternalOutput(): Boolean = false
