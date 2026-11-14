package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.playback.AudioFormat

/**
 * Where downloads live on disk (as `DownloadPaths` and `TrackCovers` on iOS):
 *
 * ```
 * <root>/<albumId>/01 - Title.flac      a track (".part" while downloading)
 * <root>/<albumId>/cover.jpg            the album cover, 600 px
 * <root>/<albumId>/covers/01 - Title.jpg   a track's own picture
 * <root>/<albumId>/album.json           the manifest
 * ```
 */
class DownloadPaths(root: String) {

    val root: String = root.trimEnd('/')

    fun albumDir(albumId: String): String = "$root/${sanitize(albumId)}"

    fun trackPath(albumId: String, fileName: String): String = "${albumDir(albumId)}/$fileName"

    /** Where a track is written while it downloads. */
    fun partPath(albumId: String, fileName: String): String = trackPath(albumId, fileName) + PART

    fun manifestPath(albumId: String): String = "${albumDir(albumId)}/$MANIFEST"

    fun coverPath(albumId: String): String = "${albumDir(albumId)}/$COVER"

    fun trackCoversDir(albumId: String): String = "${albumDir(albumId)}/$COVERS"

    /** A track's own picture: `covers/<file name without extension>.jpg`. */
    fun trackCoverPath(albumId: String, fileName: String): String =
        "${trackCoversDir(albumId)}/${fileName.substringBeforeLast('.')}.jpg"

    companion object {
        const val MANIFEST = "album.json"
        const val COVER = "cover.jpg"
        const val COVERS = "covers"
        const val PART = ".part"

        /** "NN - Title.ext": `NN` is the download number, at least two digits. */
        fun trackFileName(number: Int, title: String, format: AudioFormat): String =
            "${number.toString().padStart(2, '0')} - ${sanitize(title)}.${format.fileExtension}"

        /** A file name without its extension. */
        fun stem(fileName: String): String = fileName.substringBeforeLast('.')

        /** Keeps letters, digits, `-`, `_`, space and `.` (as iOS: alphanumerics plus "-_ ."). */
        fun sanitize(input: String): String = buildString {
            for (c in input) if (c.isAllowed()) append(c)
        }

        private fun Char.isAllowed(): Boolean = this == '-' || this == '_' || this == ' ' || this == '.' || when (category) {
            CharCategory.UPPERCASE_LETTER, CharCategory.LOWERCASE_LETTER, CharCategory.TITLECASE_LETTER,
            CharCategory.MODIFIER_LETTER, CharCategory.OTHER_LETTER,
            CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK,
            CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER,
            -> true
            else -> false
        }
    }
}
