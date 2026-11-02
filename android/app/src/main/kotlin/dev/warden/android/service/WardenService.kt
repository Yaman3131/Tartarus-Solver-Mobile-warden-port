package dev.warden.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import dev.warden.android.Core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds the screen capture. Android requires a foreground service for
 * MediaProjection, started before the projection is taken (Android 14+).
 */
class WardenService : Service() {
    private var engine: CaptureEngine? = null
    private var projection: MediaProjection? = null
    private var overlay: MarkerOverlay? = null
    private var balloon: Balloon? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val extras = intent?.extras?.keySet()?.joinToString()
        Log.i(TAG, "onStartCommand action=${intent?.action} extras=$extras")
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // The result Intent is handed over in-process: parceling it through
        // the service start has proved unreliable on this device.
        val result = takePending()
        if (result == null) {
            Log.w(TAG, "no projection result waiting")
            stopSelf()
            return START_NOT_STICKY
        }
        val (code, data) = result

        startForegroundNow()

        val mgr = getSystemService(MediaProjectionManager::class.java)
        projection = runCatching { mgr.getMediaProjection(code, data) }.getOrNull()
        if (projection == null) {
            Log.e(TAG, "no projection")
            stopSelf()
            return START_NOT_STICKY
        }
        engine = CaptureEngine(this, projection!!).also { it.start() }
        running = true
        showMarks()
        return START_STICKY
    }

    private fun startForegroundNow() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL, "Tartarus Solver", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
            val n: Notification = Notification.Builder(this, CHANNEL)
                .setContentTitle("Tartarus Solver is reading the game")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            Log.i(TAG, "foreground started")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground: ${e.message}", e)
            throw e
        }
    }

    /**
     * Follows the session's advice and draws its mark over the game: the
     * snapshot's rectangle is in captured-frame pixels, and the frame is the
     * screen at its own size, so it needs no scaling but the reference unit.
     */
    private fun showMarks() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no overlay permission: no mark")
            return
        }
        val marks = MarkerOverlay(this)
        overlay = marks
        val bubble = Balloon(
            this,
            onTap = { Core.readNow() },
            onToggleAuto = {
                Core.autoRead = !Core.autoRead
                if (Core.autoRead) Core.readNow()
            },
        )
        balloon = bubble
        bubble.show()
        val (w, h) = displaySize()
        val unit = if (w * 9 < h * 16) w / 1918f else w / 1920f
        scope.launch {
            Core.snapshot.collect { s ->
                val m = s?.mark
                marks.show(
                    m?.let {
                        MarkerOverlay.Rect(it.x, it.y, it.w, it.h, unit)
                    },
                )
                val label = when {
                    s == null -> "Tartarus Solver…"
                    !Core.autoRead -> "Auto off · tap to read"
                    s.advice != null -> s.advice.label
                    s.solving -> "Solving…"
                    s.problems.firstOrNull() != null -> s.problems.first()
                    else -> "Tartarus Solver"
                }
                bubble.setLabel(label)
            }
        }
    }

    private fun displaySize(): Pair<Int, Int> {
        val b = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        return b.width() to b.height()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        overlay?.release()
        overlay = null
        balloon?.release()
        balloon = null
        engine?.stop()
        engine = null
        projection = null
        super.onDestroy()
    }

    companion object {
        /** True while a capture runs: the activity's button follows it. */
        @Volatile
        var running = false
            private set

        private const val TAG = "warden"
        private const val CHANNEL = "warden_capture"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.warden.android.STOP"

        @Volatile
        private var pending: Pair<Int, Intent>? = null

        /** The permission dialog's result, once. */
        @Synchronized
        private fun takePending(): Pair<Int, Intent>? = pending.also { pending = null }

        /** Starts the service with the permission dialog's result. */
        @Synchronized
        fun start(context: Context, resultCode: Int, data: Intent) {
            pending = resultCode to data
            context.startForegroundService(Intent(context, WardenService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WardenService::class.java).setAction(ACTION_STOP))
        }
    }
}
