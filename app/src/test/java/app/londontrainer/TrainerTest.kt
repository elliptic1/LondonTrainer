package app.londontrainer

import org.junit.Assert.assertEquals
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
        perft(Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"), 4).also { },
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

    private val nodes by lazy { parseRepertoire(File("src/main/assets/repertoire.json").readText()) }

    /** Every move in the graph is legal, from every path, and every line ends after real moves. */
    @Test fun repertoireIsClosedAndLegal() {
        val seen = HashSet<Int>()
        fun walk(id: Int, p: Position) {
            if (!seen.add(id)) return
            val n = nodes[id]
            val moves = p.legalMoves()
            assertTrue("illegal ${n.uci} at $id", n.uci in moves)
            n.alternatives.forEach { assertTrue("illegal alt $it at $id", it in moves) }
            n.rejected.keys.forEach { assertTrue("illegal rejected $it at $id", it in moves) }
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

    @Test fun missedPositionsComeBack() {
        val parent = nodes.indices.first { nodes[it].replies.size >= 3 }
        val target = nodes[parent].replies.last()
        val rng = Random(1)
        val hits = (1..1000).count { chooseReply(nodes, nodes[parent], nodes.indices.toSet(), setOf(target.next), rng) == target }
        assertTrue("a missed line should be favored, got $hits/1000", hits > 400)
    }
}
