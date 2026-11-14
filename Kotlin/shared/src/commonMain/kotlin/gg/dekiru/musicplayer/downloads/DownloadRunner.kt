package gg.dekiru.musicplayer.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps download work alive. The manager calls [start] when albums are
 * queued; the runner must then call [DownloadManager.runQueue], which returns
 * when nothing is left (done, paused, failed or cancelled) and then calls [stop].
 * Android: WorkManager foreground work, so downloads go on with the app closed.
 */
interface DownloadRunner {
    fun start(manager: DownloadManager)
    fun stop()

    /** The network rule changed (mobile data on or off): start again under the new one. */
    fun restart(manager: DownloadManager) = start(manager)
}

/** Runs the queue in `scope` (desktop, tests). */
class InProcessDownloadRunner(private val scope: CoroutineScope) : DownloadRunner {
    var isRunning = false
        private set

    override fun start(manager: DownloadManager) {
        isRunning = true
        scope.launch { manager.runQueue() }
    }

    override fun stop() {
        isRunning = false
    }
}
