package app.londontrainer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A position in spaced repetition: `box` 0 means not yet played from memory. */
class Card(val box: Int, val due: Long, val lapses: Int = 0)

enum class Grade { Clean, Hard, Missed }

/** Days until the next review, by box. Each clean answer moves a card up one. */
private val Intervals = intArrayOf(0, 1, 3, 7, 16, 35, 90)

/** Replaying a line early proves little, so only answers given when a card is due can promote it. */
fun review(card: Card?, grade: Grade, today: Long): Card {
    val box = card?.box ?: 0
    val lapses = card?.lapses ?: 0
    if (grade == Grade.Missed) return Card(0, today, lapses + 1)
    if (card != null && card.due > today) return card
    if (grade == Grade.Hard) return Card(box, today + 1, lapses)
    val up = minOf(box + 1, Intervals.lastIndex)
    return Card(up, today + Intervals[up], lapses)
}

class Memory(
    cards: Map<String, Card> = emptyMap(),
    streak: Int = 0,
    lastDay: Long = Long.MIN_VALUE,
    lines: Int = 0,
    cleanLines: Int = 0,
    private val persist: (Memory) -> Unit = {},
) {
    var cards by mutableStateOf(cards)
        private set
    var streak by mutableIntStateOf(streak)
        private set
    var lastDay by mutableLongStateOf(lastDay)
        private set
    var lines by mutableIntStateOf(lines)
        private set
    var cleanLines by mutableIntStateOf(cleanLines)
        private set

    /** Shown a new position: due right away, since copying an arrow isn't knowing the move. */
    fun teach(key: String, today: Long) {
        if (key in cards) return
        cards = cards + (key to Card(0, today))
        persist(this)
    }

    fun grade(key: String, grade: Grade, today: Long) {
        cards = cards + (key to review(cards[key], grade, today))
        if (lastDay != today) {
            streak = if (lastDay == today - 1) streak + 1 else 1
            lastDay = today
        }
        persist(this)
    }

    fun lineDone(clean: Boolean) {
        lines++
        if (clean) cleanLines++
        persist(this)
    }

    fun reset() {
        cards = emptyMap()
        streak = 0
        lastDay = Long.MIN_VALUE
        lines = 0
        cleanLines = 0
        persist(this)
    }

    /** A streak survives until a full day is skipped. */
    fun streakOn(today: Long) = if (lastDay >= today - 1) streak else 0

    /** Nodes to review, the shakiest and earliest in the opening first. */
    fun dueIds(rep: Repertoire, today: Long): List<Int> = cards.entries
        .filter { it.value.due <= today }
        .mapNotNull { (key, card) -> rep.idByKey[key]?.let { it to card } }
        .sortedWith(compareBy({ it.second.box }, { -it.second.lapses }, { it.first }))
        .map { it.first }

    /** Chance of knowing every White move in a line, weighted by Black's reply frequencies. */
    fun readiness(rep: Repertoire) = lineReadiness(rep.nodes) { (cards[rep.keys[it]]?.box ?: 0) >= 1 }

    fun groupReadiness(rep: Repertoire, prefix: List<String>, ready: DoubleArray = readiness(rep)): Double {
        var id = 0
        for (move in prefix) {
            if ((cards[rep.keys[id]]?.box ?: 0) < 1) return 0.0
            id = rep.nodes[id].replies.first { it.uci == move }.next
        }
        return ready[id]
    }

    fun movesKnownFromStart(rep: Repertoire) = movesKnown(rep.nodes) { (cards[rep.keys[it]]?.box ?: 0) >= 1 }[0]

    fun serialize() = cards.entries.joinToString(",") { (k, c) -> "$k:${c.box}:${c.due}:${c.lapses}" }
}

/** Skips anything unreadable: a bad entry shouldn't cost the player the rest of their progress. */
fun parseCards(saved: String): Map<String, Card> = saved.split(",").mapNotNull { entry ->
    val p = entry.split(":")
    val box = p.getOrNull(1)?.toIntOrNull()
    val due = p.getOrNull(2)?.toLongOrNull()
    val lapses = p.getOrNull(3)?.toIntOrNull()
    if (box == null || box !in 0..Intervals.lastIndex || due == null || lapses == null || lapses < 0 || p[0].isBlank()) null else p[0] to Card(box, due, lapses)
}.toMap()

/** Old node ids are meaningful only alongside the exact repertoire that created them. */
fun migrateCards(keys: List<String>, seen: Set<String>, missed: Set<String>, today: Long): Map<String, Card> =
    (seen + missed).mapNotNull { raw ->
        val key = raw.toIntOrNull()?.let { keys.getOrNull(it) } ?: return@mapNotNull null
        key to Card(if (raw in missed) 0 else 1, today, if (raw in missed) 1 else 0)
    }.groupBy({ it.first }, { it.second }).mapValues { (_, cards) ->
        cards.minBy { it.box } // preserve a lapse when two old nodes reached the same position
    }
