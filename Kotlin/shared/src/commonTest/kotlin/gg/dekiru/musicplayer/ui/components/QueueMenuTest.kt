package gg.dekiru.musicplayer.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** The queue entries of every album, playlist and song menu. */
class QueueMenuTest {

    private fun titles(maniac: Boolean) = queueActions({}, {}, maniacMode = maniac).map { it.title }

    @Test
    fun bothEntriesWhenManiacModeIsOff() {
        assertEquals(listOf("Play Next", "Add to Queue"), titles(maniac = false))
    }

    @Test
    fun noPlayNextInManiacMode() {
        assertEquals(listOf("Add to Queue"), titles(maniac = true))
    }

    @Test
    fun turningManiacModeOffBringsPlayNextBack() {
        var maniac = true
        assertEquals(listOf("Add to Queue"), titles(maniac))
        maniac = false
        assertEquals(listOf("Play Next", "Add to Queue"), titles(maniac))
    }

    @Test
    fun theEntriesStillDoTheirThing() {
        var next = 0
        var add = 0
        val menu = queueActions({ next++ }, { add++ }, maniacMode = false)
        menu.forEach { it.action() }
        assertEquals(1, next)
        assertEquals(1, add)
    }
}
