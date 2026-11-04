package app.londontrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Tests = listOf(
    Triple("...Bd6", "Bg3", "Black offers to trade your best bishop. Step back to g3. If ...Bxg3, hxg3 gives your rook a half-open h-file for your rook and keeps your kingside solid."),
    Triple("...c5", "c3", "Black hits d4. c3 backs up your center; check for tactical threats before deciding whether to exchange pawns."),
    Triple("...Qb6", "Qb3 or Qc1", "The queen eyes b2, which your bishop stopped guarding when it left c1. Qb3 offers a trade; Qc1 guards it quietly."),
    Triple("...Nh5", "Bg3", "The knight chases your bishop, so step aside. If ...Nxg3, hxg3 gives your rook a half-open h-file."),
    Triple("...cxd4", "exd4", "Take back with the e-pawn. You keep a pawn in the center, and your bishop stays outside the chain."),
    Triple("...e5", "Check the center", "Black challenges d4 and opens lines. Look for a safe dxe5 capture, and check what Black would take in return before continuing the setup."),
    Triple("...Bf5", "Keep to the plan", "Black copies your bishop. Finish developing; Bd3 will challenge it."),
    Triple("...Ne4", "Nbd2 or Bd3", "The knight jumps into the center. Don't panic: your next developing moves challenge it."),
)

private val Plans = listOf(
    "Plant a knight on e5" to "Ne5 is your natural outpost, supported by your pawn on d4. Once your bishop leaves f4, the f-pawn can reinforce the knight. Then bring a rook or your queen toward Black's king.",
    "Break with e4" to "With the queen on e2 or c2 and a rook on e1, push e4. It opens the center for your bishops just when they're best placed.",
    "Keep your bishop" to "When ...Nh5 or ...h5 is in the air, h3 gives the bishop a safe home on h2. It's the piece that makes the London work.",
    "Hold the queenside" to "If Black starts ...b5 and ...b4 to pry at c3, slow it down with a3 or a4 before it gets there.",
)

@Composable
fun GuideScreen(rep: Repertoire, colors: BoardColors, onBack: () -> Unit) {
    var step by remember { mutableIntStateOf(PLAN.size) }
    val position = remember(step) { PLAN.take(step).fold(Position.start()) { p, move -> p.play(move.first) } }
    val move = PLAN.getOrNull(step - 1)
    Page {
        ScreenHeader("How the London works", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("THE SETUP")
            Text(
                "These eight moves are your usual setup. Answer threats first; in some positions, castling queenside is safer. Tap a move to see where it goes.",
                fontSize = 15.sp,
                lineHeight = 21.sp,
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Board(
                    position = position,
                    selected = -1,
                    targets = emptyList(),
                    lastMove = move?.first,
                    hintFrom = -1,
                    hintTo = -1,
                    mark = null,
                    arrows = emptyList(),
                    colors = colors,
                    modifier = Modifier.widthIn(max = 340.dp).aspectRatio(1f).clip(RoundedCornerShape(6.dp)),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PLAN.forEachIndexed { i, (_, label) ->
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(if (i == step - 1) Palette.Green else if (i < step) Palette.GreenLip else Palette.Raised)
                            .clickable(role = Role.Button) { step = i + 1 }
                            .padding(12.dp, 9.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            move?.let { (uci, label) ->
                Text(
                    "$label: ${rep.nodes.first { it.uci == uci && it.tag == "plan" }.why}",
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.heightIn(min = 64.dp),
                )
            }
            SectionTitle("BLACK'S USUAL TESTS")
            Tests.forEach { (black, answer, why) ->
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).background(Palette.Panel, RoundedCornerShape(10.dp)).padding(12.dp)) {
                    Row {
                        Text(black, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(answer, color = Palette.Green, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(why, color = Palette.Muted, fontSize = 14.sp, lineHeight = 19.sp)
                }
            }
            SectionTitle("ONCE THE SETUP IS DONE")
            Plans.forEach { (title, text) ->
                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(text, color = Palette.Muted, fontSize = 14.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}
