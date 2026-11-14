package gg.dekiru.musicplayer.android.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import gg.dekiru.musicplayer.widget.WidgetSnapshot
import java.io.File
import java.io.IOException

/**
 * Where the app leaves the home-screen widget's snapshot and art: the
 * app's own files, `widget/nowplaying.json` and `widget/art-<n>.jpg`
 * (≈512 px, sharp at a big home screen's widget size). Each picture gets its own name, so the widget can tell when it
 * changed. Reading never fails: a missing or broken file is "nothing".
 */
class WidgetFiles(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "widget")

    /** The last snapshot written, or null. */
    fun read(): WidgetSnapshot? =
        try {
            File(dir, SNAPSHOT).takeIf { it.isFile }?.readText()?.let(WidgetSnapshot::decode)
        } catch (e: IOException) {
            null
        }

    /** Writes the snapshot whole (a half-written file is never read). */
    fun write(snapshot: WidgetSnapshot) {
        dir.mkdirs()
        val temp = File(dir, "$SNAPSHOT.tmp")
        temp.writeText(snapshot.encode())
        if (!temp.renameTo(File(dir, SNAPSHOT))) {
            File(dir, SNAPSHOT).writeText(snapshot.encode())
            temp.delete()
        }
    }

    /**
     * Saves `art` as a small JPEG named after `key` (the picture's server
     * item or file), unless it's already there. Returns the file's name.
     */
    fun saveArt(key: String, art: Bitmap): String? {
        val name = "art-$ART_SIZE-${key.hashCode().toUInt().toString(16)}.jpg"
        val file = File(dir, name)
        if (file.isFile) return name
        return try {
            dir.mkdirs()
            val scale = ART_SIZE.toFloat() / maxOf(art.width, art.height)
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(art, maxOf(1, (art.width * scale).toInt()), maxOf(1, (art.height * scale).toInt()), true)
            } else {
                art
            }
            val temp = File(dir, "$name.tmp")
            temp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (small !== art) small.recycle()
            if (!temp.renameTo(file)) return null
            name
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save the widget's art", e)
            null
        }
    }

    /** Deletes saved pictures other than `keep`. */
    fun removeArtExcept(keep: String?) {
        dir.listFiles()?.filter { it.name.startsWith("art-") && it.name != keep }?.forEach { it.delete() }
    }

    /**
     * A saved picture with rounded corners (`corner`: the radius as a share of its side), or null
     * when it isn't there. The last one is kept, so each size of the widget
     * gets the same bitmap and it's sent to the home screen only once.
     */
    fun roundedArt(name: String, corner: Float): Bitmap? {
        synchronized(Companion) {
            lastRounded?.takeIf { it.first == name }?.let { return it.second }
        }
        val rounded = round(name, corner) ?: return null
        synchronized(Companion) { lastRounded = name to rounded }
        return rounded
    }

    private fun round(name: String, corner: Float): Bitmap? {
        val source = try {
            BitmapFactory.decodeFile(File(dir, name).path)
        } catch (e: Exception) {
            null
        } ?: return null
        val radius = minOf(source.width, source.height) * corner
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawRoundRect(RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()), radius, radius, paint)
        source.recycle()
        return out
    }

    private companion object {
        /** The last rounded picture, by file name. */
        var lastRounded: Pair<String, Bitmap>? = null

        const val TAG = "TetsubiWidget"
        const val SNAPSHOT = "nowplaying.json"

        /** The saved picture's longest side, in pixels. */
        const val ART_SIZE = 512
    }
}
