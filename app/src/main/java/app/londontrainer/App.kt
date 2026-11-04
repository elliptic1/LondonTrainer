package app.londontrainer

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import org.json.JSONArray

fun epochDay() = LocalDate.now().toEpochDay()

class Settings(private val prefs: SharedPreferences) {
    var sound by mutableStateOf(prefs.getBoolean("sound", true))
        private set
    var teach by mutableStateOf(prefs.getBoolean("teach", true))
        private set
    var board by mutableIntStateOf(prefs.getInt("board", 0).coerceIn(BoardThemes.indices))
        private set

    fun update(sound: Boolean = this.sound, teach: Boolean = this.teach, board: Int = this.board) {
        this.sound = sound
        this.teach = teach
        this.board = board
        prefs.edit().putBoolean("sound", sound).putBoolean("teach", teach).putInt("board", board).apply()
    }
}

class App(context: Context) {
    val rep = parseRepertoire(context.assets.open("repertoire.json").bufferedReader().use { it.readText() })
    private val prefs = context.getSharedPreferences("trainer", Context.MODE_PRIVATE)
    val settings = Settings(prefs)

    init {
        if (!prefs.getBoolean("migratedV1", false)) {
            val old = context.getSharedPreferences("progress-2551", Context.MODE_PRIVATE)
            val array = JSONArray(context.assets.open("legacy-positions.json").bufferedReader().use { it.readText() })
            val keys = List(array.length()) { array.getString(it) }
            val migrated = migrateCards(keys, old.getStringSet("seen", emptySet()).orEmpty(), old.getStringSet("missed", emptySet()).orEmpty(), epochDay())
            val current = parseCards(prefs.getString("cards", "").orEmpty())
            prefs.edit().putString("cards", Memory(migrated + current).serialize())
                .putInt("lines", prefs.getInt("lines", old.getInt("runs", 0)))
                .putInt("cleanLines", prefs.getInt("cleanLines", old.getInt("clean", 0)))
                .putBoolean("migratedV1", true).commit()
        }
    }

    // Saved on every graded answer, so leaving mid-line never loses what you just learned.
    val memory = Memory(
        parseCards(prefs.getString("cards", "").orEmpty()),
        prefs.getInt("streak", 0),
        prefs.getLong("lastDay", Long.MIN_VALUE),
        prefs.getInt("lines", 0),
        prefs.getInt("cleanLines", 0),
    ) { m ->
        prefs.edit()
            .putString("cards", m.serialize()) // Retain progress even when a session ends before its last move.
            .putInt("streak", m.streak).putLong("lastDay", m.lastDay)
            .putInt("lines", m.lines).putInt("cleanLines", m.cleanLines)
            .apply()
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val sounds = mapOf(
        Fx.Move to R.raw.fx_move, Fx.Capture to R.raw.fx_capture, Fx.Castle to R.raw.fx_castle,
        Fx.Wrong to R.raw.fx_wrong, Fx.Right to R.raw.fx_right, Fx.Done to R.raw.fx_done,
    ).mapValues { pool.load(context, it.value, 1) }

    fun play(fx: Fx) {
        if (settings.sound) pool.play(sounds.getValue(fx), 1f, 1f, 1, 0, 1f)
    }
}

