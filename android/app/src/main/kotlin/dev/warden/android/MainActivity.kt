package dev.warden.android

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.warden.android.data.BoardState
import dev.warden.android.service.WardenService
import dev.warden.android.ui.AdvicePanel
import dev.warden.android.ui.BoardEditor
import dev.warden.android.ui.LiveBoard
import dev.warden.android.ui.RunLogPanel

/**
 * The app: the board the reader installed (with its corrections) or the hand
 * board editor, next to the advice or the run log, and a button that starts
 * reading the game itself (MediaProjection + ML Kit).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.start(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WardenApp()
            }
        }
    }
}

@Composable
fun WardenApp() {
    val context = LocalContext.current
    val snapshot by Core.snapshot.collectAsState()
    val error by Core.error.collectAsState()

    var board by remember { mutableStateOf(BoardState(theme = Core.theme, tools = Core.tools)) }
    var rollouts by remember { mutableIntStateOf(Core.rollouts) }
    // The service owns the capture; the button follows it across recreation.
    var reading by remember { mutableStateOf(WardenService.running) }
    // Left: the board read from the game, or the hand board. Right: the
    // advice, or the run log. With the capture off, the editor comes first.
    var live by remember { mutableStateOf(WardenService.running) }
    var showLog by remember { mutableStateOf(false) }

    // The editor's board, installed as the reading on show while no frame
    // read is in; a frame read takes over from it.
    LaunchedEffect(board) {
        // The solver's instruments and theme live on the session, not in the
        // board model; keep them in step with the editor.
        if (board.tools != Core.tools) Core.setTools(board.tools)
        if (board.theme != Core.theme) Core.setTheme(board.theme)
        Core.setBoard(board)
    }

    val projection = remember { context.getSystemService(MediaProjectionManager::class.java) }
    val startCapture = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        android.util.Log.i("wardenui", "projection result=${result.resultCode} data=${data != null}")
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            WardenService.start(context, result.resultCode, data)
            reading = true
        }
    }
    val overlay = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }
    val notify = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Tartarus Solver", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        if (reading) {
                            WardenService.stop(context)
                            reading = false
                        } else if (!android.provider.Settings.canDrawOverlays(context)) {
                            // The mark over the game needs this first.
                            overlay.launch(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        } else {
                            startCapture.launch(projection.createScreenCaptureIntent())
                        }
                    }) {
                        Text(if (reading) "Stop reading" else "Read the game")
                    }
                    // The reader without the game: a PNG pushed under the app's
                    // files (adb push + run-as), for calibration (P5).
                    TextButton(onClick = { Core.readPng("game-01.png") }) {
                        Text("Read a picture")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = live,
                        onClick = { live = true },
                        label = { Text("Live board") },
                    )
                    FilterChip(
                        selected = !live,
                        onClick = { live = false },
                        label = { Text("Hand board") },
                    )
                }

                val read = snapshot?.reading
                if (live) {
                    val model = read?.model
                    if (model != null) {
                        LiveBoard(
                            model = model,
                            warnings = read.warnings,
                            onEdit = { cell, kind, beast, tier -> Core.editCell(cell, kind, beast, tier) },
                            onMarker = { cell, on -> Core.setMarker(cell, on) },
                        )
                    } else {
                        Text(
                            "Read the game (or a picture) to see its board here. " +
                                "The hand board is on the other tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8A8A96),
                        )
                    }
                } else {
                    BoardEditor(
                        board = board,
                        rollouts = rollouts,
                        onRollouts = {
                            rollouts = it
                            Core.setRollouts(it)
                        },
                        onChange = { board = it },
                    )
                }
            }

            Column(Modifier.weight(0.8f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !showLog,
                        onClick = { showLog = false },
                        label = { Text("Advice") },
                    )
                    FilterChip(
                        selected = showLog,
                        onClick = { showLog = true },
                        label = { Text("Run log") },
                    )
                }
                if (showLog) {
                    RunLogPanel()
                } else {
                    Text("Advice", style = MaterialTheme.typography.titleMedium)
                    AdvicePanel(snapshot, error)
                }
            }
        }
    }
}
