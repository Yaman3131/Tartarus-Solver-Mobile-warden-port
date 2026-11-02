package dev.warden.android

import android.content.Context
import dev.warden.android.data.BoardState
import dev.warden.android.data.Snapshot
import dev.warden.android.native.WardenNative
import dev.warden.android.ocr.MlKitOcrSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * The one solver session the app has: the activity's editor and the capture
 * service both drive it. The Rust side mutexes the handle, so frames can be
 * read while the UI polls.
 */
object Core {
    /** The session's state, polled at 10 Hz; null until the first poll. */
    val snapshot = MutableStateFlow<Snapshot?>(null)

    /** Why the last board or JNI call failed, if it did. */
    val error = MutableStateFlow<String?>(null)

    var rollouts: Int = 1500
        private set
    var theme: Int? = null
        private set
    var tools: Set<String> = emptySet()
        private set

    private var session = 0L
    private var watch = 0L
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True once the session exists; the UI shows the editor when false. */
    val ready: Boolean get() = session != 0L

    fun start(context: Context) {
        if (started) return
        started = true
        dir = context.filesDir
        WardenNative.wardenInit(context.filesDir.absolutePath)

        runCatching {
            JSONObject(WardenNative.wardenSettingsLoad()).optJSONObject("settings")
        }.getOrNull()?.let { s ->
            rollouts = s.optInt("rollouts").takeIf { it >= 50 } ?: 1500
            theme = if (s.isNull("theme")) null else s.optInt("theme")
            tools = s.optJSONArray("tools")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
                ?: emptySet()
        }

        val sink = MlKitOcrSink(context)
        val ocr = runCatching { WardenNative.wardenSetOcrSink(sink) }.getOrNull()
        if (ocr != null && JSONObject(ocr).has("error")) error.value = JSONObject(ocr).getString("error")
        // Loads ML Kit's model off the main thread, well before the first read.
        scope.launch(Dispatchers.Default) { sink.warmUp() }

        session = WardenNative.wardenSessionNew(rollouts, org.json.JSONArray(tools.toList()).toString(), theme ?: -1, -1)
        if (session == 0L) {
            error.value = "the solver session could not start"
            return
        }
        // The phone's frame is narrower than 16:9; the reader's mobile layout
        // path handles it (P0: width-scaled, centered).
        WardenNative.wardenSessionSetNarrowOk(session, true)
        WardenNative.wardenSessionSetTheme(session, theme ?: -1)
        watch = WardenNative.wardenWatchNew()

        scope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        var lastSummary: String? = null
        while (true) {
            val text = withContext(Dispatchers.Default) {
                WardenNative.wardenSessionPoll(session)
                WardenNative.wardenSessionSnapshot(session)
            }
            val s = Snapshot.parse(text)
            snapshot.value = s
            val summary = s?.let {
                val warns = it.reading?.warnings?.take(2)?.joinToString { w -> "${w.cell}:${w.text}" }
                "solving=${it.solving} screen=${it.reading?.screen} days=${it.reading?.daysLeft} " +
                    "advice=${it.advice?.label} problems=${it.problems} warns=$warns"
            }
            if (summary != lastSummary) {
                lastSummary = summary
                android.util.Log.i("wardenui", summary ?: "no snapshot")
            }
            delay(100)
        }
    }

    /** Installs a hand-built board as the reading on show. */
    fun setBoard(board: BoardState) {
        if (session == 0L) return
        val r = WardenNative.wardenSessionSetBoard(session, board.toJson())
        error.value = runCatching {
            val o = JSONObject(r)
            if (o.has("error")) o.getString("error") else null
        }.getOrNull()
    }

    fun setRollouts(n: Int) {
        rollouts = n
        if (session != 0L) WardenNative.wardenSessionSetRollouts(session, n)
        save()
    }

    fun setTheme(id: Int?) {
        theme = id
        if (session != 0L) WardenNative.wardenSessionSetTheme(session, id ?: -1)
        save()
    }

    fun setTools(keys: Set<String>) {
        tools = keys
        if (session != 0L) {
            WardenNative.wardenSessionSetTools(session, org.json.JSONArray(keys.toList()).toString())
        }
        save()
    }

    private fun save() {
        if (session == 0L) return
        runCatching {
            WardenNative.wardenSettingsSave(
                JSONObject()
                    .put("rollouts", rollouts)
                    .put("theme", theme ?: JSONObject.NULL)
                    .put("tools", org.json.JSONArray(tools.toList()))
                    .toString(),
            )
        }
    }

    /** Auto read: false holds reads until the player asks for one. */
    @Volatile
    var autoRead: Boolean = true

    @Volatile
    private var manual = false

    /** Asks for a read of the next frame, as the desktop's read hotkey does. */
    fun readNow() {
        manual = true
        readNext()
    }

    /** True once after [readNow]: a read the player asked for. */
    fun takeManual(): Boolean {
        val m = manual
        manual = false
        return m
    }

    /** Reads a PNG from the app's files as a live frame (a saved screenshot). */
    fun readPng(name: String): String? {
        if (session == 0L) return "no session"
        val path = java.io.File(dir, name)
        if (!path.exists()) return "no file: $path"
        val r = WardenNative.wardenSessionReadPng(session, path.absolutePath)
        return runCatching {
            val o = JSONObject(r)
            if (o.has("error")) o.getString("error") else null
        }.getOrNull()
    }

    private lateinit var dir: java.io.File

    /** A captured frame; the buffer is direct and holds `w*h*4` RGBA bytes. */
    fun readFrame(buffer: ByteBuffer, w: Int, h: Int, live: Boolean) {
        if (session != 0L) WardenNative.wardenSessionReadFrame(session, buffer, w, h, live)
    }

    /** True once after a read was held: the next frame is read whatever it shows. */
    fun wantsReread(): Boolean =
        session != 0L && WardenNative.wardenSessionWantsReread(session)

    /**
     * Samples a captured frame for the watch: true when it changed and
     * settled, so it should be read.
     */
    fun sample(buffer: ByteBuffer, w: Int, h: Int): Boolean {
        if (watch == 0L) return false
        val print = WardenNative.wardenFingerprint(buffer, w, h)
        return print.isNotEmpty() && WardenNative.wardenWatchSample(watch, print)
    }

    /** The next sample is read whatever it shows (a held read asks for one more). */
    fun readNext() {
        if (watch != 0L) WardenNative.wardenWatchReadNext(watch)
    }
}
