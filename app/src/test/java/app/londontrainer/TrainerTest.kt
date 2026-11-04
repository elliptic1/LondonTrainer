package app.londontrainer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class TrainerTest {
    private fun perft(p: Position, depth: Int): Long =
        if (depth == 0) 1 else p.legalMoves().sumOf { perft(p.play(it), depth - 1) }

    @Test fun perftStart() = assertEquals(197281L, perft(Position.start(), 4))

    @Test fun perftKiwipete() = assertEquals(
        97862L,
        perft(Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"), 3),
    )

    @Test fun perftEnPassantAndPromotion() = assertEquals(
        43238L,
        perft(Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"), 4),
    )

    private fun diff(fen: String, move: String) = Position.fromFen(fen).let { diffBoards(it.board, it.play(move).board) }

    @Test fun diffCapture() = assertEquals(
        listOf(Position.sq("e4", 0) to Position.sq("d5", 0)) to listOf(Position.sq("d5", 0)),
        diff("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1", "e4d5"),
    )

    @Test fun diffCastlingMovesBothPieces() = assertEquals(
        setOf(Position.sq("e1", 0) to Position.sq("g1", 0), Position.sq("h1", 0) to Position.sq("f1", 0)),
        diff("4k3/8/8/8/8/8/8/4K2R w K - 0 1", "e1g1").first.toSet(),
    )

    @Test fun diffEnPassantRemovesThePawnBeside() = assertEquals(
        listOf(Position.sq("d5", 0) to Position.sq("e6", 0)) to listOf(Position.sq("e5", 0)),
        diff("4k3/8/8/3Pp3/8/8/8/4K3 w - e6 0 1", "d5e6"),
    )

    @Test fun positionKeysAreStableAndDistinct() {
        // A saved player's progress depends on this exact value never changing.
        assertEquals(Position.start().key(), Position.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").key())
        assertNotEquals(Position.start().key(), Position.start().play("d2d4").key())
    }

    @Test fun unusableEnPassantDoesNotSplitProgress() {
        val afterD4 = Position.start().play("d2d4")
        assertEquals(afterD4.key(), Position(afterD4.board, afterD4.whiteToMove, afterD4.castling, -1).key())
        val available = Position.fromFen("4k3/8/8/3Pp3/8/8/8/4K3 w - e6 0 1")
        assertNotEquals(available.key(), Position(available.board, true, "", -1).key())
        val pinned = Position.fromFen("k3r3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
        assertEquals(pinned.key(), Position(pinned.board, true, "", -1).key())
    }

    @Test fun legacyKeysUseTheSameStableFormat() {
        val keys = org.json.JSONArray(File("src/main/assets/legacy-positions.json").readText())
        assertEquals(2551, keys.length())
        assertEquals(Position.start().key(), keys.getString(0))
    }

    private val rep by lazy { parseRepertoire(File("src/main/assets/repertoire.json").readText()) }
    private val nodes get() = rep.nodes

    /** Every move in the graph is legal, from every path, and every line ends after real moves. */
    @Test fun repertoireIsClosedAndLegal() {
        val seen = HashSet<Int>()
        fun walk(id: Int, p: Position) {
            if (!seen.add(id)) return
            val n = nodes[id]
            val moves = p.legalMoves()
            assertTrue("illegal ${n.uci} at $id", n.uci in moves)
            n.alternatives.forEach { assertTrue("illegal alt $it at $id", it in moves) }
            n.rejected.forEach { (move, refutation) ->
                assertTrue("illegal rejected $move at $id", move in moves)
                refutation.uci?.let { assertTrue("illegal refutation $it at $id", it in p.play(move).legalMoves()) }
            }
            val after = p.play(n.uci)
            for (r in n.replies) {
                assertTrue("illegal reply ${r.uci} at $id", r.uci in after.legalMoves())
                assertTrue("children must follow parents", r.next > id)
                walk(r.next, after.play(r.uci))
            }
        }
        walk(0, Position.start())
        assertEquals("unreachable positions", nodes.size, seen.size)
    }

    @Test fun threatsAreRealBlackMoves() {
        for ((id, n) in nodes.withIndex()) n.threat?.let { t ->
            val p = rep.positionAt(id)
            val flipped = Position(p.board, false, p.castling, -1)
            assertTrue("impossible threat ${t.uci} at $id", t.uci in flipped.legalMoves())
        }
    }

    @Test fun pathLeadsToItsPosition() {
        for (id in nodes.indices step 37) {
            val reached = rep.path(id).fold(Position.start()) { p, ply -> p.play(ply.uci) }
            assertEquals(rep.keys[id], reached.key())
        }
    }

    @Test fun everyGroupExistsInTheGraph() {
        assertTrue(rep.groups.size >= 5)
        rep.groups.forEach { assertTrue(it.name, rep.entry(it.prefix) > 0) }
    }

    /** The product promise: lines end with the setup done, not cut off mid-plan. */
    @Test fun nearlyEveryLineFinishesTheSetup() {
        val odds = DoubleArray(nodes.size).also { it[0] = 1.0 }
        var castled = 0.0
        var ended = 0.0
        for ((i, n) in nodes.withIndex()) {
            val total = n.replies.sumOf { it.weight }
            if (total == 0) {
                ended += odds[i]
                if (n.uci == "e1g1" || n.uci == "e1c1") castled += odds[i]
            }
            for (r in n.replies) odds[r.next] += odds[i] * r.weight / total
        }
        assertTrue("only ${castled / ended} of lines castle", castled / ended > 0.9)
    }

    @Test fun explanationsDontTeachTheWrongThing() {
        assertFalse(nodes.any { "leaves Black with doubled pawns" in it.why })
        val filler = nodes.count { it.why.startsWith("The most accurate") || it.why.endsWith("before continuing the plan.") }
        assertTrue("$filler generic explanations", filler < nodes.size / 50)
    }

    @Test fun duePositionsComeBack() {
        val parent = nodes.indices.first { nodes[it].replies.size >= 3 }
        val target = nodes[parent].replies.last()
        val rng = Random(1)
        val samples = 5000
        val hits = (1..samples).count { chooseReply(nodes, nodes[parent], { it == target.next }, { false }, rng) == target }
        val total = nodes[parent].replies.sumOf { it.weight }
        val baseline = target.weight.toDouble() / total
        val expected = target.weight * 20.0 / (total - target.weight + target.weight * 20.0)
        assertEquals("review steering must preserve the relative reply weights", expected, hits.toDouble() / samples, 0.04)
        assertTrue("a rare due line should be more likely than its base frequency", hits.toDouble() / samples > baseline * 2)
    }

    private fun node(uci: String, tag: String = "plan", rejected: Map<String, Refutation> = emptyMap(), alternatives: Set<String> = emptySet()) =
        Node(uci, uci, tag, "", alternatives, rejected, emptyList(), null)

    @Test fun rightIdeaWrongOrderIsntAMistake() {
        assertTrue(judge(node("g1f3"), "c2c3") is Attempt.Close)
        val e3Early = judge(node("c1f4"), "e2e3")
        assertTrue(e3Early is Attempt.Close && "shut your bishop in" in e3Early.text)
    }

    @Test fun unsoundPlanMoveIsPunished() {
        val wrong = judge(node("f4g3", "react", mapOf("e2e3" to Refutation("Nxf4", 120, "h5f4"))), "e2e3")
        assertTrue(wrong is Attempt.Wrong && wrong.punish == "h5f4")
    }

    @Test fun strayMoveIsWrongButHasNothingToPlayOut() {
        val wrong = judge(node("c1f4"), "a2a3")
        assertTrue(wrong is Attempt.Wrong && wrong.punish == null)
    }
}
