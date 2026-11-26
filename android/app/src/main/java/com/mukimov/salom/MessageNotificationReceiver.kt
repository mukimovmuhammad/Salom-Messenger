package com.mukimov.salom

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.work.*
import java.util.UUID

internal fun notificationUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

class MessageNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val owner = intent.getStringExtra("owner")?.takeIf(::notificationUuid) ?: return
        val peer = intent.getStringExtra("peer")?.takeIf(::notificationUuid) ?: return
        if (!matchesOwner(context, owner)) return
        if (intent.action == "mute") {
            val prefs = context.getSharedPreferences("notification_mutes", Context.MODE_PRIVATE)
            prefs.edit().putLong("$owner/$peer", if (muted(context, owner, peer)) 0 else System.currentTimeMillis() + 3_600_000).apply()
            PushNotifications.cancelChat(context, peer)
            return
        }
        val action = intent.action?.takeIf { it == "reply" || it == "read" } ?: return
        val message = intent.getLongExtra("message", 0).takeIf { it > 0 } ?: return
        val request = intent.getStringExtra("request")?.takeIf(::notificationUuid) ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply")?.toString()?.trim().orEmpty()
        if (action == "reply" && (reply.length !in 1..4000 || reply.toByteArray().size > 8000)) return
        val work = OneTimeWorkRequestBuilder<MessageNotificationWorker>()
            .setInputData(workDataOf("action" to action, "owner" to owner, "peer" to peer, "message" to message, "request" to request, "reply" to reply))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("notification-$request", ExistingWorkPolicy.KEEP, work)
        PushNotifications.cancelChat(context, peer)
    }

    internal companion object {
        fun matchesOwner(context: Context, owner: String): Boolean =
            context.getSharedPreferences("salom_local", Context.MODE_PRIVATE).run {
                getString("signed_in", "") == "yes" && getString("owner_profile_id", "") == owner
            }

        fun muted(context: Context, owner: String, peer: String): Boolean =
            context.getSharedPreferences("notification_mutes", Context.MODE_PRIVATE)
                .getLong("$owner/$peer", 0) > System.currentTimeMillis()

        fun addActions(context: Context, notification: NotificationCompat.Builder, owner: String, peer: String, message: Long, muted: Boolean) {
            fun pending(action: String, mutable: Boolean): PendingIntent {
                val request = UUID.randomUUID().toString()
                return PendingIntent.getBroadcast(context, 0, Intent(context, MessageNotificationReceiver::class.java).apply {
                    this.action = action
                    data = Uri.parse("salom://notification/$request")
                    putExtra("owner", owner); putExtra("peer", peer); putExtra("message", message); putExtra("request", request)
                }, PendingIntent.FLAG_ONE_SHOT or when {
                    !mutable -> PendingIntent.FLAG_IMMUTABLE
                    Build.VERSION.SDK_INT >= 31 -> PendingIntent.FLAG_MUTABLE
                    else -> 0
                })
            }
            notification.addAction(NotificationCompat.Action.Builder(0, "Ответить", pending("reply", true))
                .addRemoteInput(RemoteInput.Builder("reply").setLabel("Написать ответ").build())
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false).setAuthenticationRequired(true).build())
            notification.addAction(NotificationCompat.Action.Builder(0, "Прочитано", pending("read", false))
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false).setAuthenticationRequired(true).build())
            notification.addAction(0, if (muted) "Включить звук" else "Без звука", pending("mute", false))
        }
    }
}

class MessageNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString("owner") ?: return Result.failure()
        if (!MessageNotificationReceiver.matchesOwner(applicationContext, owner)) return Result.success()
        val peer = inputData.getString("peer") ?: return Result.failure()
        val client = DirectoryClient(applicationContext)
        if (client.ownId != owner) return Result.success()
        return try {
            if (inputData.getString("action") == "reply") {
                client.sendMessage(peer, inputData.getString("reply").orEmpty(),
                    inputData.getString("request").orEmpty(), replyToId = inputData.getLong("message", 0))
            }
            client.messages(peer, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
            Result.success()
        } catch (failure: DirectoryException) {
            if (runAttemptCount < 4 && (failure.status == 0 || failure.status == 429 || failure.status >= 500)) return Result.retry()
            UpdateNotifications.showActionError(applicationContext, peer)
            Result.failure()
        }
    }
}
