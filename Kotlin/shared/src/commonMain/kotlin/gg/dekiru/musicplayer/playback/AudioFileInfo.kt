package gg.dekiru.musicplayer.playback

/**
 * A downloaded file's sample rate (Hz) and bit depth, read from the file
 * itself (as iOS does), since downloads don't save them and a download may
 * have been converted. Lossy files (MP3, AAC, Opus) have no bit depth.
 * Nulls where the file can't be read or the platform can't tell.
 */
data class AudioFileInfo(val sampleRate: Int?, val bitDepth: Int?)

internal expect fun audioFileInfo(path: String): AudioFileInfo
