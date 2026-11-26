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
import android.os.Bundle
import android.os.ResultReceiver
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
        @Suppress("DEPRECATION")
        val result = intent?.getParcelableExtra<ResultReceiver>("promotion_result")
        if (callId.isEmpty() || CallSessionManager.current(callId) == null) {
            result?.send(0, null)
            stopSelf(startId)
            return START_NOT_STICKY
        }
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
        val openIntent = Intent(endIntent).apply {
            removeExtra("incoming_call_action")
            data = android.net.Uri.parse("salom://call/$callId/open")
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, callId.hashCode(), openIntent, flags)
        val end = PendingIntent.getActivity(this, callId.hashCode(), endIntent, flags)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setColor(0xFFFFC400.toInt())
            .setContentTitle("Звонок Салом").setContentText(peerName)
            .setCategory(NotificationCompat.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).addAction(0, "Завершить", end).build()
        val cameraAllowed = intent?.getBooleanExtra("with_camera", false) == true &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or if (cameraAllowed) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0 else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            result?.send(1, null)
        } catch (failure: RuntimeException) {
            android.util.Log.e("SalomCall", "Cannot start call foreground service", failure)
            result?.send(0, null)
            stopSelf(startId)
        }
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

        suspend fun start(context: Context, callId: String, peerId: String, peerName: String, video: Boolean) =
            kotlinx.coroutines.withTimeout(5_000) {
                val promoted = kotlinx.coroutines.CompletableDeferred<Boolean>()
                val result = object : ResultReceiver(android.os.Handler(android.os.Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        promoted.complete(resultCode == 1)
                    }
                }
                val intent = Intent(context, CallForegroundService::class.java).apply {
                    putExtra(EXTRA_CALL_ID, callId)
                    putExtra(EXTRA_PEER_ID, peerId)
                    putExtra(EXTRA_PEER_NAME, peerName)
                    putExtra("with_camera", video)
                    putExtra("promotion_result", result)
                }
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
                check(promoted.await()) { "Android не разрешил запустить микрофон. Открой Салом и попробуй снова." }
            }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
