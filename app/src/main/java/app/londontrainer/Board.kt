package app.londontrainer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val SlideMs = 110
private const val LiftMs = 70
private const val BadgeMs = 130
private const val LiftedScale = 1.6f
private const val LiftedRise = 0.8f // in squares, so the finger doesn't hide the piece

class BoardColors(val name: String, val light: Color, val dark: Color, val lastLight: Color, val lastDark: Color)

val BoardThemes = listOf(
    BoardColors("Green", Color(0xFFEBECD0), Color(0xFF739552), Color(0xFFF5F682), Color(0xFFB9CA43)),
    BoardColors("Walnut", Color(0xFFF0D9B5), Color(0xFFB58863), Color(0xFFF6EB72), Color(0xFFDCC34B)),
    BoardColors("Slate", Color(0xFFDEE3E6), Color(0xFF7D95A6), Color(0xFFE6EDA0), Color(0xFFA9BD6F)),
)

private val HintColor = Color(0xA081B64C)
private val MoveDot = Color(0x24000000)
private val HoverRing = Color(0x40FFFFFF)
private val PieceArt = mapOf(
    'K' to R.drawable.piece_wk, 'Q' to R.drawable.piece_wq, 'R' to R.drawable.piece_wr,
    'B' to R.drawable.piece_wb, 'N' to R.drawable.piece_wn, 'P' to R.drawable.piece_wp,
    'k' to R.drawable.piece_bk, 'q' to R.drawable.piece_bq, 'r' to R.drawable.piece_br,
    'b' to R.drawable.piece_bb, 'n' to R.drawable.piece_bn, 'p' to R.drawable.piece_bp,
)

/** A verdict shown as a badge on the square the piece went (or tried to go) to. */
class Mark(val square: Int, val tone: Tone)

/** Points out a move: what to play, or what Black has in mind. */
class Arrow(val from: Int, val to: Int, val color: Color)

/** Positions are in squares from the board's top left, so they survive a resize. */
private class Flight(val piece: Char, val square: Int, val start: Offset, val end: Offset, val scale: Float)

private class Motion(val flights: List<Flight>, val fading: List<Pair<Int, Char>>) {
    val t = Animatable(0f)
    val covered = flights.map { it.square }.toSet()
}

/** The piece that just left the finger, where it was and how big it was drawn. */
private class Release(val from: Int, val at: Offset, val scale: Float)

private fun center(s: Int) = Offset(s % 8 + 0.5f, 7 - s / 8 + 0.5f)

private fun motionBetween(before: CharArray, after: CharArray, release: Release?): Motion {
    val (slides, left) = diffBoards(before, after)
    val flights = slides.map { (from, to) ->
        val held = release?.takeIf { it.from == from }
        Flight(after[to], to, held?.at ?: center(from), center(to), held?.scale ?: 1f)
    }
    // Nothing moved: the move was refused, so the held piece goes home.
    val home = release?.takeIf { slides.isEmpty() && before[it.from] != '.' }?.let { Flight(before[it.from], it.from, it.at, center(it.from), it.scale) }
    return Motion(flights + listOfNotNull(home), left.map { it to before[it] })
}

@Composable
fun Board(
    position: Position,
    selected: Int,
    targets: List<Int>,
    lastMove: String?,
    hintFrom: Int,
    hintTo: Int,
    mark: Mark?,
    arrows: List<Arrow>,
    colors: BoardColors,
    modifier: Modifier,
    onTap: (Int) -> Unit = {},
    onPick: (Int) -> Boolean = { false },
    onDrop: (Int) -> Boolean = { false },
) {
    val art = PieceArt.mapValues { ImageBitmap.imageResource(it.value) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)
    val pick by rememberUpdatedState(onPick)
    val drop by rememberUpdatedState(onDrop)
    val current by rememberUpdatedState(selected)

    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragAt by remember { mutableStateOf(Offset.Zero) }
    var grabbedAt by remember { mutableStateOf(Offset.Zero) }
    val lift = remember { Animatable(0f) }
    fun heldCenter(cell: Float, l: Float) =
        (dragAt + (center(dragFrom) * cell - grabbedAt) * (1 - l)) / cell + Offset(0f, -LiftedRise * l)

    // Built during composition so the first frame of a move already shows the piece at its start.
    val shown = remember { arrayOf(position) }
    val released = remember { arrayOfNulls<Release>(1) }
    var refused by remember { mutableIntStateOf(0) }
    val motion = remember(position, refused) {
        motionBetween(shown[0].board, position.board, released[0]).also {
            shown[0] = position
            released[0] = null
        }
    }
    LaunchedEffect(motion) { motion.t.animateTo(1f, tween(SlideMs, easing = FastOutSlowInEasing)) }

    val badge = remember(mark) { Animatable(0f) }
    LaunchedEffect(mark) {
        if (mark == null) return@LaunchedEffect
        haptics.performHapticFeedback(if (mark.tone == Tone.Bad) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
        delay(SlideMs.toLong())
        badge.animateTo(1f, tween(BadgeMs, easing = FastOutSlowInEasing))
    }

    val measurer = rememberTextMeasurer()
    val labels = remember(measurer) {
        val style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold)
        "abcdefgh".map { measurer.measure(it.toString(), style) } to "12345678".map { measurer.measure(it.toString(), style) }
    }

    Box(
        modifier
            .semantics { contentDescription = "Chess board, White at the bottom" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat()
                    val from = squareAt(down.position, width)
                    if (from < 0) return@awaitEachGesture
                    val again = current == from
                    if (!pick(from)) {
                        if (waitForUpOrCancellation() != null) tap(from)
                        return@awaitEachGesture
                    }
                    val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (slop == null) {
                        if (again) tap(from) // a second tap on the selected piece puts it down
                        return@awaitEachGesture
                    }
                    grabbedAt = down.position
                    dragAt = slop.position
                    dragFrom = from
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    scope.launch { lift.animateTo(1f, tween(LiftMs, easing = FastOutSlowInEasing)) }
                    val finished = drag(slop.id) {
                        dragAt = it.position
                        it.consume()
                    }
                    released[0] = Release(from, heldCenter(width / 8f, lift.value), 1 + (LiftedScale - 1) * lift.value)
                    dragFrom = -1
                    scope.launch { lift.snapTo(0f) }
                    if (!finished || !drop(squareAt(dragAt, width))) refused++
                }
            },
    ) {
    Canvas(Modifier.fillMaxSize()) {
        val cell = size.width / 8f
        fun corner(s: Int) = Offset(s % 8 * cell, (7 - s / 8) * cell)
        val square = Size(cell, cell)
        fun light(s: Int) = (s % 8 + s / 8) % 2 == 1
        val t = motion.t.value
        val dragging = dragFrom >= 0

        for (s in 0..63) {
            drawRect(if (light(s)) colors.light else colors.dark, corner(s), square)
            val marked = s == selected || lastMove != null && (s == Position.sq(lastMove, 0) || s == Position.sq(lastMove, 2))
            if (marked) drawRect(if (light(s)) colors.lastLight else colors.lastDark, corner(s), square, alpha = 0.85f)
            if (s == hintFrom || s == hintTo) drawRect(HintColor, corner(s), square)
            if (s == mark?.square && mark.tone == Tone.Bad) drawRect(Palette.Bad, corner(s), square, alpha = 0.6f * badge.value)
        }
        for (i in 0..7) {
            val file = labels.first[i]
            val rank = labels.second[i]
            val pad = cell * 0.05f
            drawText(file, if (light(i)) colors.dark else colors.light, Offset((i + 1) * cell - file.size.width - pad, size.height - file.size.height))
            drawText(rank, if (light(i * 8)) colors.dark else colors.light, Offset(pad, (7 - i) * cell))
        }
        for (s in targets) {
            val c = corner(s) + Offset(cell / 2, cell / 2)
            if (position.board[s] == '.') drawCircle(MoveDot, cell * 0.17f, c)
            else drawCircle(MoveDot, cell * 0.44f, c, style = Stroke(cell * 0.1f))
        }

        for ((s, piece) in motion.fading) drawPiece(art.getValue(piece), center(s) * cell, cell, alpha = 1 - t)
        for (s in 0..63) {
            val p = position.board[s]
            if (p != '.' && s != dragFrom && s !in motion.covered) drawPiece(art.getValue(p), center(s) * cell, cell)
        }
        for (f in motion.flights) {
            if (f.square == dragFrom) continue
            drawPiece(art.getValue(f.piece), lerp(f.start, f.end, t) * cell, cell * (f.scale + (1 - f.scale) * t))
        }
        for (a in arrows) drawArrow(a, cell)
        if (mark != null && badge.value > 0f) drawBadge(mark, corner(mark.square), cell, badge.value)

        // Restart under a held piece (second finger) can empty its square.
        if (dragging && position.board[dragFrom] != '.') {
            val l = lift.value
            val over = squareAt(dragAt, size.width)
            if (over >= 0) drawCircle(HoverRing, cell * l, corner(over) + Offset(cell / 2, cell / 2))
            val held = heldCenter(cell, l) * cell
            val big = cell * (1 + (LiftedScale - 1) * l)
            // A tinted copy underneath reads as a soft shadow without a blur pass.
            drawPiece(
                art.getValue(position.board[dragFrom]),
                held + Offset(0f, cell * 0.08f * l),
                big,
                tint = ColorFilter.tint(Color(0x40000000), BlendMode.SrcIn),
            )
            drawPiece(art.getValue(position.board[dragFrom]), held, big)
        }
    }
    SquaresForScreenReaders(position, selected, targets, tap, pick)
    }
}

/** The board is one canvas to the eye, but a screen reader needs 64 squares to explore and tap. */
@Composable
private fun SquaresForScreenReaders(position: Position, selected: Int, targets: List<Int>, tap: (Int) -> Unit, pick: (Int) -> Boolean) {
    Column(Modifier.fillMaxSize()) {
        for (rank in 7 downTo 0) Row(Modifier.fillMaxWidth().weight(1f)) {
            for (file in 0..7) {
                val s = rank * 8 + file
                Box(
                    Modifier.weight(1f).fillMaxSize().clearAndSetSemantics {
                        contentDescription = "${'a' + file}${rank + 1}, ${pieceName(position.board[s])}"
                        if (s == selected) stateDescription = "selected" else if (s in targets) stateDescription = "can move here"
                        onClick { if (s == selected || !pick(s)) tap(s); true }
                    },
                )
            }
        }
    }
}

private fun pieceName(c: Char) = if (c == '.') "empty" else {
    val side = if (c.isUpperCase()) "white" else "black"
    val name = when (c.lowercaseChar()) { 'k' -> "king"; 'q' -> "queen"; 'r' -> "rook"; 'b' -> "bishop"; 'n' -> "knight"; else -> "pawn" }
    "$side $name"
}

private fun DrawScope.drawArrow(a: Arrow, cell: Float) {
    val from = center(a.from) * cell
    val to = center(a.to) * cell
    val along = (to - from) / (to - from).getDistance()
    val across = Offset(-along.y, along.x)
    val neck = to - along * (cell * 0.42f)
    val color = a.color.copy(alpha = 0.85f)
    drawLine(color, from + along * (cell * 0.28f), neck, cell * 0.15f)
    drawPath(
        Path().apply {
            moveTo(to.x, to.y)
            (neck + across * (cell * 0.26f)).let { lineTo(it.x, it.y) }
            (neck - across * (cell * 0.26f)).let { lineTo(it.x, it.y) }
            close()
        },
        color,
    )
}

private fun DrawScope.drawPiece(image: ImageBitmap, center: Offset, side: Float, alpha: Float = 1f, tint: ColorFilter? = null) {
    val px = ceil(side).toInt()
    drawImage(
        image,
        dstOffset = IntOffset((center.x - side / 2).roundToInt(), (center.y - side / 2).roundToInt()),
        dstSize = IntSize(px, px),
        alpha = alpha,
        colorFilter = tint,
        filterQuality = FilterQuality.High,
    )
}

private fun DrawScope.drawBadge(mark: Mark, corner: Offset, cell: Float, pop: Float) {
    val r = cell * 0.2f * pop
    val c = corner + Offset(cell * 0.8f, cell * 0.2f) // Stays inside the square so it is not clipped at the board edge.
    val stroke = cell * 0.055f * pop
    fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
        drawLine(Color.White, c + Offset(x0, y0) * r, c + Offset(x1, y1) * r, stroke, StrokeCap.Round)
    drawCircle(Color(0x40000000), r * 1.08f, c + Offset(0f, cell * 0.015f))
    drawCircle(mark.tone.color, r, c)
    when (mark.tone) {
        Tone.Bad -> { line(-0.42f, -0.42f, 0.42f, 0.42f); line(0.42f, -0.42f, -0.42f, 0.42f) }
        Tone.Warn -> { line(0f, -0.52f, 0f, 0.14f); line(0f, 0.52f, 0f, 0.52f) }
        else -> { line(-0.46f, 0.04f, -0.12f, 0.4f); line(-0.12f, 0.4f, 0.48f, -0.34f) }
    }
}

/** The square at `p` on a board `width` wide, or -1 off the board. */
private fun squareAt(p: Offset, width: Float): Int {
    if (p.x !in 0f..<width || p.y !in 0f..<width) return -1
    val cell = width / 8f
    return (7 - (p.y / cell).toInt()) * 8 + (p.x / cell).toInt()
}
