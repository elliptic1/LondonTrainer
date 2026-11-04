package app.londontrainer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlin.random.Random

sealed interface Session {
    val title: String

    /** A whole line from the first move. `prefix` fixes Black's first moves to stay in one opening. */
    class Line(override val title: String = "Play a line", val prefix: List<String> = emptyList()) : Session

    /** Straight to the positions that are due. */
    class Review(val queue: List<Int>) : Session {
        override val title = "Review"
    }
}

enum class Phase { Yours, Black, Punish, Answered, Done }

enum class Fx { Move, Capture, Castle, Wrong, Right, Done }

enum class Tone(val title: String, val color: Color) {
    Info("Your move", Palette.Raised),
    Teach("New position", Palette.Blue),
    Good("Correct", Palette.Green),
    Adjust("Good adjustment", Palette.Blue),
    Warn("Not quite", Palette.Yellow),
    Bad("Not this time", Palette.Bad),
}

data class Feedback(val text: String, val tone: Tone, val title: String = tone.title)

class Step(val position: Position, val ply: Ply)

private const val ThinkMs = 450L
private const val PunishMs = 1700L

class Drill(
    private val rep: Repertoire,
    private val memory: Memory,
    val session: Session,
    private val teach: Boolean,
    private val today: Long,
    private val fx: (Fx) -> Unit = {},
    private val rng: Random = Random,
) {
    private var position by mutableStateOf(Position.start())
    private var last by mutableStateOf<String?>(null)
    var nodeId by mutableIntStateOf(0)
        private set
    var phase by mutableStateOf(Phase.Yours)
        private set
    var steps by mutableStateOf(emptyList<Step>())
        private set
    var mark by mutableStateOf<Mark?>(null)
        private set
    var feedback by mutableStateOf(Feedback("You're White. Play the London: drag a piece to its square, or tap one then the other.", Tone.Info))
        private set
    var hints by mutableIntStateOf(0)
        private set
    var arrows by mutableStateOf(emptyList<Arrow>())
        private set
    var mistakes by mutableIntStateOf(0)
        private set
    var learned by mutableIntStateOf(0)
        private set
    var assisted by mutableStateOf(false)
        private set
    var teaching by mutableStateOf(false)
        private set

    /** How many steps of a finished line are on the board. */
    var cursor by mutableIntStateOf(0)
        private set

    private var inspecting by mutableStateOf(false)
    private var slip = Grade.Clean
    private var entering: Reply? = null
    private var punishing: Triple<String, Position, String?>? = null
    private val queue = ArrayDeque((session as? Session.Review)?.queue.orEmpty())
    private val retried = HashSet<Int>()
    private val answered = HashSet<Int>()
    private val cards = queue.size
    var cleared by mutableIntStateOf(0)
        private set

    val total get() = cards
    val cardsLeft get() = queue.size
    val node get() = rep.nodes[nodeId]
    val reviewable get() = phase == Phase.Done && session is Session.Line
    val shown get() = if (!reviewable || cursor == steps.size) position else if (cursor == 0) Position.start() else steps[cursor - 1].position
    val shownLast get() = if (!reviewable || cursor == steps.size) last else steps.getOrNull(cursor - 1)?.ply?.uci
    val played get() = steps.filterIndexed { i, _ -> i % 2 == 0 }.map { it.ply.uci }.toSet()
    val moving get() = phase == Phase.Yours

    init {
        if (session is Session.Review) nextCard() else enter(0)
    }

    private val key get() = rep.keys[nodeId]

    private fun enter(id: Int) {
        nodeId = id
        hints = 0
        slip = Grade.Clean
        mark = null
        teaching = teach && session is Session.Line && memory.cards[rep.keys[id]] == null
        arrows = if (teaching) listOf(arrow(rep.nodes[id].uci, Tone.Teach)) else emptyList()
        if (teaching) feedback = Feedback("${rep.nodes[id].san}. ${rep.nodes[id].why}", Tone.Teach)
    }

    private fun arrow(uci: String, tone: Tone) = Arrow(Position.sq(uci, 0), Position.sq(uci, 2), tone.color)

    private fun move(ply: Ply) {
        position = position.play(ply.uci)
        last = ply.uci
        steps = steps + Step(position, ply)
        fx(if ('x' in ply.san) Fx.Capture else if (ply.san.startsWith("O-O")) Fx.Castle else Fx.Move)
    }

    private fun miss() {
        if (slip != Grade.Missed) memory.grade(key, Grade.Missed, today)
        slip = Grade.Missed
    }

    /** True if the piece stays where it was put, for now at least. */
    fun tryMove(uci: String): Boolean {
        if (phase != Phase.Yours) return false
        val to = Position.sq(uci, 2)
        if (teaching && uci != node.uci) {
            mark = Mark(to, Tone.Warn) // the arrow and its explanation are still up: no need to replace them
            return false
        }
        return when (val result = judge(node, uci)) {
            Attempt.Correct -> {
                correct()
                true
            }
            is Attempt.Close -> {
                assisted = true
                if (slip == Grade.Clean) {
                    slip = Grade.Hard
                    memory.grade(key, Grade.Hard, today)
                }
                feedback = Feedback(result.text, Tone.Warn)
                mark = Mark(to, Tone.Warn)
                fx(Fx.Wrong)
                false
            }
            is Attempt.Wrong -> {
                mistakes++
                miss()
                hints = maxOf(hints, 1)
                feedback = Feedback(result.text, Tone.Bad)
                mark = Mark(to, Tone.Bad)
                fx(Fx.Wrong)
                result.punish?.let { reply ->
                    punishing = Triple(reply, position, last)
                    position = position.play(uci)
                    last = uci
                    phase = Phase.Punish
                    mark = null
                    true
                } ?: false
            }
        }
    }

    private fun correct() {
        val here = node
        when {
            teaching -> {
                memory.teach(key, today)
                learned++
            }
            slip == Grade.Clean -> memory.grade(key, Grade.Clean, today)
            slip == Grade.Hard -> memory.grade(key, Grade.Hard, today)
        }
        move(Ply(here.uci, here.san, here.why))
        val tone = if (here.tag == "plan") Tone.Good else Tone.Adjust
        feedback = Feedback("${here.san}: ${here.why}", tone)
        mark = Mark(Position.sq(here.uci, 2), tone)
        arrows = emptyList()
        teaching = false
        fx(Fx.Right)
        if (session is Session.Review) {
            answered.add(nodeId)
            cleared = answered.size
            if (slip == Grade.Missed && retried.add(nodeId)) queue.addLast(nodeId)
            phase = Phase.Answered
        } else if (here.replies.isEmpty()) {
            finish()
        } else {
            phase = Phase.Black
        }
    }

    private fun finish() {
        phase = Phase.Done
        cursor = steps.size
        if (session is Session.Line) memory.lineDone(mistakes == 0 && learned == 0 && !assisted)
        fx(Fx.Done)
    }

    /** After a pause for thought, Black moves. */
    suspend fun blackTurn() {
        delay(ThinkMs)
        blackMoves()
    }

    fun blackMoves() {
        if (phase != Phase.Black) return
        val reply = entering ?: chooseBlack()
        entering = null
        move(Ply(reply.uci, reply.san, null))
        enter(reply.next)
        phase = Phase.Yours
    }

    private fun chooseBlack(): Reply {
        val forced = (session as? Session.Line)?.prefix?.getOrNull(steps.size / 2)
        return node.replies.firstOrNull { it.uci == forced } ?: chooseReply(
            rep.nodes,
            node,
            due = { id -> memory.cards[rep.keys[id]]?.let { it.due <= today } ?: false },
            new = { id -> rep.keys[id] !in memory.cards },
            rng,
        )
    }

    /** Plays out what Black would do to the wrong move, then takes it all back. */
    suspend fun playPunishment() {
        val (reply, home, before) = punishing ?: return
        delay(ThinkMs)
        val capture = position.board[Position.sq(reply, 2)] != '.'
        position = position.play(reply)
        last = reply
        arrows = listOf(arrow(reply, Tone.Bad))
        fx(if (capture) Fx.Capture else Fx.Move)
        delay(PunishMs)
        position = home
        last = before
        arrows = emptyList()
        punishing = null
        phase = Phase.Yours
    }

    /** First hint names Black's idea and the piece to move; the second gives the square. */
    fun hint() {
        if (phase != Phase.Yours || hints >= 2 || teaching) return
        hints++
        assisted = true
        miss()
        if (hints > 1) {
            feedback = Feedback("Try ${node.san}: ${node.uci.take(2)} to ${node.uci.substring(2, 4)}. ${node.why}", Tone.Info, "Move to try")
            arrows = listOf(arrow(node.uci, Tone.Teach))
            return
        }
        val piece = when (position.board[Position.sq(node.uci, 0)]) {
            'K' -> "king"; 'Q' -> "queen"; 'R' -> "rook"; 'B' -> "bishop"; 'N' -> "knight"; else -> "pawn"
        }
        val nudge = "Look at your $piece on ${node.uci.take(2)}."
        val threat = node.threat
        when {
            threat == null -> feedback = Feedback(if (node.tag == "plan") "Nothing urgent. What does the setup still need?" else "Something needs attention before the plan continues.", Tone.Info, "Hint")
            else -> {
                val text = if ('x' in threat.san || '+' in threat.san) "Black is threatening ...${threat.san}." else "Black wants to play ...${threat.san}."
                feedback = Feedback(text, Tone.Info, "Hint")
                arrows = listOf(arrow(threat.uci, Tone.Bad))
            }
        }
        feedback = feedback.copy(text = "${feedback.text} $nudge")
    }

    /** On to the next due position. */
    fun nextCard() {
        val id = queue.removeFirstOrNull()
        if (id == null) return finish()
        val path = rep.path(id)
        var p = Position.start()
        steps = path.dropLast(1).map { ply -> p = p.play(ply.uci); Step(p, ply) }
        position = p
        last = path.dropLast(1).lastOrNull()?.uci
        mark = null
        arrows = emptyList()
        hints = 0
        slip = Grade.Clean
        nodeId = id
        teaching = false
        feedback = Feedback(path.lastOrNull()?.let { "Black played ...${it.san}. What's your move?" } ?: "How does the London start?", Tone.Info, "Review")
        val into = path.lastOrNull()
        entering = into?.let { Reply(it.uci, it.san, 0, id) }
        phase = if (into == null) Phase.Yours else Phase.Black
    }

    fun stepBy(delta: Int) {
        if (reviewable) {
            inspecting = true
            cursor = (cursor + delta).coerceIn(0, steps.size)
        }
    }

    /** What the coach says about the move at the cursor. */
    fun stepFeedback(): Feedback? {
        if (!reviewable || cursor == steps.size && !inspecting) return null
        val step = steps.getOrNull(cursor - 1) ?: return Feedback("The starting position. Use the arrows to walk through the line.", Tone.Info, "Line review")
        val why = step.ply.why
        return if (why != null) Feedback("${step.ply.san}: $why", Tone.Good, "Your move") else Feedback("Black played ...${step.ply.san}.", Tone.Info, "Black's move")
    }
}
