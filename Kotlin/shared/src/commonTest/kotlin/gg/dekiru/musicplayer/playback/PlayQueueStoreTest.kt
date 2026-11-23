package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.downloads.LocalFiles
import gg.dekiru.musicplayer.downloads.tempDir
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayQueueStoreTest {
    private val f = QueueFixture()

    private fun sampleQueue(): PlayQueue = PlayQueue().apply {
        replace(QueueFixture.A, QueueFixture.aTracks, newId = f.ids)
        addToQueue(QueueFixture.group("P", QueueGroupKind.PLAYLIST), QueueFixture.bTracks, f.random, f.ids)
        skipTo(entries[1].id)
        repeatMode = RepeatMode.ALL
        setShuffle(true, Random(5))
    }

    private inline fun inTemp(block: (String) -> Unit) {
        val dir = tempDir()
        try {
            block(dir)
        } finally {
            LocalFiles.deleteRecursively(dir)
        }
    }

    /** The Swift side's example, as given. */
    private val swiftExample = """
        {"currentEntryId":"B271","entries":[{"batchId":"268D","group":{"artItemId":"A","artist":"Artist","id":"A","kind":"album","title":"Album A"},"id":"B271","track":{"album":"Album A","albumId":"A","artItemId":"A","artist":"Artist","container":"flac","durationSeconds":100,"id":"A1","normalizationGain":-6.5,"title":"Song A1","trackNumber":0}}],"playOrder":["B271"],"positionSeconds":12,"repeatMode":"off","shuffle":false,"version":1}
    """.trim()

    @Test
    fun decodesTheSwiftExample() {
        val snapshot = assertNotNull(PlayQueueSnapshot.decode(swiftExample))
        assertEquals(1, snapshot.version)
        assertEquals(12.0, snapshot.positionSeconds)
        assertEquals(RepeatMode.OFF, snapshot.repeatMode)
        val entry = snapshot.entries.single()
        assertEquals(QueueEntry(
            id = "B271",
            batchId = "268D",
            track = QueueTrack(
                id = "A1", title = "Song A1", artist = "Artist", album = "Album A", albumId = "A", artItemId = "A",
                durationSeconds = 100.0, trackNumber = 0, container = "flac", normalizationGain = -6.5,
            ),
            group = QueueGroup("A", QueueGroupKind.ALBUM, "Album A", "Artist", "A"),
        ), entry)
        val q = snapshot.toQueue()
        assertEquals("B271", q.currentEntryId)
        assertEquals(listOf("B271"), q.playOrder)
        // And back: the same keys and values (key order aside).
        assertEquals(Json.parseToJsonElement(swiftExample), Json.parseToJsonElement(q.snapshot(12.0).encode().replace("100.0", "100").replace("12.0", "12")))
    }

    @Test
    fun jsonKeysAndValues() {
        val q = sampleQueue()
        val obj = Json.parseToJsonElement(q.snapshot(3.5).encode()).jsonObject
        assertEquals(setOf("version", "entries", "currentEntryId", "positionSeconds", "shuffle", "playOrder", "repeatMode"), obj.keys)
        assertEquals("all", obj["repeatMode"]!!.jsonPrimitive.content)
        assertEquals("true", obj["shuffle"]!!.jsonPrimitive.content)
        val entry = obj["entries"]!!.jsonArray.first().jsonObject
        assertEquals(setOf("id", "batchId", "track", "group"), entry.keys)
        assertEquals("album", entry["group"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        val track = entry["track"]!!.jsonObject
        assertEquals(setOf("id", "title", "artist", "album", "albumId", "artItemId", "durationSeconds", "trackNumber"), track.keys, "nulls left out")
        val playlist = obj["entries"]!!.jsonArray.last().jsonObject["group"]!!.jsonObject
        assertEquals("playlist", playlist["kind"]!!.jsonPrimitive.content)
        for ((mode, name) in listOf(RepeatMode.OFF to "off", RepeatMode.ONE to "one")) {
            val o = Json.parseToJsonElement(q.copy().apply { repeatMode = mode }.snapshot(0.0).encode()).jsonObject
            assertEquals(name, o["repeatMode"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun explicitNullsAndUnknownKeysLoad() {
        val text = """
            {"version":1,"extra":true,"currentEntryId":null,"entries":[
              {"id":"x","batchId":"b","track":{"id":"t","title":"T","artist":"","album":"","albumId":"a","durationSeconds":1.5,"container":null,"artistId":null},
               "group":{"id":"t","kind":"tracks","title":"T","artist":null,"artItemId":null}}
            ],"playOrder":["x"],"positionSeconds":0,"shuffle":false,"repeatMode":"one"}
        """.trimIndent()
        val q = assertNotNull(PlayQueueSnapshot.decode(text)).toQueue()
        assertEquals("x", q.currentEntryId, "null current: the first")
        assertEquals(RepeatMode.ONE, q.repeatMode)
        assertEquals(QueueGroupKind.TRACKS, q.entries.single().group.kind)
        assertEquals("a", q.entries.single().track.artItemId, "art defaults to the album")
    }

    @Test
    fun roundTripThroughAFile() = inTemp { dir ->
        val store = PlayQueueStore.inDataFolder(dir)
        assertEquals("$dir/PlayQueue/queue.json", store.path)
        val q = sampleQueue()
        assertTrue(store.save(q.snapshot(42.5)), "creates the folder")
        val loaded = assertNotNull(store.load("srv", "usr"))
        assertEquals(1, loaded.version)
        assertEquals(42.5, loaded.positionSeconds)
        assertEquals(q, loaded.toQueue())
        store.clear()
        assertNull(store.load("srv", "usr"))
        assertFalse(LocalFiles.exists(store.path))
    }

    @Test
    fun serverAndUserRoundTripAndMustMatch() = inTemp { dir ->
        val store = PlayQueueStore.inDataFolder(dir)
        val q = sampleQueue()
        store.save(q.snapshot(7.0, serverId = "srv", userId = "usr"))
        val obj = Json.parseToJsonElement(LocalFiles.readText(store.path)!!).jsonObject
        assertEquals("srv", obj["serverId"]!!.jsonPrimitive.content)
        assertEquals("usr", obj["userId"]!!.jsonPrimitive.content)
        assertEquals(1, obj["version"]!!.jsonPrimitive.content.toInt())
        val loaded = assertNotNull(store.load("srv", "usr"))
        assertEquals("srv", loaded.serverId)
        assertEquals("usr", loaded.userId)
        assertEquals(q, loaded.toQueue())
        assertNull(store.load("other", "usr"), "another server")
        assertNull(store.load("srv", "other"), "another user")
    }

    @Test
    fun aSnapshotWithoutServerAndUserStillLoads() = inTemp { dir ->
        val store = PlayQueueStore.inDataFolder(dir)
        store.save(sampleQueue().snapshot(0.0))
        assertFalse("serverId" in LocalFiles.readText(store.path)!!, "nulls left out")
        val loaded = assertNotNull(store.load("srv", "usr"))
        assertNull(loaded.serverId)
        assertNull(loaded.userId)
    }

    @Test
    fun savingAnEmptyQueueDeletesTheFile() = inTemp { dir ->
        val store = PlayQueueStore.inDataFolder(dir)
        store.save(sampleQueue().snapshot(0.0))
        assertTrue(store.save(PlayQueue().snapshot(0.0)))
        assertFalse(LocalFiles.exists(store.path))
        assertTrue(store.save(PlayQueue().snapshot(0.0)), "nothing to delete is fine")
    }

    @Test
    fun loadRepairs() = inTemp { dir ->
        val store = PlayQueueStore.inDataFolder(dir)
        val q = sampleQueue()
        val broken = q.snapshot(0.0).copy(playOrder = listOf("zz"), currentEntryId = "gone", positionSeconds = -4.0)
        LocalFiles.createDirectories("$dir/PlayQueue")
        LocalFiles.writeText(store.path, broken.encode())
        val loaded = assertNotNull(store.load("srv", "usr"))
        assertFalse(loaded.shuffle)
        assertEquals(q.entries.map { it.id }, loaded.playOrder)
        assertEquals(q.entries.first().id, loaded.currentEntryId)
        assertEquals(0.0, loaded.positionSeconds)
    }

    @Test
    fun missingCorruptOtherVersionOrEmptyLoadAsNothing() = inTemp { dir ->
        val store = PlayQueueStore.inDataFolder(dir)
        assertNull(store.load("srv", "usr"), "missing")
        LocalFiles.createDirectories("$dir/PlayQueue")
        val bad = listOf(
            "", "{", "not json", "[]",
            """{"version":1,"entries":[{"id":3}]}""",
            """{"version":2,"entries":[]}""",
            sampleQueue().snapshot(0.0).copy(version = 2).encode(),
            """{"version":1,"entries":[],"playOrder":[]}""",
            sampleQueue().snapshot(0.0).encode().replace("\"all\"", "\"sometimes\""),
        )
        for (text in bad) {
            LocalFiles.writeText(store.path, text)
            assertNull(store.load("srv", "usr"), "should not load: $text")
        }
        // A folder where the file should be.
        LocalFiles.deleteRecursively(store.path)
        LocalFiles.createDirectories(store.path)
        assertNull(store.load("srv", "usr"), "a folder")
        assertFalse(store.save(sampleQueue().snapshot(0.0)), "can't write over a folder")
    }

    @Test
    fun saverCoalescesToOneWritePerInterval() = runTest {
        val writes = mutableListOf<PlayQueueSnapshot>()
        val saver = PlayQueueSaver(this, { writes += it }, intervalMillis = 1_000, ioDispatcher = StandardTestDispatcher(testScheduler))
        val q = sampleQueue()
        saver.schedule(q.snapshot(1.0))
        saver.schedule(q.snapshot(2.0))
        advanceTimeBy(500)
        saver.schedule(q.snapshot(3.0))
        runCurrent()
        assertEquals(0, writes.size, "nothing before the interval")
        advanceTimeBy(501)
        runCurrent()
        assertEquals(listOf(3.0), writes.map { it.positionSeconds }, "one write, the latest, an interval after the first change")
        assertFalse(saver.hasPending)

        saver.schedule(q.snapshot(4.0))
        assertTrue(saver.hasPending)
        saver.flush()
        assertEquals(listOf(3.0, 4.0), writes.map { it.positionSeconds }, "flush writes at once")
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, writes.size, "nothing left after a flush")
        saver.flush()
        assertEquals(2, writes.size, "flushing with nothing waiting writes nothing")
    }

    @Test
    fun saverWritesToTheStore() = runTest {
        val dir = tempDir()
        try {
            val store = PlayQueueStore.inDataFolder(dir)
            val saver = PlayQueueSaver(this, store, ioDispatcher = StandardTestDispatcher(testScheduler))
            saver.schedule(sampleQueue().snapshot(9.0))
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(9.0, store.load("srv", "usr")?.positionSeconds)
        } finally {
            LocalFiles.deleteRecursively(dir)
        }
    }

    @Test
    fun saverWritesAChangeMadeDuringAWrite() = runTest {
        val writes = mutableListOf<PlayQueueSnapshot>()
        val q = sampleQueue()
        lateinit var saver: PlayQueueSaver
        // The second change arrives while the first is being written.
        saver = PlayQueueSaver(this, {
            writes += it
            if (it.positionSeconds == 1.0) saver.schedule(q.snapshot(2.0))
        }, intervalMillis = 1_000, ioDispatcher = StandardTestDispatcher(testScheduler))
        saver.schedule(q.snapshot(1.0))
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf(1.0), writes.map { it.positionSeconds })
        assertTrue(saver.hasPending)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf(1.0, 2.0), writes.map { it.positionSeconds }, "written an interval later, with no further change")
        assertFalse(saver.hasPending)
    }

    @Test
    fun aCancelledSaverWritesNothingMore() = runTest {
        val writes = mutableListOf<PlayQueueSnapshot>()
        val saver = PlayQueueSaver(this, { writes += it }, intervalMillis = 1_000, ioDispatcher = StandardTestDispatcher(testScheduler))
        val q = sampleQueue()
        saver.schedule(q.snapshot(1.0))
        saver.cancel()
        assertFalse(saver.hasPending)
        saver.schedule(q.snapshot(2.0))
        saver.flush()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, writes.size)
    }
}
