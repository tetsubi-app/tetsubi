package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.playback.AudioFormat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where an album download stands, as saved in its manifest. */
@Serializable
enum class ManifestStatus {
    @SerialName("downloading") DOWNLOADING,
    @SerialName("paused") PAUSED,
    @SerialName("failed") FAILED,
    @SerialName("complete") COMPLETE,
}

/** One track of a downloaded album. The real title is here: file names lose punctuation. */
@Serializable
data class ManifestTrack(
    /** The download number (the `NN` of the file name). */
    val number: Int,
    val trackId: String,
    val title: String,
    /** File name in the album folder, e.g. "01 - Title.flac". */
    val file: String,
    val durationSeconds: Double = 0.0,
    val normalizationGain: Double? = null,
    /** e.g. "FLAC 905 kbps". */
    val qualityLabel: String? = null,
    /** The track has its own picture, saved in `covers/`. */
    val hasOwnArt: Boolean = false,
    /** The file's format, e.g. "ALAC"; null in manifests written before it was saved. */
    val format: String? = null,
) {
    /** The file's format: saved, else the first word of the quality label ("ALAC 1160 kbps"). */
    val fileFormat: AudioFormat?
        get() = (format ?: qualityLabel?.substringBefore(' '))
            ?.let { name -> AudioFormat.entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) } }
}

/**
 * `album.json` in each album folder. iOS keeps this in UserDefaults; here it
 * travels with the files. Tracks are in play order.
 */
@Serializable
data class DownloadManifest(
    val albumId: String,
    val title: String,
    val artist: String,
    val year: Int? = null,
    /** The chosen format's name ("flac", …) or "original". */
    val format: String = ORIGINAL,
    /** Epoch ms, set when complete. */
    val downloadedAt: Long? = null,
    val status: ManifestStatus = ManifestStatus.DOWNLOADING,
    val error: String? = null,
    val expectedTracks: Int = 0,
    val tracks: List<ManifestTrack> = emptyList(),
) {
    /** The chosen format, null for the original. */
    val preferredFormat: AudioFormat? get() = formatFromName(format)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val ORIGINAL = "original"

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(text: String): DownloadManifest? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        /** "flac" → FLAC; "original", missing or unknown → null (as iOS's raw values). */
        fun formatFromName(name: String?): AudioFormat? = AudioFormat.entries.firstOrNull { it.name.lowercase() == name?.lowercase() }

        fun formatName(format: AudioFormat?): String = format?.name?.lowercase() ?: ORIGINAL

        /** Downloaded = the manifest says complete and every listed file is there. */
        fun isDownloaded(manifest: DownloadManifest?, fileExists: (String) -> Boolean): Boolean =
            manifest != null && manifest.status == ManifestStatus.COMPLETE && manifest.tracks.isNotEmpty() &&
                manifest.tracks.all { fileExists(it.file) }
    }
}
