package app.londontrainer

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object Palette {
    val Felt = Color(0xFF302E2B)
    val Panel = Color(0xFF262522)
    val Raised = Color(0xFF3C3A37)
    val Text = Color(0xFFFFFFFF)
    val Muted = Color(0xFFB4B3B1)
    val Green = Color(0xFF81B64C)
    val GreenLip = Color(0xFF45753C)
    val Blue = Color(0xFF5C9BD1)
    val Yellow = Color(0xFFE8B13C)
    val Bad = Color(0xFFE0483A)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            val ctx = LocalContext.current
            val colors = darkColorScheme(
                primary = Palette.Green,
                background = Palette.Felt,
                surface = Palette.Felt,
                onBackground = Palette.Text,
                onSurface = Palette.Text,
            )
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    val nodes by produceState<List<Node>?>(null) {
                        value = withContext(Dispatchers.IO) {
                            parseRepertoire(ctx.assets.open("repertoire.json").bufferedReader().readText())
                        }
                    }
                    nodes?.let { Trainer(it) }
                }
            }
        }
    }
}

private class Progress(context: Context, size: Int) {
    // Ids change when the repertoire is regenerated, so progress is scoped to its size.
    private val prefs = context.getSharedPreferences("progress-$size", Context.MODE_PRIVATE)
    var seen by mutableStateOf(load("seen"))
    var missed by mutableStateOf(load("missed"))
    var runs by mutableIntStateOf(prefs.getInt("runs", 0))
    var cleanRuns by mutableIntStateOf(prefs.getInt("clean", 0))

    private fun load(key: String) = prefs.getStringSet(key, emptySet())!!.map { it.toInt() }.toSet()

    fun save() = prefs.edit()
        .putStringSet("seen", seen.map { it.toString() }.toSet())
        .putStringSet("missed", missed.map { it.toString() }.toSet())
        .putInt("runs", runs).putInt("clean", cleanRuns)
        .apply()
}

private data class Feedback(val text: String, val tone: Tone)

enum class Tone(val title: String, val color: Color) {
    Info("Your move", Palette.Raised),
    Good("Correct", Palette.Green),
    Adjust("Good adjustment", Palette.Blue),
    Warn("Playable", Palette.Yellow),
    Bad("Not this time", Palette.Bad),
}

@Composable
private fun Trainer(nodes: List<Node>) {
    val ctx = LocalContext.current
    val progress = remember { Progress(ctx, nodes.size) }
    var run by remember { mutableIntStateOf(0) }
    var pos by remember(run) { mutableStateOf(Position.start()) }
    var nodeId by remember(run) { mutableIntStateOf(0) }
    var selected by remember(run) { mutableIntStateOf(-1) }
    var lastMove by remember(run) { mutableStateOf<String?>(null) }
    var mark by remember(run) { mutableStateOf<Mark?>(null) }
    var moves by remember(run) { mutableStateOf(listOf<String>()) }
    var played by remember(run) { mutableStateOf(setOf<String>()) }
    var mistakes by remember(run) { mutableIntStateOf(0) }
    var hints by remember(run) { mutableIntStateOf(0) }
    var slipped by remember(run) { mutableStateOf(false) }
    var blackToReply by remember(run) { mutableStateOf(false) }
    var done by remember(run) { mutableStateOf(false) }
    var feedback by remember(run) { mutableStateOf(Feedback("You're White. Play the London: drag a piece to its square, or tap one then the other.", Tone.Info)) }

    val node = nodes[nodeId]
    val legal = remember(pos) { pos.legalMoves() }

    fun finish() {
        done = true
        progress.runs++
        if (mistakes == 0) progress.cleanRuns++
        progress.save()
    }

    /** True if the move was played. */
    fun tryMove(move: String): Boolean {
        selected = -1
        val to = Position.sq(move, 2)
        return when (val result = judge(node, move)) {
            Attempt.Correct -> {
                progress.seen += nodeId
                if (!slipped) progress.missed -= nodeId
                pos = pos.play(move)
                lastMove = move
                moves = moves + node.san
                played = played + move
                hints = 0
                slipped = false
                val tone = if (node.tag == "plan") Tone.Good else Tone.Adjust
                feedback = Feedback("${node.san}: ${node.why}", tone)
                mark = Mark(to, tone)
                if (node.replies.isEmpty()) finish() else blackToReply = true
                true
            }
            is Attempt.Playable -> {
                feedback = Feedback(result.text, Tone.Warn)
                mark = Mark(to, Tone.Warn)
                false
            }
            is Attempt.Wrong -> {
                mistakes++
                slipped = true
                progress.missed += nodeId
                hints = maxOf(hints, 1)
                feedback = Feedback(result.text, Tone.Bad)
                mark = Mark(to, Tone.Bad)
                false
            }
        }
    }

    LaunchedEffect(blackToReply) {
        if (!blackToReply) return@LaunchedEffect
        delay(450)
        val reply = chooseReply(nodes, node, progress.seen, progress.missed)
        pos = pos.play(reply.uci)
        lastMove = reply.uci
        moves = moves + reply.san
        mark = null
        nodeId = reply.next
        blackToReply = false
    }

    Column(
        Modifier.fillMaxSize().background(Palette.Felt)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        TopBar(progress.seen.size, nodes.size, progress.cleanRuns, progress.runs)
        MoveStrip(moves)
        PlayerStrip(R.drawable.piece_bk, "Black", if (blackToReply) "Thinking…" else "", muted = true)
        Board(
            position = pos,
            selected = selected,
            targets = if (selected >= 0) legal.filter { Position.sq(it, 0) == selected }.map { Position.sq(it, 2) } else emptyList(),
            lastMove = lastMove,
            hintFrom = if (hints >= 1 && !done) Position.sq(node.uci, 0) else -1,
            hintTo = if (hints >= 2 && !done) Position.sq(node.uci, 2) else -1,
            mark = mark,
            onTap = { square ->
                if (done || blackToReply) return@Board
                val move = legal.firstOrNull { selected >= 0 && Position.sq(it, 0) == selected && Position.sq(it, 2) == square }
                if (move != null) tryMove(move) else selected = -1
            },
            onPick = { square ->
                val movable = !done && !blackToReply && pos.board[square].let { it != '.' && it.isUpperCase() }
                if (movable) {
                    selected = square
                    mark = null
                }
                movable
            },
            onDrop = { square ->
                val move = legal.firstOrNull { Position.sq(it, 0) == selected && Position.sq(it, 2) == square }
                when {
                    move != null -> tryMove(move)
                    square != selected -> { selected = -1; false } // dropped back on its own square: stays selected for tap-to-move
                    else -> false
                }
            },
        )
        PlayerStrip(R.drawable.piece_wk, "You", if (done || blackToReply) "" else "Your move", muted = false)
        PlanRow(played)
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            when {
                !done -> Coach(feedback.tone.title, feedback.text, feedback.tone)
                mistakes == 0 -> Coach("Clean run!", "No mistakes. ${feedback.text}", Tone.Good)
                else -> Coach(
                    "Line finished",
                    "$mistakes mistake${if (mistakes == 1) "" else "s"}. Those positions will come back.",
                    Tone.Warn,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Palette.Panel).navigationBarsPadding().padding(12.dp, 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (done) {
                PrimaryButton("Start over", Modifier.weight(1f)) { run++ }
            } else {
                BarButton(R.drawable.ic_restart, "Restart", Modifier.weight(1f)) { run++ }
                BarButton(
                    R.drawable.ic_hint,
                    if (hints == 0) "Hint" else "Show square",
                    Modifier.weight(1f),
                    enabled = !blackToReply && hints < 2,
                ) {
                    hints++
                    slipped = true
                    progress.missed += nodeId
                }
            }
        }
    }
}

@Composable
private fun TopBar(seen: Int, total: Int, clean: Int, runs: Int) {
    val share by animateFloatAsState(seen / total.toFloat(), tween(240))
    Column(Modifier.background(Palette.Panel)) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("London Trainer", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("$seen/$total positions · $clean/$runs clean", color = Palette.Muted, fontSize = 12.sp)
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(Palette.Raised)) {
            Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(Palette.Green))
        }
    }
}

@Composable
private fun MoveStrip(moves: List<String>) {
    val scroll = rememberScrollState()
    LaunchedEffect(moves.size) { scroll.animateScrollTo(scroll.maxValue, tween(160)) }
    Row(
        Modifier.fillMaxWidth().height(36.dp).background(Palette.Panel).horizontalScroll(scroll).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        moves.forEachIndexed { i, san ->
            if (i % 2 == 0) Text("${i / 2 + 1}.", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, end = 2.dp))
            val latest = i == moves.lastIndex
            Text(
                san,
                fontSize = 13.sp,
                fontWeight = if (latest) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier
                    .background(if (latest) Palette.Raised else Color.Transparent, RoundedCornerShape(4.dp))
                    .padding(6.dp, 2.dp),
            )
        }
    }
}

@Composable
private fun PlayerStrip(piece: Int, name: String, status: String, muted: Boolean) {
    val shown by animateFloatAsState(if (status.isEmpty()) 0f else 1f, tween(120))
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(piece),
            null,
            Modifier.size(32.dp).background(if (muted) Palette.Muted else Palette.Raised, RoundedCornerShape(4.dp)).padding(2.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(status, color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.alpha(shown))
    }
}

@Composable
private fun PlanRow(played: Set<String>) {
    Row(Modifier.fillMaxWidth().padding(10.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PLAN.forEach { (uci, label) ->
            val on = uci in played
            val fill by animateColorAsState(if (on) Palette.Green else Palette.Raised, tween(160))
            Text(
                label,
                color = if (on) Palette.Text else Palette.Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                softWrap = false,
                modifier = Modifier.weight(1f).background(fill, RoundedCornerShape(5.dp)).padding(vertical = 6.dp)
                    .semantics { stateDescription = if (on) "played" else "to play" },
            )
        }
    }
}

@Composable
private fun Coach(title: String, text: String, tone: Tone) {
    val ring by animateColorAsState(tone.color, tween(150))
    Row(Modifier.fillMaxWidth().padding(10.dp, 8.dp)) {
        Image(
            painterResource(R.drawable.piece_wn),
            null,
            Modifier.size(40.dp).background(ring, CircleShape).padding(5.dp),
        )
        Spacer(Modifier.width(10.dp))
        AnimatedContent(
            targetState = title to text,
            transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(70)) },
            modifier = Modifier.weight(1f),
            label = "coach",
        ) { (heading, body) ->
            Column(Modifier.fillMaxWidth().background(Palette.Raised, RoundedCornerShape(10.dp)).padding(12.dp, 9.dp)) {
                Text(heading, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(body, fontSize = 15.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun BarButton(icon: Int, label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .heightIn(min = 52.dp).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(icon), null, Modifier.size(22.dp))
        Text(label, color = Palette.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PrimaryButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val sink by animateDpAsState(if (pressed) 3.dp else 0.dp, tween(60))
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(56.dp)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(52.dp).background(Palette.GreenLip, shape))
        Box(
            Modifier.fillMaxWidth().offset(y = sink).height(52.dp).background(Palette.Green, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}
