package app.londontrainer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunScoreTest {
    private val node = Node("d2d4", "d4", "plan", "", setOf("g1f3"), emptyMap(), emptyList())

    private fun finish(score: RunScore): RunScore =
        (1..8).fold(score) { total, _ -> total.record(judge(node, "d2d4")) }

    @Test fun correctMovesAloneAreClean() {
        assertTrue(finish(RunScore()).clean)
    }

    @Test fun correctingMistakesDoesNotEraseThemAtTheEnd() {
        val score = finish(RunScore().record(judge(node, "a2a3")).record(judge(node, "b2b3")))
        assertFalse(score.clean)
        assertEquals(2, score.mistakes)
        assertEquals("2 mistakes", score.summary)
    }

    @Test fun playableAlternativeIsARetryWithoutCallingItAChessMistake() {
        val score = finish(RunScore().record(judge(node, "g1f3")))
        assertFalse(score.clean)
        assertEquals(0, score.mistakes)
        assertEquals("1 playable alternative", score.summary)
    }

    @Test fun hintOnAnEarlierMoveStillPreventsACleanRun() {
        val score = finish(RunScore().withHint())
        assertFalse(score.clean)
        assertEquals("1 hint", score.summary)
    }

    @Test fun summaryIncludesAllHelpAndRetriesAcrossTheRun() {
        val score = finish(RunScore().record(judge(node, "a2a3"))
            .record(judge(node, "g1f3")).withHint().withHint())
        assertEquals("1 mistake, 1 playable alternative, 2 hints", score.summary)
        assertFalse(score.clean)
        assertTrue("A new run starts clean", RunScore().clean)
    }
}
