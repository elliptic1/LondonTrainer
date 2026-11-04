package app.londontrainer

import org.json.JSONObject
import kotlin.random.Random

class Reply(val uci: String, val san: String, val weight: Int, val next: Int)

/** What Black does to a plan move played at the wrong moment. */
class Refutation(val san: String?, val loss: Int, val uci: String?)

class Threat(val san: String, val uci: String)

class Node(
    val uci: String,
    val san: String,
    val tag: String,
    val why: String,
    val alternatives: Set<String>,
    val rejected: Map<String, Refutation>,
    val replies: List<Reply>,
    val threat: Threat?,
)

class Group(val name: String, val blurb: String, val prefix: List<String>)

class Ply(val uci: String, val san: String, val why: String?)

class Repertoire(val nodes: List<Node>, val groups: List<Group>) {
    private val parent = IntArray(nodes.size) { -1 }
    private val before = arrayOfNulls<Position>(nodes.size)

    init {
        before[0] = Position.start()
        // Generated breadth-first, so children always have higher ids than parents.
        for (i in nodes.indices) {
            val after = before[i]!!.play(nodes[i].uci)
            for (r in nodes[i].replies) if (before[r.next] == null) {
                before[r.next] = after.play(r.uci)
                parent[r.next] = i
            }
        }
    }

    val keys = List(nodes.size) { before[it]!!.key() }

    /** One node per position: the same position can be reached at different move numbers. */
    val idByKey: Map<String, Int> = HashMap<String, Int>().also { m -> keys.forEachIndexed { i, k -> m.putIfAbsent(k, i) } }

    /** Average moves of a line that exist from each node on: the most a player could know. */
    val fullDepth = movesKnown(nodes) { true }

    fun positionAt(id: Int) = before[id]!!

    /** The moves that lead to `id`, ending with Black's move into it. */
    fun path(id: Int): List<Ply> {
        val out = ArrayDeque<Ply>()
        var n = id
        while (parent[n] >= 0) {
            val p = parent[n]
            val reply = nodes[p].replies.first { it.next == n }
            out.addFirst(Ply(reply.uci, reply.san, null))
            out.addFirst(Ply(nodes[p].uci, nodes[p].san, nodes[p].why))
            n = p
        }
        return out
    }

    /** The node after Black has played `prefix`, each answered by the repertoire. */
    fun entry(prefix: List<String>) = prefix.fold(0) { id, move -> nodes[id].replies.first { it.uci == move }.next }
}

fun parseRepertoire(json: String): Repertoire {
    val root = JSONObject(json)
    val nodes = root.getJSONArray("nodes")
    val groups = root.getJSONArray("groups")
    return Repertoire(
        List(nodes.length()) { i ->
            val n = nodes.getJSONObject(i)
            val alts = n.optJSONArray("a")
            val rej = n.optJSONObject("r")
            val replies = n.getJSONArray("b")
            val threat = n.optJSONArray("h")
            Node(
                uci = n.getString("u"),
                san = n.getString("s"),
                tag = n.getString("t"),
                why = n.getString("w"),
                alternatives = List(alts?.length() ?: 0) { alts!!.getString(it) }.toSet(),
                rejected = rej?.keys()?.asSequence()?.associateWith {
                    val r = rej.getJSONArray(it)
                    Refutation(if (r.isNull(0)) null else r.getString(0), r.getInt(1), if (r.isNull(2)) null else r.getString(2))
                } ?: emptyMap(),
                replies = List(replies.length()) {
                    val r = replies.getJSONArray(it)
                    Reply(r.getString(0), r.getString(1), r.getInt(2), r.getInt(3))
                },
                threat = threat?.let { Threat(it.getString(0), it.getString(1)) },
            )
        },
        List(groups.length()) { i ->
            val g = groups.getJSONObject(i)
            val prefix = g.getJSONArray("p")
            Group(g.getString("n"), g.getString("d"), List(prefix.length()) { prefix.getString(it) })
        },
    )
}

/**
 * Picks Black's reply: as often as real players choose it, but steered toward positions that are
 * due for review, then ones you haven't met.
 */
fun chooseReply(nodes: List<Node>, node: Node, due: (Int) -> Boolean, new: (Int) -> Boolean, rng: Random = Random): Reply {
    val reachesDue = BooleanArray(nodes.size)
    val reachesNew = BooleanArray(nodes.size)
    for (i in nodes.indices.reversed()) {
        reachesDue[i] = due(i) || nodes[i].replies.any { reachesDue[it.next] }
        reachesNew[i] = new(i) || nodes[i].replies.any { reachesNew[it.next] }
    }
    fun weight(r: Reply) = r.weight * if (reachesDue[r.next]) 20 else if (reachesNew[r.next]) 3 else 1
    var roll = rng.nextInt(node.replies.sumOf { weight(it) })
    return node.replies.first { r -> (roll - weight(r)).also { roll = it } < 0 }
}

/** How many moves of a typical line, from each node on, are known before the first gap. */
fun movesKnown(nodes: List<Node>, known: (Int) -> Boolean): DoubleArray {
    val moves = DoubleArray(nodes.size)
    for (i in nodes.indices.reversed()) {
        if (!known(i)) continue
        val total = nodes[i].replies.sumOf { it.weight }
        moves[i] = 1.0 + if (total == 0) 0.0 else nodes[i].replies.sumOf { it.weight * moves[it.next] } / total
    }
    return moves
}

sealed interface Attempt {
    data object Correct : Attempt

    /** Not the move, but sound or the right idea: no punishment, just try again. */
    data class Close(val text: String) : Attempt

    /** `punish` is Black's answer, for showing on the board. */
    data class Wrong(val text: String, val punish: String? = null) : Attempt
}

fun judge(node: Node, move: String): Attempt {
    if (move == node.uci) return Attempt.Correct
    node.rejected[move]?.let { r ->
        val pawns = "%.1f".format(r.loss / 100.0)
        return Attempt.Wrong(
            if (r.san != null) "Normally the plan, but not now: Black answers ...${r.san} costing about $pawns pawns compared with the right move."
            else "Normally the plan, but not now: it costs about $pawns pawns.",
            r.uci,
        )
    }
    PLAN.firstOrNull { it.first == move }?.let { (_, label) ->
        return Attempt.Close(
            when {
                move == "e2e3" && node.uci == "c1f4" -> "e3 is part of the setup, but it would shut your bishop in. Get Bf4 out first."
                node.tag == "plan" -> "$label is part of the setup, but ${node.san} comes first here."
                else -> "$label is part of the setup, but something needs your attention first."
            },
        )
    }
    if (move in node.alternatives) return Attempt.Close("Playable, but it's not the London move here. Stick to the recipe.")
    return Attempt.Wrong("That's not part of the plan here. What does the setup still need, and is anything under attack?")
}

val PLAN = listOf("d2d4" to "d4", "c1f4" to "Bf4", "e2e3" to "e3", "g1f3" to "Nf3", "c2c3" to "c3", "b1d2" to "Nbd2", "f1d3" to "Bd3", "e1g1" to "O-O")

/** A gap anywhere on a path makes that whole line unready. */
fun lineReadiness(nodes: List<Node>, known: (Int) -> Boolean): DoubleArray {
    val chances = DoubleArray(nodes.size)
    for (i in nodes.indices.reversed()) {
        if (!known(i)) continue
        val replies = nodes[i].replies
        val total = replies.sumOf { it.weight }
        chances[i] = if (total == 0) 1.0 else replies.sumOf { it.weight * chances[it.next] } / total
    }
    return chances
}
