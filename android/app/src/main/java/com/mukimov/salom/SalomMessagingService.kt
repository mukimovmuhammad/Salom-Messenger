package com.mukimov.salom

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal object PushNotifications {
    const val CHANNEL = "salom_messages_ding_v1"
    const val SILENT_CHANNEL = "salom_messages_silent_v1"
    const val CALL_CHANNEL = "salom_incoming_calls_v1"
    private const val LEGACY_CHANNEL = "salom_messages"
    fun soundUri(context: Context): android.net.Uri = android.net.Uri.parse(
        "android.resource://${context.packageName}/raw/${context.resources.getResourceEntryName(R.raw.salom_ding)}")
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val previous = manager.getNotificationChannel(LEGACY_CHANNEL)
            val sound = when {
                previous != null && previous.sound == null -> null
                Build.VERSION.SDK_INT >= 30 && previous?.hasUserSetSound() == true -> previous.sound
                else -> soundUri(context)
            }
            if (manager.getNotificationChannel(CHANNEL) == null) manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Сообщения Салом", previous?.importance ?: NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Новые сообщения · звук Салом"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                    setSound(sound, android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    previous?.let { vibrationPattern = it.vibrationPattern; enableVibration(it.shouldVibrate()) }
                }
            )
            if (manager.getNotificationChannel(SILENT_CHANNEL) == null) manager.createNotificationChannel(
                NotificationChannel(SILENT_CHANNEL, "Тихие сообщения в приложении", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Сообщения, когда Салом открыто в другом разделе"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                    setSound(null, null)
                    enableVibration(false)
                }
            )
            if (manager.getNotificationChannel(CALL_CHANNEL) == null) manager.createNotificationChannel(
                NotificationChannel(CALL_CHANNEL, "Входящие звонки", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Звонки Салом"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                        android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 250, 400)
                }
            )
        }
    }
    fun allowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun cancelChat(context: Context, peerId: String) {
        NotificationManagerCompat.from(context).cancel("salom-peer-$peerId", 0)
    }

    fun cancelCall(context: Context, callId: String) {
        NotificationManagerCompat.from(context).cancel("salom-call-$callId", 0)
    }

    fun sync(context: Context) {
        createChannel(context)
        WorkManager.getInstance(context).enqueueUniqueWork("salom-push-registration", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}

class PushRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val local = applicationContext.getSharedPreferences("salom_local", Context.MODE_PRIVATE)
        if (local.getString("signed_in", "") != "yes") return Result.success()
        // Keep a disabled registration after sign-out so old pushes stop on this installation.
        if (local.getString("name", "").isNullOrBlank()) return Result.success()
        val token = suspendCancellableCoroutine<String?> { continuation ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (continuation.isActive) continuation.resume(if (task.isSuccessful) task.result else null)
            }
        } ?: return Result.retry()
        return try {
            DirectoryClient(applicationContext).registerPush(token,
                local.getString("signed_in", "") == "yes" && PushNotifications.allowed(applicationContext))
            Result.success()
        } catch (exception: DirectoryException) { Result.retry() }
    }
}

class SalomMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { PushNotifications.sync(this) }

    override fun onMessageReceived(message: RemoteMessage) {
        val local = getSharedPreferences("salom_local", MODE_PRIVATE)
        if (local.getString("signed_in", "") != "yes") return
        if (!PushNotifications.allowed(this)) return
        if (message.data["type"] == "incoming_call") {
            val localCaller = message.data["caller_id"]
            if (!(local.getBoolean("app_visible", false) && local.getString("active_peer_id", null) == localCaller)) showIncomingCall(message.data)
            return
        }
        if (message.data["type"] == "missed_call") { showMissedCall(message.data); return }
        val peerId = message.data["peer_id"] ?: return
        val messageId = message.data["message_id"] ?: return
        val manager = NotificationManagerCompat.from(this)
        val appVisible = local.getBoolean("app_visible", false)
        val activePeer = local.getString("active_peer_id", null)
        if (appVisible && activePeer == peerId) {
            manager.cancel("salom-peer-$peerId", 0)
            return
        }
        PushNotifications.createChannel(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("peer_id", peerId)
            data = android.net.Uri.parse("salom://chat/$peerId")
        }
        val pending = PendingIntent.getActivity(this, peerId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = message.notification?.title ?: "Салом"
        val body = message.notification?.body ?: "Новое сообщение"
        val silent = appVisible
        val notification = NotificationCompat.Builder(this, if (silent) PushNotifications.SILENT_CHANNEL else PushNotifications.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setColor(0xFFFFC400.toInt())
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (Build.VERSION.SDK_INT < 26) notification.setSound(if (silent) null else PushNotifications.soundUri(this)).setPriority(if (silent) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
        try { manager.notify("salom-peer-$peerId", 0, notification.build()) }
        catch (_: SecurityException) { /* Permission can be revoked between checking and posting. */ }
    }

    private fun showIncomingCall(data: Map<String, String>) {
        val callId = data["call_id"]?.takeIf { it.length <= 36 } ?: return
        val callerId = data["caller_id"]?.takeIf { it.length <= 36 } ?: return
        PushNotifications.createChannel(this)
        val manager = NotificationManagerCompat.from(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("incoming_call_id", callId)
            putExtra("incoming_call_peer", callerId)
            putExtra("incoming_call_name", data["caller_name"]?.take(80) ?: "Салом")
            this.data = android.net.Uri.parse("salom://call/$callId")
        }
        fun pending(action: String, requestCode: Int) = PendingIntent.getActivity(this, requestCode,
            Intent(intent).putExtra("incoming_call_action", action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val accept = pending("accept", callId.hashCode())
        val decline = pending("decline", callId.hashCode() xor 0x71)
        val fullScreen = PendingIntent.getActivity(this, callId.hashCode() xor 0x19, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, PushNotifications.CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setColor(0xFFFFC400.toInt())
            .setContentTitle(data["caller_name"]?.take(80) ?: "Входящий звонок")
            .setContentText("Голосовой звонок · Салом")
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOngoing(true).setAutoCancel(false)
            .setTimeoutAfter(45_000).setFullScreenIntent(fullScreen, true).setContentIntent(accept)
            .addAction(0, "Отклонить", decline).addAction(0, "Ответить", accept)
            .build()
        try { manager.notify("salom-call-$callId", 0, notification) }
        catch (_: SecurityException) { /* Notification permission can change while FCM is being handled. */ }
    }

    private fun showMissedCall(data: Map<String, String>) {
        val callId = data["call_id"]?.takeIf { it.length <= 36 } ?: return
        val peerId = data["caller_id"]?.takeIf { it.length <= 36 } ?: return
        PushNotifications.createChannel(this)
        val count = data["unread_count"]?.toIntOrNull()?.coerceIn(1, 999) ?: 1
        val title = if (count == 1) "Пропущенный звонок" else "Пропущенные звонки ($count)"
        val body = data["caller_name"]?.take(80) ?: "Открой чат, чтобы перезвонить"
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("peer_id", peerId)
            this.data = android.net.Uri.parse("salom://chat/$peerId?missed=$callId")
        }
        val pending = PendingIntent.getActivity(this, callId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, PushNotifications.SILENT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setColor(0xFFFFC400.toInt())
            .setContentTitle(title).setContentText(body).setContentIntent(pending)
            .setCategory("missed_call").setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { NotificationManagerCompat.from(this).notify("salom-missed-call", 0, notification) }
        catch (_: SecurityException) { /* Notification permission can change while FCM is being handled. */ }
    }
}
