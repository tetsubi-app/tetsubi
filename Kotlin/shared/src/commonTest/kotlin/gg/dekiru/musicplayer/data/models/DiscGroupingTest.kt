package gg.dekiru.musicplayer.data.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Multi-disc albums shown as one. Ported from DiscGroupingTests.swift and DiscTagTests.swift. */
class DiscGroupingTest {

    private fun album(id: String, name: String, artist: String = "Pumpkins") = JellyfinAlbum(
        id = id, name = name, productionYear = 1995, type = "MusicAlbum",
        albumArtists = listOf(JellyfinPerson(id = "ar", name = artist)),
    )

    private fun track(id: String, disc: Int?, number: Int) =
        JellyfinTrack(id = id, name = id, indexNumber = number, albumId = "a", discNumber = disc)

    @Test
    fun parsesCommonDiscNames() {
        val cases = listOf(
            Triple("Mellon Collie (Disc 1)", "Mellon Collie", 1),
            Triple("Mellon Collie [Disc 2]", "Mellon Collie", 2),
            Triple("Mellon Collie (CD2)", "Mellon Collie", 2),
            Triple("Mellon Collie - Disc 2", "Mellon Collie", 2),
            Triple("Mellon Collie: Disk 3", "Mellon Collie", 3),
            Triple("Mellon Collie CD 1", "Mellon Collie", 1),
            Triple("Mellon Collie (Disc 2 of 2)", "Mellon Collie", 2),
            Triple("Mellon Collie (disc.2)", "Mellon Collie", 2),
            Triple("Inside Yours/Push (Disc 2)", "Inside Yours/Push", 2),
            Triple("Akumajou Dracula X (Disc 1 Dracula X Chii no Rondo)", "Akumajou Dracula X", 1),
        )
        for ((name, base, disc) in cases) {
            val parsed = DiscGrouping.parse(name)
            assertEquals(base, parsed?.base, name)
            assertEquals(disc, parsed?.disc, name)
        }
    }

    @Test
    fun parsesDiscSubtitle() {
        assertEquals(
            "Vampire Killer-Castlevania Bloodlines",
            DiscGrouping.parse("Akumajou Dracula X (Disc 2 Vampire Killer-Castlevania Bloodlines)")?.subtitle,
        )
        assertEquals("Dawn to Dusk", DiscGrouping.parse("Mellon Collie (Disc 1: Dawn to Dusk)")?.subtitle)
        assertNull(DiscGrouping.parse("Mellon Collie (Disc 1)")?.subtitle)
    }

    @Test
    fun ignoresNonDiscNames() {
        for (name in listOf("Mellon Collie", "Disc 2", "Abcd 2", "NOW 100", "1999", "Discovery", "Discovery 2", "Abc (Discotheque 2)")) {
            assertNull(DiscGrouping.parse(name), name)
        }
    }

    @Test
    fun mergesDiscsWithDifferentSubtitles() {
        val merged = DiscGrouping.merge(
            listOf(
                album("a", "Akumajou Dracula X (Disc 1 Dracula X Chii no Rondo)", artist = "Konami Kukeiha Club"),
                album("b", "Akumajou Dracula X (Disc 2 Vampire Killer-Castlevania Bloodlines)", artist = "Konami Kukeiha Club"),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals("Akumajou Dracula X", merged[0].displayName)
        assertEquals(listOf("a", "b"), merged[0].allDiscs.map { it.id })
    }

    @Test
    fun mergesDiscsInPlaceOfFirst() {
        val merged = DiscGrouping.merge(
            listOf(album("x", "Siamese Dream"), album("d2", "Mellon Collie (Disc 2)"), album("y", "Gish"), album("d1", "Mellon Collie (Disc 1)")),
        )
        assertEquals(listOf("Siamese Dream", "Mellon Collie", "Gish"), merged.map { it.displayName })
        val mc = merged[1]
        assertEquals("d1", mc.id, "keeps the lowest disc's id")
        assertTrue(mc.isMultiDisc)
        assertEquals(listOf("d1", "d2"), mc.allDiscs.map { it.id })
        assertEquals(listOf("Mellon Collie (Disc 1)", "Mellon Collie (Disc 2)"), mc.allDiscs.map { it.displayName })
    }

    @Test
    fun accentsAndCaseDontSplitAGroup() {
        val merged = DiscGrouping.merge(
            listOf(album("1", "Café Tacvba (Disc 1)", "Café Tacvba"), album("2", "cafe tacvba (Disc 2)", "Cafe Tacvba")),
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun differentArtistsAreNotMerged() {
        val merged = DiscGrouping.merge(
            listOf(album("a", "Greatest Hits (Disc 1)", artist = "Queen"), album("b", "Greatest Hits (Disc 1)", artist = "ABBA")),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun singleDiscGetsBaseNameAndFindsSiblings() {
        val lone = DiscGrouping.merge(listOf(album("d2", "Mellon Collie (Disc 2)")))[0]
        assertEquals("Mellon Collie", lone.displayName)
        assertFalse(lone.isMultiDisc)
        val artistAlbums = listOf(album("d1", "Mellon Collie (Disc 1)"), album("d2", "Mellon Collie (Disc 2)"), album("z", "Zeitgeist"))
        assertEquals(listOf("d1", "d2"), DiscGrouping.siblings(lone, artistAlbums).map { it.id })
        assertEquals(emptyList(), DiscGrouping.siblings(album("z", "Zeitgeist"), artistAlbums))
    }

    // Discs inside one album (ParentIndexNumber)

    @Test
    fun groupsByDiscTag() {
        val groups = DiscGrouping.byDiscNumber(listOf(track("a1", 1, 1), track("a2", 1, 2), track("b1", 2, 1)))
        assertEquals(listOf(1, 2), groups?.map { it.first })
        assertEquals(listOf(listOf("a1", "a2"), listOf("b1")), groups?.map { g -> g.second.map { it.id } })
    }

    @Test
    fun oneDiscOrUntaggedIsNotSplit() {
        assertNull(DiscGrouping.byDiscNumber(listOf(track("a", 1, 1), track("b", 1, 2))))
        assertNull(DiscGrouping.byDiscNumber(listOf(track("a", null, 1), track("b", null, 2))))
    }

    @Test
    fun fileNumbersDontCollide() {
        assertEquals(1, track("a", 1, 1).fileNumber)
        assertEquals(12, track("b", 1, 12).fileNumber)
        assertEquals(201, track("c", 2, 1).fileNumber)
        assertEquals(3, track("d", null, 3).fileNumber)
    }
}
