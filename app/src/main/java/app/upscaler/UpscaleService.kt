package app.upscaler

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Foreground service: keeps upload / monitoring / download alive while the app is minimized or the screen is off. */
class UpscaleService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    companion object {
        const val START = "start"; const val CANCEL = "cancel"
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, UpscaleService::class.java).setAction(START))
        fun cancel(ctx: Context) = ctx.startService(Intent(ctx, UpscaleService::class.java).setAction(CANCEL))
    }
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { job?.cancel(); return START_NOT_STICKY }
        val v = JobStore.pendingVideo; val s = JobStore.pendingSettings
        if (v == null || s == null || job?.isActive == true) { if (job?.isActive != true) stopSelf(); return START_NOT_STICKY }
        Notif.init(this)
        ServiceCompat.startForeground(this, Notif.ID_PROGRESS, Notif.progress(this, "AI Upscaling", "Starting…", null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "upscaler:job")
            .apply { acquire(6 * 3600 * 1000L) }
        val runner = JobRunner(applicationContext, BackendRepo(applicationContext))
        val ticker = scope.launch {
            var lastPct = -2
            JobStore.ui.collectLatest { ui ->
                val (text, p) = when (val ph = ui.phase) {
                    is Phase.Uploading -> "Uploading video" to ph.progress
                    is Phase.Waiting -> ph.message to null
                    is Phase.Processing -> "${ui.scale}× AI upscale" to ph.progress
                    is Phase.Downloading -> "Downloading result" to ph.progress
                    else -> return@collectLatest
                }
                val pct = p?.let { (it * 100).toInt() } ?: -1
                if (pct != lastPct) {
                    lastPct = pct
                    if (androidx.core.content.ContextCompat.checkSelfPermission(this@UpscaleService, android.Manifest.permission.POST_NOTIFICATIONS) == 0 ||
                        android.os.Build.VERSION.SDK_INT < 33)
                        NotificationManagerCompat.from(this@UpscaleService).notify(Notif.ID_PROGRESS,
                            Notif.progress(this@UpscaleService, ui.fileName, text + (if (pct >= 0) " · $pct%" else ""), p))
                }
            }
        }
        job = scope.launch {
            try { runner.run(v, s) } finally {
                ticker.cancel()
                when (val ph = JobStore.ui.value.phase) {
                    is Phase.Done -> if (!Notif.appVisible()) Notif.done(applicationContext, ph.uri)
                    is Phase.Failed -> if (!Notif.appVisible()) Notif.failed(applicationContext, ph.message)
                    else -> {}
                }
                wake?.takeIf { it.isHeld }?.release()
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); wake?.takeIf { it.isHeld }?.release(); super.onDestroy() }
}
