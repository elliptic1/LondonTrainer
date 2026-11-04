package app.londontrainer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HeaderHeight = 48.dp
private val PlanHeight = 36.dp
private val BarHeight = 68.dp
private val CoachMinimum = 104.dp
private val StripHeight = 40.dp

@Composable
fun DrillScreen(drill: Drill, colors: BoardColors, due: Int, onBack: () -> Unit, onRestart: () -> Unit, onReview: () -> Unit, onNextLine: () -> Unit) {
    var selected by remember(drill) { mutableIntStateOf(-1) }
    LaunchedEffect(drill, drill.phase) {
        selected = -1
        when (drill.phase) {
            Phase.Black -> drill.blackTurn()
            Phase.Punish -> drill.playPunishment()
            else -> {}
        }
    }
    val board = @Composable { modifier: Modifier -> TrainerBoard(drill, colors, selected, { selected = it }, modifier) }
    val strips = @Composable { black: Boolean ->
        val thinking = drill.phase == Phase.Black
        if (black) PlayerStrip(R.drawable.piece_bk, "Black", if (thinking) "Thinking…" else "", true)
        else PlayerStrip(R.drawable.piece_wk, "You", if (drill.moving) "Your move" else "", false)
    }
    val coach = @Composable { modifier: Modifier ->
        Box(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { Coach(summary(drill, due)) }
    }

    // Panel behind the system bars so the header and controls run to the screen's edges.
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Panel).windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (maxWidth > maxHeight) {
            val side = minOf(maxHeight, maxWidth * 0.6f)
            Row(Modifier.fillMaxSize().background(Palette.Felt)) {
                Box(Modifier.fillMaxHeight().width(side), contentAlignment = Alignment.Center) { board(Modifier.size(side)) }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    DrillHeader(drill, onBack)
                    PlanRow(drill.played)
                    coach(Modifier.weight(1f))
                    Controls(drill, due, onRestart, onReview, onNextLine, onBack)
                }
            }
        } else {
            val chrome = HeaderHeight + PlanHeight + BarHeight + CoachMinimum
            val roomy = maxHeight - chrome - StripHeight * 2 >= maxWidth
            val side = minOf(maxWidth, maxHeight - chrome - if (roomy) StripHeight * 2 else 0.dp).coerceAtLeast(200.dp)
            Column(Modifier.fillMaxSize()) {
                DrillHeader(drill, onBack)
                Column(Modifier.weight(1f).fillMaxWidth().background(Palette.Felt)) {
                    if (roomy) strips(true)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { board(Modifier.size(side)) }
                    if (roomy) strips(false)
                    PlanRow(drill.played)
                    coach(Modifier.weight(1f))
                }
                Controls(drill, due, onRestart, onReview, onNextLine, onBack)
            }
        }
    }
}

@Composable
private fun TrainerBoard(drill: Drill, colors: BoardColors, selected: Int, select: (Int) -> Unit, modifier: Modifier) {
    val position = drill.shown
    val legal = remember(position) { position.legalMoves() }
    fun moveTo(square: Int) = legal.firstOrNull { selected >= 0 && Position.sq(it, 0) == selected && Position.sq(it, 2) == square }
    val node = drill.node
    Board(
        position = position,
        selected = selected,
        targets = if (selected >= 0) legal.filter { Position.sq(it, 0) == selected }.map { Position.sq(it, 2) } else emptyList(),
        lastMove = drill.shownLast,
        hintFrom = if (drill.hints >= 1 && drill.moving) Position.sq(node.uci, 0) else -1,
        hintTo = if (drill.hints >= 2 && drill.moving) Position.sq(node.uci, 2) else -1,
        mark = drill.mark.takeIf { drill.phase != Phase.Done || !drill.reviewable },
        arrows = drill.arrows,
        colors = colors,
        modifier = modifier,
        onTap = { square ->
            if (!drill.moving) return@Board
            val move = moveTo(square)
            select(-1)
            if (move != null) drill.tryMove(move)
        },
        onPick = { square ->
            val movable = drill.moving && position.board[square].let { it != '.' && it.isUpperCase() }
            if (movable) select(square)
            movable
        },
        onDrop = { square ->
            val move = moveTo(square)
            when {
                move != null -> { select(-1); drill.tryMove(move) }
                square != selected -> { select(-1); false } // dropped back on its own square: stays selected for tap-to-move
                else -> false
            }
        },
    )
}

private fun summary(drill: Drill, due: Int): Feedback {
    drill.stepFeedback()?.let { return it }
    if (drill.phase != Phase.Done) return drill.feedback
    val review = drill.session is Session.Review
    return when {
        review && drill.mistakes == 0 && !drill.assisted -> Feedback("All ${drill.cleared} from memory. They'll come back when they start to fade.", Tone.Good, "Review done")
        review -> Feedback("Some answers needed help or another try. Those positions will come back for more practice.", Tone.Warn, "Review done")
        drill.learned > 0 -> Feedback("Seeing a move isn't knowing it. Review ${if (due == 1) "it" else "them"} from memory to lock ${if (due == 1) "it" else "them"} in.", Tone.Teach, "${drill.learned} new position${if (drill.learned == 1) "" else "s"}")
        drill.assisted -> Feedback("Some moves needed a hint or a different order. Review them to build confidence.", Tone.Warn, "Line finished")
        drill.mistakes == 0 -> Feedback("Every move from memory. ${drill.feedback.text}", Tone.Good, "Clean line")
        else -> Feedback("${drill.mistakes} mistake${if (drill.mistakes == 1) "" else "s"}. Those positions will come back.", Tone.Warn, "Line finished")
    }
}

@Composable
private fun Controls(drill: Drill, due: Int, onRestart: () -> Unit, onReview: () -> Unit, onNextLine: () -> Unit, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = BarHeight).background(Palette.Panel).padding(12.dp, 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (drill.phase) {
            Phase.Done -> {
                if (drill.reviewable) {
                    IconTap(R.drawable.ic_back, "Previous move", enabled = drill.cursor > 0) { drill.stepBy(-1) }
                    IconTap(R.drawable.ic_forward, "Next move", enabled = drill.cursor < drill.steps.size) { drill.stepBy(1) }
                }
                when {
                    due > 0 -> PrimaryButton("Review $due", Modifier.weight(1f), onReview)
                    drill.session is Session.Line -> PrimaryButton("Next line", Modifier.weight(1f), onNextLine)
                    else -> PrimaryButton("Done", Modifier.weight(1f), onBack)
                }
            }
            Phase.Answered -> PrimaryButton(if (drill.cardsLeft > 0) "Next" else "Finish", Modifier.weight(1f)) { drill.nextCard() }
            else -> {
                if (drill.session is Session.Line) BarButton(R.drawable.ic_restart, "Restart", Modifier.weight(1f), onClick = onRestart)
                BarButton(
                    R.drawable.ic_hint,
                    if (drill.hints == 0) "Hint" else "Show square",
                    Modifier.weight(1f),
                    enabled = drill.moving && drill.hints < 2 && !drill.teaching,
                ) { drill.hint() }
            }
        }
    }
}

/** Back, then the moves so far: the session's name only holds the space until there are moves. */
@Composable
private fun DrillHeader(drill: Drill, onBack: () -> Unit) {
    val scroll = rememberScrollState()
    val moves = drill.steps
    val current = if (drill.reviewable) drill.cursor - 1 else moves.lastIndex
    LaunchedEffect(moves.size) { scroll.animateScrollTo(scroll.maxValue, tween(160)) }
    Row(Modifier.fillMaxWidth().height(HeaderHeight).background(Palette.Panel).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTap(R.drawable.ic_back, "Back to home", onClick = onBack)
        if (moves.isEmpty()) Text(drill.session.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        else Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
            moves.forEachIndexed { i, step ->
                if (i % 2 == 0) Text("${i / 2 + 1}.", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, end = 2.dp))
                val latest = i == current
                Text(
                    step.ply.san,
                    fontSize = 13.sp,
                    fontWeight = if (latest) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier
                        .background(if (latest) Palette.Raised else Color.Transparent, RoundedCornerShape(4.dp))
                        .padding(6.dp, 2.dp),
                )
            }
        }
        if (drill.session is Session.Review) Text("${drill.cleared}/${drill.total}", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun PlayerStrip(piece: Int, name: String, status: String, muted: Boolean) {
    Row(Modifier.fillMaxWidth().height(StripHeight).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(piece),
            null,
            Modifier.size(30.dp).background(if (muted) Palette.Muted else Palette.Raised, RoundedCornerShape(4.dp)).padding(2.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(status, color = Palette.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun PlanRow(played: Set<String>) {
    Row(Modifier.fillMaxWidth().height(PlanHeight).padding(10.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PLAN.forEach { (uci, label) ->
            val queenside = uci == "e1g1" && "e1c1" in played
            val on = uci in played || queenside
            val fill by animateColorAsState(if (on) Palette.Green else Palette.Raised, tween(160))
            Box(
                Modifier.weight(1f).fillMaxHeight().background(fill, RoundedCornerShape(5.dp))
                    .semantics { stateDescription = if (on) "played" else "to play" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (queenside) "O-O-O" else label,
                    color = if (on) Palette.Text else Palette.Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    softWrap = false,
                )
            }
        }
    }
}
