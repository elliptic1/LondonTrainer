package app.londontrainer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {
    @Test fun cleanAnswersSpaceOutReviews() {
        val first = review(null, Grade.Clean, 100)
        assertEquals(1, first.box)
        assertEquals(101, first.due)
        val second = review(first, Grade.Clean, 101)
        assertEquals(2, second.box)
        assertEquals(104, second.due)
    }

    @Test fun replayingEarlyProvesNothing() {
        val card = Card(3, 110)
        assertEquals(card, review(card, Grade.Clean, 105))
        assertEquals(card, review(card, Grade.Hard, 105))
    }

    @Test fun aMissStartsOverAndComesBackToday() {
        val missed = review(Card(5, 100, lapses = 2), Grade.Missed, 100)
        assertEquals(0, missed.box)
        assertEquals(100, missed.due)
        assertEquals(3, missed.lapses)
    }

    @Test fun hardHoldsTheBoxAndTriesAgainTomorrow() {
        val held = review(Card(3, 100), Grade.Hard, 100)
        assertEquals(3, held.box)
        assertEquals(101, held.due)
    }

    @Test fun intervalsTopOutInsteadOfOverflowing() {
        var card: Card? = null
        var day = 0L
        repeat(20) {
            card = review(card, Grade.Clean, day)
            day = card!!.due
        }
        assertEquals(6, card!!.box)
    }

    @Test fun beingTaughtIsNotKnowing() {
        val memory = Memory()
        memory.teach("k", 100)
        assertEquals(0, memory.cards.getValue("k").box)
        assertEquals(100, memory.cards.getValue("k").due)
    }

    @Test fun teachingNeverOverwritesProgress() {
        val memory = Memory(mapOf("k" to Card(4, 130)))
        memory.teach("k", 100)
        assertEquals(4, memory.cards.getValue("k").box)
    }

    @Test fun streakCountsDaysAndBreaksOnASkippedDay() {
        val memory = Memory()
        memory.grade("a", Grade.Clean, 100)
        memory.grade("b", Grade.Clean, 100)
        assertEquals(1, memory.streak)
        memory.grade("a", Grade.Clean, 101)
        assertEquals(2, memory.streakOn(101))
        assertEquals(2, memory.streakOn(102))
        assertEquals(0, memory.streakOn(103))
        memory.grade("a", Grade.Clean, 104)
        assertEquals(1, memory.streak)
    }

    @Test fun progressSurvivesASaveAndLoad() {
        val memory = Memory()
        memory.grade("abc", Grade.Clean, 100)
        memory.grade("def", Grade.Missed, 100)
        val loaded = parseCards(memory.serialize())
        assertEquals(1, loaded.getValue("abc").box)
        assertEquals(101, loaded.getValue("abc").due)
        assertEquals(1, loaded.getValue("def").lapses)
    }

    @Test fun aDamagedSaveKeepsWhatItCan() {
        val loaded = parseCards("abc:1:101:0,garbage,:::,def:2:x:0,ghi:3:104:1,bad:-1:90:0,bad2:99:90:0,bad3:1:90:-1")
        assertEquals(setOf("abc", "ghi"), loaded.keys)
        assertTrue(parseCards("").isEmpty())
    }

    private fun tiny(): Repertoire {
        fun n(uci: String, vararg replies: Reply) = Node(uci, uci, "plan", "", emptySet(), emptyMap(), replies.toList(), null)
        return Repertoire(
            listOf(
                n("d2d4", Reply("d7d5", "d5", 3, 1), Reply("g8f6", "Nf6", 1, 2)),
                n("c1f4"),
                n("c1f4"),
            ),
            emptyList(),
        )
    }

    @Test fun readinessRequiresKnowingTheWholeLine() {
        val rep = tiny()
        val everyLineHasTwoMoves = 2.0
        assertEquals(everyLineHasTwoMoves, rep.fullDepth[0], 1e-9)

        val onlyFirstMove = Memory(mapOf(rep.keys[0] to Card(1, 0)))
        assertEquals(0.0, onlyFirstMove.readiness(rep)[0], 1e-9)

        // Knowing the 3-in-4 reply carries you further than knowing the 1-in-4 one.
        val common = Memory(mapOf(rep.keys[0] to Card(1, 0), rep.keys[1] to Card(1, 0)))
        val rare = Memory(mapOf(rep.keys[0] to Card(1, 0), rep.keys[2] to Card(1, 0)))
        assertEquals(0.75, common.readiness(rep)[0], 1e-9)
        assertEquals(0.25, rare.readiness(rep)[0], 1e-9)
    }

    @Test fun migrationPreservesKnownAndMissedPositionsAndIgnoresBadIds() {
        val cards = migrateCards(listOf("a", "b", "a"), setOf("0", "1", "999", "bad"), setOf("2"), 100)
        assertEquals(setOf("a", "b"), cards.keys)
        assertEquals(0, cards.getValue("a").box)
        assertEquals(1, cards.getValue("a").lapses)
        assertEquals(1, cards.getValue("b").box)
        assertEquals(100, cards.getValue("b").due)
    }

    @Test fun openingReadinessIncludesTheMovesBeforeItsShortcut() {
        val rep = tiny()
        val laterOnly = Memory(mapOf(rep.keys[1] to Card(1, 0)))
        assertEquals(0.0, laterOnly.groupReadiness(rep, listOf("d7d5")), 1e-9)
        val whole = Memory(mapOf(rep.keys[0] to Card(1, 0), rep.keys[1] to Card(1, 0)))
        assertEquals(1.0, whole.groupReadiness(rep, listOf("d7d5")), 1e-9)
    }

    @Test fun aGapEndsTheCount() {
        val rep = tiny()
        val noFirstMove = Memory(mapOf(rep.keys[1] to Card(1, 0), rep.keys[2] to Card(1, 0)))
        assertEquals(0.0, noFirstMove.readiness(rep)[0], 1e-9)
    }

    @Test fun shakiestPositionsComeFirstAndUnknownOnesAreIgnored() {
        val rep = tiny()
        val memory = Memory(
            mapOf(
                rep.keys[0] to Card(3, 90),
                rep.keys[1] to Card(0, 100, lapses = 1),
                rep.keys[2] to Card(2, 200),
                "left over from an older repertoire" to Card(0, 90),
            ),
        )
        assertEquals(listOf(1, 0), memory.dueIds(rep, 100))
    }
}
