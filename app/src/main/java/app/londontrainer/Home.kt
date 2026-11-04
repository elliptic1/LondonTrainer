package app.londontrainer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Felt).windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxSize(), content = content)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        color = Palette.Muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
fun HomeScreen(
    rep: Repertoire,
    memory: Memory,
    today: Long,
    due: Int,
    onReview: () -> Unit,
    onLine: (Group?) -> Unit,
    onGuide: () -> Unit,
    onSettings: () -> Unit,
) {
    val ready = remember(memory.cards) { memory.readiness(rep) }
    val moves = remember(memory.cards) { memory.movesKnownFromStart(rep) }
    Page {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("London Trainer", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconTap(R.drawable.ic_settings, "Settings", onClick = onSettings)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Readiness(ready[0].toFloat(), moves, memory.streakOn(today), memory.lines, memory.cleanLines)
            Spacer(Modifier.height(16.dp))
            when {
                due > 0 -> {
                    PrimaryButton("Review $due position${if (due == 1) "" else "s"}", Modifier.fillMaxWidth(), onReview)
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Play a line", Modifier.fillMaxWidth()) { onLine(null) }
                }
                memory.cards.isEmpty() -> PrimaryButton("Play your first line", Modifier.fillMaxWidth()) { onLine(null) }
                else -> PrimaryButton("Play a line", Modifier.fillMaxWidth()) { onLine(null) }
            }
            SectionTitle("PRACTICE BY OPENING")
            rep.groups.forEach { g -> GroupRow(g, memory.groupReadiness(rep, g.prefix, ready).toFloat()) { onLine(g) } }
            SectionTitle("LEARN")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Panel)
                    .clickable(role = Role.Button, onClick = onGuide).padding(14.dp),
            ) {
                Column {
                    Text("How the London works", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("The setup, Black's tests, and what to do next", color = Palette.Muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Readiness(share: Float, moves: Double, streak: Int, lines: Int, clean: Int) {
    val swept by animateFloatAsState(share, tween(600))
    val percent = (share * 100).roundToInt()
    Row(
        Modifier.fillMaxWidth().background(Palette.Panel, RoundedCornerShape(12.dp)).padding(14.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                val stroke = Stroke(8.dp.toPx(), cap = StrokeCap.Round)
                drawArc(Palette.Raised, -90f, 360f, false, style = stroke)
                if (swept > 0f) drawArc(Palette.Green, -90f, 360f * swept, false, style = stroke)
            }
            Text("$percent%", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("London readiness", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                if (moves < 1) "Your chance of knowing every move in a typical line."
                else "$percent% of typical lines known from start to finish.",
                color = Palette.Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            if (streak > 0 || lines > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(
                        if (streak > 0) "$streak-day streak" else null,
                        if (lines > 0) "$clean/$lines clean lines" else null,
                    ).joinToString(" · "),
                    color = Palette.Green,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun GroupRow(group: Group, share: Float, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 9.dp, horizontal = 2.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(group.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(group.blurb, color = Palette.Muted, fontSize = 13.sp)
            }
            Text("${(share * 100).roundToInt()}%", color = Palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        ThinBar(share, Modifier.fillMaxWidth())
    }
}
