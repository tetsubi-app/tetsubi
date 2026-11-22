package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.downloads.LocalFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * The queue as saved between runs, restored paused at the same entry and
 * position. The same JSON as iOS's `PlayQueueSnapshot`: `repeatMode` is
 * "off", "all" or "one"; optional values that are null are left out (and
 * explicit nulls load).
 */
@Serializable
data class PlayQueueSnapshot(
    val version: Int = VERSION,
    val entries: List<QueueEntry> = emptyList(),
    val currentEntryId: String? = null,
    val positionSeconds: Double = 0.0,
    val shuffle: Boolean = false,
    val playOrder: List<String> = emptyList(),
    @Serializable(with = RepeatModeName::class)
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** The server the queue belongs to; null in files written before it was added (they still load). */
    val serverId: String? = null,
    /** The user the queue belongs to; null as [serverId]. */
    val userId: String? = null,
) {
    /** The queue, repaired ([PlayQueue.from]). */
    fun toQueue(): PlayQueue = PlayQueue.from(this)

    /** Repaired: the queue's own parts, and a negative or non-finite position as 0. */
    fun repaired(): PlayQueueSnapshot =
        toQueue().snapshot(positionSeconds, serverId, userId)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val VERSION = 1

        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        /** Null for anything that isn't a version-1 snapshot (never throws). Not repaired. */
        fun decode(text: String): PlayQueueSnapshot? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.version == VERSION }
    }
}

/** [RepeatMode] as "off", "all", "one" (as iOS); anything else fails, so the file doesn't load (as iOS). */
internal object RepeatModeName : KSerializer<RepeatMode> {
    override val descriptor = PrimitiveSerialDescriptor("gg.dekiru.musicplayer.RepeatMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: RepeatMode) = encoder.encodeString(
        when (value) {
            RepeatMode.OFF -> "off"
            RepeatMode.ALL -> "all"
            RepeatMode.ONE -> "one"
        },
    )

    override fun deserialize(decoder: Decoder): RepeatMode = when (decoder.decodeString()) {
        "all" -> RepeatMode.ALL
        "one" -> RepeatMode.ONE
        "off" -> RepeatMode.OFF
        else -> throw SerializationException("Unknown repeat mode")
    }
}

/**
 * Reads and writes the saved queue (`<app data>/PlayQueue/queue.json`).
 * Failures are swallowed: a queue that can't be saved or read is simply not restored.
 */
class PlayQueueStore(val path: String) {

    /**
     * The saved snapshot, repaired; null when missing, unreadable, corrupt,
     * of another version or empty, or saved for another server or user (a
     * saved `serverId`/`userId` that differs from the one given; a file
     * without them loads).
     */
    fun load(serverId: String?, userId: String?): PlayQueueSnapshot? = runCatching {
        LocalFiles.readText(path)?.let(PlayQueueSnapshot::decode)
            ?.takeIf { matches(it.serverId, serverId) && matches(it.userId, userId) }
            ?.repaired()?.takeIf { it.entries.isNotEmpty() }
    }.getOrNull()

    private fun matches(saved: String?, wanted: String?) = saved == null || wanted == null || saved == wanted

    /** Writes through a temporary file; an empty queue deletes the file. Returns whether it worked. */
    fun save(snapshot: PlayQueueSnapshot): Boolean = runCatching {
        if (snapshot.entries.isEmpty()) {
            LocalFiles.deleteRecursively(path)
        } else {
            path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(LocalFiles::createDirectories)
            LocalFiles.writeText(path, snapshot.encode())
        }
    }.isSuccess

    /** Forgets the saved queue (sign out). */
    fun clear() {
        runCatching { LocalFiles.deleteRecursively(path) }
    }

    companion object {
        const val FOLDER = "PlayQueue"
        const val FILE_NAME = "queue.json"

        /** The store in the app's data folder (`AppEnvironment.dataDir`: Android `filesDir`, Linux `$XDG_DATA_HOME/gg.dekiru.musicplayer`). */
        fun inDataFolder(dataDir: String) = PlayQueueStore("${dataDir.trimEnd('/')}/$FOLDER/$FILE_NAME")
    }
}

/**
 * Saves the queue on every change without writing on every change: the
 * latest snapshot is written [intervalMillis] after the first unsaved change
 * (at most one write per interval), on [ioDispatcher]. Call from one thread
 * (the main one).
 */
class PlayQueueSaver(
    private val scope: CoroutineScope,
    private val write: (PlayQueueSnapshot) -> Unit,
    private val intervalMillis: Long = 1_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(scope: CoroutineScope, store: PlayQueueStore, intervalMillis: Long = 1_000, ioDispatcher: CoroutineDispatcher = Dispatchers.Default) :
        this(scope, { snapshot: PlayQueueSnapshot -> store.save(snapshot); Unit }, intervalMillis, ioDispatcher)

    private var pending: PlayQueueSnapshot? = null
    private var job: Job? = null

    /** Whether a snapshot is waiting to be written. */
    val hasPending: Boolean get() = pending != null

    fun schedule(snapshot: PlayQueueSnapshot) {
        pending = snapshot
        if (job?.isActive == true) return
        job = scope.launch {
            delay(intervalMillis)
            writePending()
        }
    }

    /** Writes a waiting snapshot now (the app going to the background or closing). */
    suspend fun flush() {
        job?.cancel()
        job = null
        writePending()
    }

    private suspend fun writePending() {
        val snapshot = pending ?: return
        pending = null
        withContext(ioDispatcher) { write(snapshot) }
    }
}
