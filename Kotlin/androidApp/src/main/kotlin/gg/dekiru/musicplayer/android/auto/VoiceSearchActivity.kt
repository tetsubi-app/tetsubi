package gg.dekiru.musicplayer.android.auto

import android.app.Activity
import android.app.SearchManager
import android.content.ComponentName
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import gg.dekiru.musicplayer.android.PlaybackService

/**
 * "Play <album> on Tetsubi" said to Google Assistant on the phone: Assistant
 * sends MEDIA_PLAY_FROM_SEARCH here. Nothing is shown; the request goes to
 * the playback service (the same handling as Android Auto's voice
 * requests) and, if nothing could play, the reason shows briefly in
 * iOS's words ("Open Tetsubi and sign in first.").
 */
@UnstableApi
class VoiceSearchActivity : Activity() {

    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            finish()
            return
        }
        val args = Bundle().apply {
            putString(AutoCallback.EXTRA_QUERY, intent.getStringExtra(SearchManager.QUERY).orEmpty())
            for (key in listOf(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.EXTRA_MEDIA_ALBUM, MediaStore.EXTRA_MEDIA_ARTIST, VoiceQuery.EXTRA_PLAYLIST)) {
                intent.getStringExtra(key)?.let { putString(key, it) }
            }
        }
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controller = future
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull()
            if (connected == null) {
                finish()
                return@addListener
            }
            val result = connected.sendCustomCommand(AutoCallback.PLAY_FROM_SEARCH_COMMAND, args)
            result.addListener({
                val answer = runCatching { result.get() }.getOrNull()
                if (answer != null && answer.resultCode != SessionResult.RESULT_SUCCESS) {
                    answer.sessionError?.message?.let { Toast.makeText(applicationContext, it, Toast.LENGTH_LONG).show() }
                }
                finish()
            }, mainExecutor())
        }, mainExecutor())
    }

    private fun mainExecutor() = ContextCompat.getMainExecutor(this)

    override fun onDestroy() {
        // Music started by the request carries on: the service keeps itself going while playing.
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onDestroy()
    }
}
