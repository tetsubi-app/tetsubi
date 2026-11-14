package gg.dekiru.musicplayer.data.artist

import java.io.File
import java.io.IOException
import java.net.URLEncoder

/** One JSON file per artist in `<cache>/ArtistInfo/`. */
class FileArtistInfoCache(cacheDir: String) : ArtistInfoCache {
    private val dir = File(cacheDir, "ArtistInfo")

    // The charset *name*: encode(String, Charset) is Android 13+ only and crashed Android 8–12.
    private fun file(key: String) = File(dir, URLEncoder.encode(key, "UTF-8") + ".json")

    override fun load(key: String): String? = try {
        file(key).takeIf { it.isFile }?.readText()
    } catch (e: IOException) {
        null
    }

    override fun save(key: String, text: String) {
        try {
            dir.mkdirs()
            file(key).writeText(text)
        } catch (e: IOException) {
            // Not cached; fetched again next time.
        }
    }
}
