package gg.dekiru.musicplayer.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's Application implements this, so the download work and the
 * notification's buttons find the signed-in [DownloadManager] (restoring the
 * saved login when the process was started just for the work). Main thread.
 */
interface DownloadsHost {
    fun downloadManager(): DownloadManager?
}

/**
 * Runs downloads as WorkManager foreground work (unique work "downloads"),
 * so they go on with the app closed and carry on after the process is killed.
 */
class WorkManagerDownloadRunner(context: Context) : DownloadRunner {
    private val context = context.applicationContext

    override fun start(manager: DownloadManager) {
        // A worker already in its loop picks up the new album itself.
        if (workerActive) return
        enqueue(manager, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    /** Work still waiting for its network is replaced by work with the new rule. */
    override fun restart(manager: DownloadManager) {
        if (workerActive) return
        enqueue(manager, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(manager: DownloadManager, policy: ExistingWorkPolicy) {
        val network = if (DownloadNotice.needsUnmetered(manager.settings.allowCellular)) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        // start appends, not keeps: work that is just finishing would drop the new one.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    // The worker ends by itself when runQueue returns.
    override fun stop() {}

    companion object {
        const val WORK_NAME = "downloads"

        /** A worker is between its start and its last look at the queue (main thread only). */
        internal var workerActive = false
    }
}

/** Gets the manager from the app and runs its queue in the foreground, with the progress notification. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifications = DownloadNotifications(context)
    private var foreground = false

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.foregroundInfo(null)

    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        val host = applicationContext as? DownloadsHost ?: return@withContext Result.success()
        WorkManagerDownloadRunner.workerActive = true
        try {
            val manager = host.downloadManager() ?: return@withContext Result.success()
            coroutineScope {
                val shown = launch { showProgress(manager) }
                // Albums queued while runQueue was ending start another round;
                // stop if the manager was replaced (sign-out or another sign-in).
                do {
                    manager.runQueue()
                    delay(200)
                } while (host.downloadManager() === manager && DownloadNotice.hasQueued(manager.active.value))
                shown.cancel()
            }
            Result.success()
        } finally {
            WorkManagerDownloadRunner.workerActive = false
            if (!foreground) notifications.cancelProgress()
        }
    }

    private suspend fun showProgress(manager: DownloadManager) = coroutineScope {
        launch {
            var last = manager.active.value
            manager.active.collect { active ->
                DownloadNotice.newFailures(last, active).forEach(notifications::showFailed)
                last = active
            }
        }
        manager.active.map(DownloadNotice::from).distinctUntilChanged().conflate().collect { notice ->
            if (notice != null) show(notice)
            // At most a couple of updates a second (Android drops faster ones).
            delay(500)
        }
    }

    private suspend fun show(notice: DownloadNotice) {
        val info = notifications.foregroundInfo(notice)
        try {
            setForeground(info)
            foreground = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Android 12+ refuses a foreground service started from the
            // background (work resumed after the process died): the work goes
            // on without one, the notification is posted plainly.
            notifications.post(info.notification)
        }
    }
}

/** Pause and Cancel on the notification. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val albumId = intent.getStringExtra(EXTRA_ALBUM) ?: return
        val manager = (context.applicationContext as? DownloadsHost)?.downloadManager() ?: return
        when (intent.action) {
            ACTION_PAUSE -> manager.pause(albumId)
            ACTION_CANCEL -> manager.cancel(albumId)
        }
    }

    companion object {
        const val ACTION_PAUSE = "gg.dekiru.musicplayer.downloads.PAUSE"
        const val ACTION_CANCEL = "gg.dekiru.musicplayer.downloads.CANCEL"
        const val EXTRA_ALBUM = "albumId"
    }
}

/** The "Downloads" channel: progress while downloading, and a note when an album fails. */
internal class DownloadNotifications(context: Context) {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun foregroundInfo(notice: DownloadNotice?): ForegroundInfo {
        val notification = progress(notice)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_ID, notification)
        }
    }

    private fun progress(notice: DownloadNotice?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(notice?.title ?: "Downloading")
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (notice == null) return builder.build()
        notice.text?.let(builder::setContentText)
        if (notice.waitingForWifi) builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
        notice.percent?.let { builder.setProgress(100, it, false) }
        builder.addAction(0, "Pause", action(DownloadActionReceiver.ACTION_PAUSE, notice.albumId))
        builder.addAction(0, "Cancel", action(DownloadActionReceiver.ACTION_CANCEL, notice.albumId))
        return builder.build()
    }

    fun showFailed(album: DownloadAlbumState) {
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(album.title)
            .setContentText(album.error ?: "Download failed")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        notify(album.albumId, FAILED_ID, notification)
    }

    fun post(notification: Notification) = notify(null, PROGRESS_ID, notification)

    fun cancelProgress() = manager.cancel(PROGRESS_ID)

    /** Without the Android 13+ permission nothing shows; downloads run anyway. */
    private fun notify(tag: String?, id: Int, notification: Notification) {
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(tag, id, notification)
        } catch (e: SecurityException) {
        }
    }

    private fun openApp(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(action: String, albumId: String): PendingIntent {
        val intent = Intent(context, DownloadActionReceiver::class.java)
            .setAction(action)
            .putExtra(DownloadActionReceiver.EXTRA_ALBUM, albumId)
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        const val CHANNEL = "downloads"
        const val PROGRESS_ID = 2001
        const val FAILED_ID = 2002
    }
}
