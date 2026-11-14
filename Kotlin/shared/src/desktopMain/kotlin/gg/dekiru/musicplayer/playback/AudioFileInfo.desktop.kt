package gg.dekiru.musicplayer.playback

/** No downloads on the desktop yet (the Linux phase): nothing to read. */
internal actual fun audioFileInfo(path: String): AudioFileInfo = AudioFileInfo(null, null)
