package gg.dekiru.musicplayer.playback

import kotlin.test.Test
import kotlin.test.assertEquals

/** Queue adds apply in tap order ([AddTurns]). */
class AddTurnsTest {

    @Test
    fun finishingInReverseAppliesInTurnOrder() {
        val turns = AddTurns()
        val applied = mutableListOf<String>()
        val a = turns.take()
        val b = turns.take()
        turns.finish(b) { applied += "b" }
        assertEquals(emptyList(), applied)
        turns.finish(a) { applied += "a" }
        assertEquals(listOf("a", "b"), applied)
        assertEquals(0, turns.pending)
    }

    @Test
    fun aDroppedTurnLetsTheOthersThrough() {
        val turns = AddTurns()
        val applied = mutableListOf<String>()
        val a = turns.take()
        val b = turns.take()
        val c = turns.take()
        turns.finish(c) { applied += "c" }
        turns.drop(b)
        assertEquals(emptyList(), applied)
        turns.finish(a) { applied += "a" }
        assertEquals(listOf("a", "c"), applied)
    }

    @Test
    fun aFailedFirstTurnLetsTheNextApply() {
        val turns = AddTurns()
        val applied = mutableListOf<String>()
        val a = turns.take()
        val b = turns.take()
        turns.finish(b) { applied += "b" }
        turns.drop(a)
        assertEquals(listOf("b"), applied)
    }

    @Test
    fun aSecondFinishIsIgnored() {
        val turns = AddTurns()
        val applied = mutableListOf<String>()
        val a = turns.take()
        turns.finish(a) { applied += "a" }
        turns.finish(a) { applied += "again" }
        turns.drop(a)
        assertEquals(listOf("a"), applied)
    }

    @Test
    fun cancelAllDropsWaitingTurnsButNotLaterOnes() {
        val turns = AddTurns()
        val applied = mutableListOf<String>()
        val old = turns.take()
        turns.cancelAll()
        val new = turns.take()
        turns.finish(old) { applied += "old" }
        assertEquals(emptyList(), applied)
        turns.finish(new) { applied += "new" }
        assertEquals(listOf("new"), applied)
    }
}
