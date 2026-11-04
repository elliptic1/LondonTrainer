package app.londontrainer

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ReviewSize = 12

private sealed interface Screen {
    data object Home : Screen
    data object Guide : Screen
    data object Settings : Screen
    class Drilling(val drill: Drill) : Screen
}

class MainActivity : ComponentActivity() {
    private val today = mutableLongStateOf(epochDay())

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
                    val app by produceState<App?>(null) {
                        value = withContext(Dispatchers.IO) { App(ctx.applicationContext) }
                    }
                    if (app == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Palette.Green)
                        }
                    } else app?.let { Root(it, today.longValue) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        today.longValue = epochDay() // the app can sit open past midnight
    }
}

@Composable
private fun Root(app: App, today: Long) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val due = remember(app.memory.cards, today) { app.memory.dueIds(app.rep, today) }

    fun start(session: Session) {
        screen = Screen.Drilling(Drill(app.rep, app.memory, session, app.settings.teach, today, app::play))
    }
    fun review() = start(Session.Review(due.take(ReviewSize)))

    BackHandler(screen != Screen.Home) { screen = Screen.Home }

    Crossfade(screen, animationSpec = tween(140), label = "screen") { shown ->
        when (shown) {
            Screen.Home -> HomeScreen(
                app.rep, app.memory, today, due.size,
                onReview = ::review,
                onLine = { start(it?.let { g -> Session.Line(g.name, g.prefix) } ?: Session.Line()) },
                onGuide = { screen = Screen.Guide },
                onSettings = { screen = Screen.Settings },
            )
            Screen.Guide -> GuideScreen(app.rep, BoardThemes[app.settings.board]) { screen = Screen.Home }
            Screen.Settings -> SettingsScreen(app.settings, app.memory) { screen = Screen.Home }
            is Screen.Drilling -> DrillScreen(
                shown.drill,
                BoardThemes[app.settings.board],
                due.size,
                onBack = { screen = Screen.Home },
                onRestart = { start(shown.drill.session) },
                onReview = ::review,
                onNextLine = { start(shown.drill.session) },
            )
        }
    }
}
