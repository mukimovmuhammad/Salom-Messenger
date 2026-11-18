package com.mukimov.salom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Keeps the user's explicitly started microphone call alive while SALOM is backgrounded. */
internal class CallForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Текущий звонок", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Управление активным звонком Салом"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val callId = intent?.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val peerId = intent?.getStringExtra(EXTRA_PEER_ID).orEmpty()
        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME).orEmpty().ifBlank { "Собеседник" }
        val endIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("incoming_call_id", callId)
            putExtra("incoming_call_peer", peerId)
            putExtra("incoming_call_name", peerName)
            putExtra("incoming_call_action", "end")
            this.data = android.net.Uri.parse("salom://call/$callId/end")
        }
        val pending = PendingIntent.getActivity(this, callId.hashCode(), endIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setColor(0xFFFFC400.toInt())
            .setContentTitle("Звонок Салом").setContentText(peerName)
            .setCategory(NotificationCompat.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(pending).addAction(0, "Завершить", pending).build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "salom_ongoing_call_v1"
        private const val NOTIFICATION_ID = 4208
        private const val ACTION_STOP = "com.mukimov.salom.STOP_CALL_SERVICE"
        private const val EXTRA_CALL_ID = "call_id"
        private const val EXTRA_PEER_ID = "peer_id"
        private const val EXTRA_PEER_NAME = "peer_name"

        fun start(context: Context, callId: String, peerId: String, peerName: String) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                putExtra(EXTRA_CALL_ID, callId); putExtra(EXTRA_PEER_ID, peerId); putExtra(EXTRA_PEER_NAME, peerName)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
