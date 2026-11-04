package app.londontrainer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

@Composable
fun Coach(feedback: Feedback) {
    val ring by animateColorAsState(feedback.tone.color, tween(150))
    Row(Modifier.fillMaxWidth().padding(10.dp, 8.dp)) {
        Image(
            painterResource(R.drawable.piece_wn),
            null,
            Modifier.size(40.dp).background(ring, CircleShape).padding(5.dp),
        )
        Spacer(Modifier.width(10.dp))
        AnimatedContent(
            targetState = feedback.title to feedback.text,
            transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(70)) },
            modifier = Modifier.weight(1f),
            label = "coach",
        ) { (heading, body) ->
            Column(
                Modifier.fillMaxWidth().background(Palette.Raised, RoundedCornerShape(10.dp)).padding(12.dp, 9.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(heading, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(body, fontSize = 15.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
fun BarButton(icon: Int, label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
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
fun IconTap(icon: Int, label: String, size: Dp = 48.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(icon), label, Modifier.size(24.dp))
    }
}

@Composable
fun PrimaryButton(label: String, modifier: Modifier, onClick: () -> Unit) {
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

@Composable
fun SecondaryButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Raised)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ThinBar(share: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Raised)) {
        Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).fillMaxHeight().background(Palette.Green))
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Palette.Panel).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTap(R.drawable.ic_back, "Back", onClick = onBack)
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
