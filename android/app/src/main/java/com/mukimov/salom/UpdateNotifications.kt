package com.mukimov.salom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

internal object UpdateNotifications {
    private const val CHANNEL = "salom_updates_v1"
    const val OPEN_UPDATES = "salom-open-updates"

    fun progress(context: Context, version: String, percent: Int) =
        show(context, 1, "Обновление Салома $version", "Загружено $percent%", percent)

    fun ready(context: Context, version: String) =
        show(context, 1, "Обновление Салома $version", "Скачано и проверено. Нажми, чтобы установить.")

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel("salom-updates", 1)

    fun failed(context: Context, version: String) =
        show(context, 1, "Обновление Салома $version", "Загрузка прервана. Нажми, чтобы продолжить.")

    fun installed(context: Context, version: String) {
        clear(context)
        show(context, 2, "Салом обновлён", "Установлена версия $version")
    }

    fun showActionError(context: Context, peer: String) =
        show(context, 3, "Действие не выполнено", "Открой чат, чтобы попробовать снова.", peer = peer)

    private fun show(context: Context, id: Int, title: String, text: String, percent: Int? = null, peer: String = OPEN_UPDATES) {
        if (!PushNotifications.allowed(context)) return
        try {
            if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Обновления Салома", NotificationManager.IMPORTANCE_LOW))
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("peer_id", peer)
            val open = PendingIntent.getActivity(context, 7810 + id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setOnlyAlertOnce(true)
                .setContentTitle(title).setContentText(text).setContentIntent(open)
                .setOngoing(percent != null).setAutoCancel(percent == null)
            if (percent != null) notification.setProgress(100, percent, false)
            NotificationManagerCompat.from(context).notify("salom-updates", id, notification.build())
        } catch (_: SecurityException) { }
    }
}

class UpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdateNotifications.installed(context, context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty())
        }
    }
}
