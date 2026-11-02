package app.londontrainer

import org.json.JSONObject
import kotlin.random.Random

class Reply(val uci: String, val san: String, val weight: Int, val next: Int)

class Node(
    val uci: String,
    val san: String,
    val tag: String,
    val why: String,
    val alternatives: Set<String>,
    val rejected: Map<String, Pair<String?, Int>>,
    val replies: List<Reply>,
)

fun parseRepertoire(json: String): List<Node> {
    val nodes = JSONObject(json).getJSONArray("nodes")
    return List(nodes.length()) { i ->
        val n = nodes.getJSONObject(i)
        val alts = n.optJSONArray("a")
        val rej = n.optJSONObject("r")
        val replies = n.getJSONArray("b")
        Node(
            uci = n.getString("u"),
            san = n.getString("s"),
            tag = n.getString("t"),
            why = n.getString("w"),
            alternatives = List(alts?.length() ?: 0) { alts!!.getString(it) }.toSet(),
            rejected = rej?.keys()?.asSequence()?.associateWith {
                val r = rej.getJSONArray(it)
                (if (r.isNull(0)) null else r.getString(0)) to r.getInt(1)
            } ?: emptyMap(),
            replies = List(replies.length()) {
                val r = replies.getJSONArray(it)
                Reply(r.getString(0), r.getString(1), r.getInt(2), r.getInt(3))
            },
        )
    }
}

/** Picks Black's reply, steering toward positions you got wrong, then ones you haven't seen. */
fun chooseReply(nodes: List<Node>, node: Node, seen: Set<Int>, missed: Set<Int>, rng: Random = Random): Reply {
    // Generated breadth-first, so children always have higher ids than parents.
    val reachesMissed = BooleanArray(nodes.size)
    val reachesUnseen = BooleanArray(nodes.size)
    for (i in nodes.indices.reversed()) {
        reachesMissed[i] = i in missed || nodes[i].replies.any { reachesMissed[it.next] }
        reachesUnseen[i] = i !in seen || nodes[i].replies.any { reachesUnseen[it.next] }
    }
    fun weight(r: Reply) = r.weight * if (reachesMissed[r.next]) 30 else if (reachesUnseen[r.next]) 2 else 1
    var roll = rng.nextInt(node.replies.sumOf { weight(it) })
    return node.replies.first { r -> (roll - weight(r)).also { roll = it } < 0 }
}

sealed interface Attempt {
    data object Correct : Attempt
    data class Playable(val text: String) : Attempt
    data class Wrong(val text: String) : Attempt
}

fun judge(node: Node, move: String, san: (String) -> String = { it }): Attempt = when {
    move == node.uci -> Attempt.Correct
    move in node.alternatives -> Attempt.Playable("Playable, but it's not the London move here. Stick to the recipe.")
    move in node.rejected -> {
        val (refutation, loss) = node.rejected.getValue(move)
        val pawns = "%.1f".format(loss / 100.0)
        Attempt.Wrong(
            if (refutation != null) "Normally the plan, but not now: Black answers ...$refutation and you're $pawns pawns worse."
            else "Normally the plan, but not now: it costs about $pawns pawns."
        )
    }
    else -> Attempt.Wrong("That's not part of the plan here. What does the setup still need, and is anything under attack?")
}

val PLAN = listOf("d2d4" to "d4", "c1f4" to "Bf4", "e2e3" to "e3", "g1f3" to "Nf3", "c2c3" to "c3", "b1d2" to "Nbd2", "f1d3" to "Bd3", "e1g1" to "O-O")
