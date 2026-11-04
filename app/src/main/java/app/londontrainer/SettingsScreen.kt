package app.londontrainer

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(settings: Settings, memory: Memory, onBack: () -> Unit) {
    val links = LocalUriHandler.current
    var confirming by remember { mutableStateOf(false) }
    Page {
        ScreenHeader("Settings", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("TRAINING")
            Toggle("Teach new positions", "Show the move with an arrow the first time you meet a position.", settings.teach) { settings.update(teach = it) }
            SectionTitle("SOUND")
            Toggle("Sound effects", "Piece sounds, and a chime when you finish.", settings.sound) { settings.update(sound = it) }
            SectionTitle("BOARD")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BoardThemes.forEachIndexed { i, theme ->
                    val on = i == settings.board
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.RadioButton) { settings.update(board = i) }
                            .semantics(mergeDescendants = true) { selected = on; contentDescription = "${theme.name} board" }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(Modifier.fillMaxWidth().height(44.dp).border(2.dp, if (on) Palette.Green else Color.Transparent, RoundedCornerShape(6.dp)).padding(3.dp).clip(RoundedCornerShape(4.dp))) {
                            Box(Modifier.weight(1f).fillMaxHeight().background(theme.light))
                            Box(Modifier.weight(1f).fillMaxHeight().background(theme.dark))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(theme.name, fontSize = 13.sp, color = if (on) Palette.Text else Palette.Muted, fontWeight = FontWeight.Bold)
                    }
                }
            }
            SectionTitle("PROGRESS")
            Text(
                "Reset progress",
                color = Palette.Bad,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { confirming = true }.padding(vertical = 12.dp),
            )
            SectionTitle("ABOUT")
            Text(
                "Black's replies come from real club games in the Lichess open database, and every position is checked with Stockfish. Works offline, with no account and no tracking. Chess pieces and sounds are original to London Trainer.",
                color = Palette.Muted,
                fontSize = 14.sp,
                lineHeight = 19.sp,
            )
            Text(
                "Privacy policy",
                color = Palette.Green,
                fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { runCatching { links.openUri("https://elliptic1.github.io/LondonTrainer/privacy.html") } }
                    .padding(vertical = 14.dp),
            )
            Spacer(Modifier.height(20.dp))
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Reset all progress?") },
            text = { Text("This clears every position you've learned, your streak, and your stats. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = { memory.reset(); confirming = false }) { Text("Reset", color = Palette.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).toggleable(value = on, role = Role.Switch, onValueChange = set).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Switch(on, null, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Green, checkedThumbColor = Palette.Text, uncheckedThumbColor = Palette.Muted, uncheckedTrackColor = Palette.Raised))
    }
}
