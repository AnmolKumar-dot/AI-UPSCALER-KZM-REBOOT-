package app.upscaler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

object Notif {
    private const val CH_PROGRESS = "progress"
    private const val CH_RESULT = "result"
    const val ID_PROGRESS = 1
    private const val ID_RESULT = 2

    fun init(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Upscaling progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "Upscaling results", NotificationManager.IMPORTANCE_HIGH))
    }
    fun appVisible() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun progress(ctx: Context, title: String, text: String, p: Float?): Notification =
        NotificationCompat.Builder(ctx, CH_PROGRESS).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(text).setOnlyAlertOnce(true).setOngoing(true)
            .setContentIntent(openApp(ctx))
            .setProgress(100, ((p ?: 0f) * 100).toInt(), p == null).build()

    fun done(ctx: Context, uri: Uri) = post(ctx, NotificationCompat.Builder(ctx, CH_RESULT)
        .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("AI Upscaling Complete")
        .setContentText("Your video has been upscaled. Tap to watch it.").setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(ctx, 1, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PendingIntent.FLAG_IMMUTABLE)).build())

    fun failed(ctx: Context, msg: String) = post(ctx, NotificationCompat.Builder(ctx, CH_RESULT)
        .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("Upscaling failed")
        .setContentText(msg).setAutoCancel(true).setContentIntent(openApp(ctx)).build())

    private fun post(ctx: Context, n: Notification) {
        val nm = NotificationManagerCompat.from(ctx)
        if (nm.areNotificationsEnabled()) @Suppress("MissingPermission") nm.notify(ID_RESULT, n)
    }
}
