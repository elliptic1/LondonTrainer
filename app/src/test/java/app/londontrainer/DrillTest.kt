package app.londontrainer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class DrillTest {
    private val rep by lazy { parseRepertoire(File("src/main/assets/repertoire.json").readText()) }
    private val today = 100L

    private fun line(memory: Memory, teach: Boolean = true, prefix: List<String> = emptyList(), seed: Int = 1) =
        Drill(rep, memory, Session.Line(prefix = prefix), teach, today, rng = Random(seed))

    /** Plays the right move until the line ends. */
    private fun finish(d: Drill) {
        while (d.phase != Phase.Done) {
            assertTrue(d.tryMove(d.node.uci))
            d.blackMoves()
        }
    }

    @Test fun aNewPositionIsTaughtButNotYetKnown() {
        val memory = Memory()
        val d = line(memory)
        assertTrue(d.teaching)
        assertEquals(1, d.arrows.size)
        assertTrue(d.tryMove("d2d4"))
        assertEquals(0, memory.cards.getValue(rep.keys[0]).box)
        assertEquals(1, d.learned)
    }

    @Test fun whileTeachingOnlyTheArrowCountsAndNothingIsPenalized() {
        val memory = Memory()
        val d = line(memory)
        assertFalse(d.tryMove("a2a3"))
        assertEquals(0, d.mistakes)
        assertTrue(memory.cards.isEmpty())
    }

    @Test fun aKnownPositionIsTestedNotTaught() {
        val memory = Memory(mapOf(rep.keys[0] to Card(1, today)))
        val d = line(memory)
        assertFalse(d.teaching)
        assertTrue(d.arrows.isEmpty())
        d.tryMove("d2d4")
        assertEquals(2, memory.cards.getValue(rep.keys[0]).box)
    }

    @Test fun progressIsSavedOnEveryAnswerNotWhenTheLineEnds() {
        var saves = 0
        val memory = Memory(mapOf(rep.keys[0] to Card(1, today))) { saves++ }
        val d = line(memory)
        d.tryMove("d2d4")
        assertEquals(1, saves)
        assertTrue(d.phase != Phase.Done)
    }

    @Test fun aMistakeIsSavedTheMomentItHappens() {
        val memory = Memory(mapOf(rep.keys[0] to Card(4, today)))
        val d = line(memory)
        assertFalse(d.tryMove("a2a3"))
        assertEquals(1, d.mistakes)
        assertEquals(0, memory.cards.getValue(rep.keys[0]).box)
    }

    @Test fun recoveringFromAMistakeDoesntCountAsKnowingIt() {
        val memory = Memory(mapOf(rep.keys[0] to Card(4, today)))
        val d = line(memory)
        d.tryMove("a2a3")
        d.tryMove("d2d4")
        assertEquals(0, memory.cards.getValue(rep.keys[0]).box)
    }

    @Test fun aHintCountsAsNotKnowingAndGivesTheIdeaFirst() {
        val memory = Memory(mapOf(rep.keys[0] to Card(4, today)))
        val d = line(memory)
        d.hint()
        assertEquals(1, d.hints)
        assertEquals(0, memory.cards.getValue(rep.keys[0]).box)
        assertEquals("Hint", d.feedback.title)
        assertTrue("pawn on d2" in d.feedback.text)
        d.hint()
        assertTrue("d2 to d4" in d.feedback.text)
        assertEquals(1, d.arrows.size)
        d.hint()
        assertEquals(2, d.hints)
    }

    @Test fun thePlanOrderSlipOnlyHoldsTheCardBack() {
        val start = rep.nodes[0].replies.first { it.uci == "d7d5" }.next
        val key = rep.keys[start]
        val memory = Memory(rep.keys.associateWith { Card(3, today) })
        val d = line(memory, prefix = listOf("d7d5"))
        d.tryMove("d2d4")
        d.blackMoves()
        assertEquals(start, d.nodeId)
        assertFalse(d.tryMove("e2e3"))
        assertEquals(0, d.mistakes)
        d.tryMove("c1f4")
        assertEquals(3, memory.cards.getValue(key).box)
        assertEquals(today + 1, memory.cards.getValue(key).due)
    }

    @Test fun aWrongPlanMovePlaysOutThePunishmentThenTakesItBack() = runBlocking {
        val id = rep.nodes.indices.first { i -> rep.nodes[i].rejected.values.any { it.uci != null } }
        val (move, refutation) = rep.nodes[id].rejected.entries.first { it.value.uci != null }
        val memory = Memory(rep.keys.associateWith { Card(3, today) })
        val d = Drill(rep, memory, Session.Review(listOf(id)), true, today)
        d.blackMoves()
        val home = d.shown.key()
        assertTrue(d.tryMove(move))
        assertEquals(Phase.Punish, d.phase)
        assertEquals(rep.positionAt(id).play(move).key(), d.shown.key())
        d.playPunishment()
        assertEquals(Phase.Yours, d.phase)
        assertEquals(home, d.shown.key())
        assertTrue(d.arrows.isEmpty())
        assertTrue(refutation.san != null)
    }

    @Test fun blackMovesEvenInEveryLineAndTheSetupIsCredited() {
        for (seed in 1..40) {
            val d = line(Memory(), seed = seed)
            finish(d)
            assertTrue("line $seed", d.steps.size % 2 == 1)
        }
    }

    @Test fun aFinishedLineCanBeSteppedThrough() {
        val d = line(Memory())
        finish(d)
        val end = d.shown.key()
        assertNull(d.stepFeedback())
        d.stepBy(-1)
        assertTrue(d.stepFeedback() != null)
        assertTrue(d.shown.key() != end)
        d.stepBy(-1000)
        assertEquals(Position.start().key(), d.shown.key())
        d.stepBy(1000)
        assertEquals(end, d.shown.key())
        assertTrue(d.steps.last().ply.san in d.stepFeedback()!!.text)
    }

    @Test fun anOpeningSessionStaysInItsOpening() {
        repeat(20) { seed ->
            val d = line(Memory(), prefix = listOf("g8f6", "g7g6"), seed = seed)
            finish(d)
            assertEquals("Nf6", d.steps[1].ply.san)
            assertEquals("g6", d.steps[3].ply.san)
        }
    }

    @Test fun reviewJumpsStraightToTheDuePosition() {
        val id = rep.nodes.indices.first { rep.path(it).size == 8 }
        val memory = Memory(mapOf(rep.keys[id] to Card(0, today)))
        val d = Drill(rep, memory, Session.Review(listOf(id)), true, today)
        assertEquals(Phase.Black, d.phase)
        d.blackMoves()
        assertEquals(Phase.Yours, d.phase)
        assertEquals(8, d.steps.size)
        assertEquals(rep.keys[id], d.shown.key())
        assertFalse(d.teaching)
    }

    @Test fun reviewAnsweredCleanlyMovesThePositionUp() {
        val id = 5
        val memory = Memory(mapOf(rep.keys[id] to Card(0, today)))
        val d = Drill(rep, memory, Session.Review(listOf(id)), true, today)
        d.blackMoves()
        d.tryMove(rep.nodes[id].uci)
        assertEquals(Phase.Answered, d.phase)
        d.nextCard()
        assertEquals(Phase.Done, d.phase)
        assertEquals(1, memory.cards.getValue(rep.keys[id]).box)
        assertEquals(1, d.cleared)
    }

    @Test fun aMissedReviewComesBackOnceInTheSameSession() {
        val id = 5
        val memory = Memory(mapOf(rep.keys[id] to Card(0, today)))
        val d = Drill(rep, memory, Session.Review(listOf(id)), true, today)
        d.blackMoves()
        d.hint()
        d.tryMove(rep.nodes[id].uci)
        d.nextCard()
        assertEquals(Phase.Black, d.phase)
        d.blackMoves()
        d.tryMove(rep.nodes[id].uci)
        d.nextCard()
        assertEquals(Phase.Done, d.phase)
        assertEquals(1, d.cleared)
        assertTrue(d.assisted)
    }

    @Test fun hintsDoNotProduceCleanLines() {
        val memory = Memory(rep.keys.associateWith { Card(3, today) })
        val d = line(memory)
        d.hint()
        finish(d)
        assertEquals(1, memory.lines)
        assertEquals(0, memory.cleanLines)
    }

    @Test fun reviewingTheFirstMoveWorks() {
        val memory = Memory(mapOf(rep.keys[0] to Card(0, today)))
        val d = Drill(rep, memory, Session.Review(listOf(0)), true, today)
        assertEquals(Phase.Yours, d.phase)
        assertTrue(d.tryMove("d2d4"))
    }

    @Test fun movesAreIgnoredWhenItIsntYourTurn() {
        val d = line(Memory())
        d.tryMove("d2d4")
        assertEquals(Phase.Black, d.phase)
        assertFalse(d.tryMove("e2e4"))
    }
}
