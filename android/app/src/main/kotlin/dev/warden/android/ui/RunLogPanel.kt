package dev.warden.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.warden.android.data.BoardState.Companion.cellLabel
import org.json.JSONObject
import java.io.File

/** A run folder's name, as `src/app/runlog.rs` writes it. */
private val RUN_FOLDER = Regex("""\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}(-\d+)?""")

private val GREY = Color(0xFF8A8A96)

/** One line of a run's log.jsonl. */
private data class LogEvent(val time: String, val kind: String, val text: String)

/**
 * The runs the session kept under `filesDir/runs`: each folder is one run,
 * with its log.jsonl (reads, advice, corrections, misses) and the frames it
 * saved. Newest first; a run opens under its name.
 */
@Composable
fun RunLogPanel() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    val root = remember(context) { File(context.filesDir, "runs") }
    val runs = remember(tick, root) { runFolders(root) }

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Run log", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { tick++ }) { Text("Refresh") }
        }
        if (runs.isEmpty()) {
            Text(
                "No runs logged yet. A run is logged while the game is being read.",
                style = MaterialTheme.typography.bodySmall,
                color = GREY,
            )
        }
        runs.forEach { dir ->
            val name = dir.name
            val on = open == name
            TextButton(onClick = { open = if (on) null else name }) {
                Text((if (on) "▾ " else "▸ ") + name)
            }
            if (on) RunDetail(dir)
        }
    }
}

/** A run's lines and frames. */
@Composable
private fun RunDetail(dir: File) {
    val events = remember(dir.path) { readEvents(dir) }
    val frames = remember(dir.path) { frameFiles(dir) }
    var shown by remember(dir.path) { mutableStateOf<String?>(null) }

    Column(
        Modifier.padding(start = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (events.isEmpty()) {
            Text("Nothing written to this run's log yet.", style = MaterialTheme.typography.bodySmall, color = GREY)
        }
        events.forEach { EventRow(it) }
        if (frames.isNotEmpty()) {
            Text("Frames", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                frames.forEach { f ->
                    FilterChip(
                        selected = shown == f.name,
                        onClick = { shown = f.name },
                        label = { Text(f.name) },
                    )
                }
            }
            shown?.let { FrameImage(File(dir, it)) }
        }
    }
}

@Composable
private fun EventRow(e: LogEvent) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(e.time, Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall, color = GREY)
        Text(e.kind, Modifier.width(52.dp), style = MaterialTheme.typography.labelSmall, color = kindColour(e.kind))
        Text(e.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FrameImage(file: File) {
    val bitmap = remember(file.path, file.lastModified()) {
        android.graphics.BitmapFactory.decodeFile(
            file.path,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 },
        )
    }
    if (bitmap != null) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = file.name,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
    } else {
        Text("Could not read ${file.name}", style = MaterialTheme.typography.bodySmall, color = GREY)
    }
}

private fun runFolders(root: File): List<File> =
    root.listFiles()
        ?.filter { it.isDirectory && RUN_FOLDER.matches(it.name) }
        ?.sortedByDescending { it.name }
        ?: emptyList()

private fun frameFiles(dir: File): List<File> =
    dir.listFiles { f -> f.isFile && f.name.startsWith("frame-") && f.name.endsWith(".jpg") }
        ?.sortedBy { it.name }
        ?: emptyList()

private fun readEvents(dir: File): List<LogEvent> {
    val file = File(dir, "log.jsonl")
    if (!file.isFile) return emptyList()
    return runCatching {
        file.readLines().mapNotNull { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
            val at = o.optString("at")
            LogEvent(
                time = if (at.length >= 19) at.substring(11, 19) else at,
                kind = o.optString("kind"),
                text = describe(o),
            )
        }
    }.getOrDefault(emptyList())
}

/** One line as the viewer reads it; the fields are `Session`'s log events. */
private fun describe(o: JSONObject): String = when (o.optString("kind")) {
    "read" -> buildString {
        append(o.optString("screen"))
        if (o.has("days_left") && !o.isNull("days_left")) {
            append(" · ${o.optInt("days_left")} to Riot")
        }
        o.optJSONObject("model")?.let {
            append(" · day ${it.optInt("day")} · warden ${cellLabel(it.optInt("warden"))}")
        }
        val warnings = o.optJSONArray("warnings")
        if (warnings != null && warnings.length() > 0) {
            append(" · ${warnings.length()} warning(s)")
        }
    }
    "advice" -> buildString {
        append(o.optString("pick"))
        val also = o.optJSONArray("also_good")
        if (also != null && also.length() > 0) {
            append(" (also: ${strings(also).joinToString()})")
        }
    }
    "edit" -> "cell ${o.optInt("cell")}: ${o.optString("edit")}"
    "miss" -> "${o.optString("screen")} — ${o.optString("line")}"
    "run_end" -> buildString {
        append("run over")
        val vaulted = o.optJSONArray("vaulted")
        if (vaulted != null && vaulted.length() > 0) {
            append(", vaulted ${strings(vaulted).joinToString()}")
        }
    }
    "window" -> buildString {
        append(o.optString("window"))
        if (o.has("ocr") && !o.isNull("ocr")) append(" · OCR ${o.optString("ocr")}")
    }
    else -> o.toString()
}

private fun strings(a: org.json.JSONArray): List<String> = List(a.length()) { a.optString(it) }

private fun kindColour(kind: String): Color = when (kind) {
    "advice" -> Color(0xFF7BD88A)
    "edit" -> Color(0xFFE0C860)
    "miss" -> Color(0xFFE08080)
    "run_end" -> Color(0xFFB48CFF)
    else -> GREY
}
