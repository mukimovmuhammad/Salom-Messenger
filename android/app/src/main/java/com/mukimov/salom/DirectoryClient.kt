package com.mukimov.salom

import kotlinx.coroutines.ensureActive

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class DirectoryException(message: String, val status: Int = 0) : Exception(message)
internal data class ConversationPreview(val account: SalomAccount, val lastText: String, val unreadCount: Int = 0)
internal data class MessageEvent(val id: Long, val messageId: Long, val kind: String, val message: Message? = null)
internal data class MessageBatch(val messages: List<Message>, val receipts: Map<Long, String>, val events: List<MessageEvent>, val hasMore: Boolean, val clearedThrough: Long)
internal data class RealtimeEvent(val type: String, val connected: Boolean = false, val online: Boolean? = null, val typing: Boolean? = null, val callId: String? = null, val peerId: String? = null, val callerName: String? = null, val state: String? = null, val signalKind: String? = null, val signalPayload: JSONObject? = null, val video: Boolean = false, val message: Message? = null)

/** Private installation/session credential for the Salom API. Google sign-in uses AuthClient. */
internal class DirectoryClient(context: Context) {
    companion object {
        private val callCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    private val identity = context.getSharedPreferences("salom_directory", Context.MODE_PRIVATE)
    private val local = context.getSharedPreferences("salom_local", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var syncedSignature: Pair<String, String>? = null
    val ownId: String = identity.getString("device_id", null) ?: UUID.randomUUID().toString().also { identity.edit().putString("device_id", it).commit() }
    private val token: String = SecureTokenStore(context).readOrCreate()
    private val realtimeClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(25, TimeUnit.SECONDS).build()
    @Volatile private var activeRealtime: WebSocket? = null
    init {
        val snapshot = identity.getString("auth_snapshot", null)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()
        }
        snapshot?.let {
            if (it.has("session_token")) {
                it.remove("session_token")
                check(identity.edit().putString("auth_snapshot", it.toString()).commit()) {
                    "Не удалось безопасно обновить данные входа"
                }
            }
        }
        // Recover the profile projection if the process stopped between the two preference writes.
        if (local.getString("owner_profile_id", "") != ownId) {
            snapshot?.let {
                runCatching {
                    val profile = it.getJSONObject("profile")
                    if (profile.getString("id") == ownId) local.edit().remove("about")
                        .putString("name", profile.getString("name")).putString("username", profile.getString("username"))
                        .remove("phone").putString("email", it.optString("email"))
                        .putString("owner_profile_id", ownId).putString("account_verified", "yes").putString("signed_in", "yes").commit()
                }
            }
        }
    }

    internal fun request(path: String, body: JSONObject? = null, method: String = if (body != null) "PUT" else "GET"): JSONObject {
        val connection = URL("https://mukimov.site/salom/api/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 7000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            val model = android.os.Build.MODEL.filter { it in ' '..'~' && it != '/' }.trim().take(55)
            connection.setRequestProperty("X-Salom-Device", if (model.isEmpty()) "Android" else "Android / $model")
            connection.requestMethod = method
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val data = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(data.size)
                connection.outputStream.use { it.write(data) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val result = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code !in 200..299) {
                val detail = result.opt("detail")
                throw DirectoryException(if (detail is String && detail.isNotBlank()) detail else "Сервер временно недоступен ($code)", code)
            }
            return result
        } catch (exception: DirectoryException) { throw exception }
        catch (exception: Exception) { throw DirectoryException("Не удалось связаться с сервером. Проверь интернет и попробуй снова") }
        finally { connection.disconnect() }
    }

    suspend fun publish(name: String, username: String, about: String? = null): SalomAccount = withContext(Dispatchers.IO) {
        mutex.withLock {
            val details = JSONObject().put("device_id", ownId).put("name", name.trim()).put("username", username.trim())
            if (about != null) details.put("about", about)
            val result = try {
                request("profile", details)
            } catch (failure: DirectoryException) {
                if (about == null || failure.status != 422) throw failure
                details.remove("about")
                request("profile", details)
            }
            syncedSignature = name.trim() to username.trim()
            account(result)
        }
    }

    suspend fun uploadAvatar(jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        syncLocal()
        val connection = URL("https://mukimov.site/salom/api/profiles/me/avatar").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 7000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "image/jpeg")
            connection.setFixedLengthStreamingMode(jpeg.size)
            connection.outputStream.use { it.write(jpeg) }
            if (connection.responseCode != 200) throw DirectoryException("Не удалось загрузить фото профиля", connection.responseCode)
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()).getString("avatar_url") }
        } finally { connection.disconnect() }
    }

    suspend fun syncLocal() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val name = local.getString("name", "").orEmpty().trim()
            val username = local.getString("username", "").orEmpty().trim()
            if (name.isNotEmpty() && syncedSignature != (name to username)) {
                request("profile", JSONObject().put("device_id", ownId).put("name", name).put("username", username))
                syncedSignature = name to username
            }
        }
    }

    suspend fun search(query: String): List<SalomAccount> = withContext(Dispatchers.IO) {
        syncLocal()
        val prefix = query.trim().removePrefix("@")
        if (!prefix.matches(Regex("[A-Za-z0-9_]{1,24}"))) return@withContext emptyList()
        val array = request("search?q=" + URLEncoder.encode(prefix, "UTF-8")).getJSONArray("profiles")
        (0 until array.length()).map { account(array.getJSONObject(it)) }
    }

    private fun account(json: JSONObject) = SalomAccount(json.getString("id"), json.getString("name"),
        json.optString("username"), "", json.optBoolean("is_active"), json.optString("avatar_url"),
        about = json.opt("about") as? String, joinedAt = json.optLong("joined_at").takeIf { it > 0 })

    suspend fun sendMessage(peerId: String, text: String, clientId: String, attachment: Attachment? = null, replyToId: Long? = null): Message = withContext(Dispatchers.IO) {
        syncLocal()
        message(request("messages", JSONObject().put("recipient_id", peerId).put("text", text).put("client_id", clientId).apply { attachment?.let {
            if (it.extraPhotos.isEmpty()) put("attachment_id", it.id)
            else put("album_ids", org.json.JSONArray(it.photos.map { photo -> photo.id }))
        }; replyToId?.let { put("reply_to_id", it) } }, "POST"))
    }

    suspend fun heartbeat() = withContext(Dispatchers.IO) {
        syncLocal(); request("presence", JSONObject(), "POST"); Unit
    }

    suspend fun peerOnline(peerId: String): Boolean = withContext(Dispatchers.IO) {
        syncLocal(); request("profiles/" + URLEncoder.encode(peerId, "UTF-8")).optBoolean("is_online")
    }

    suspend fun setTyping(peerId: String, typing: Boolean) = withContext(Dispatchers.IO) {
        syncLocal(); request("typing", JSONObject().put("peer_id", peerId).put("typing", typing), "POST"); Unit
    }

    suspend fun peerTyping(peerId: String): Boolean = withContext(Dispatchers.IO) {
        syncLocal(); request("typing?peer_id=" + URLEncoder.encode(peerId, "UTF-8")).optBoolean("typing")
    }

    suspend fun createCall(peerId: String, video: Boolean): JSONObject = withContext(Dispatchers.IO) {
        syncLocal()
        val body = JSONObject().put("peer_id", peerId)
        if (video) body.put("mode", "video")
        request("calls", body, "POST")
    }

    suspend fun updateCall(callId: String, action: String): JSONObject = withContext(Dispatchers.IO) {
        syncLocal(); request("calls/$callId/state", JSONObject().put("action", action), "POST")
    }

    fun finishCall(callId: String) {
        callCleanupScope.launch {
            runCatching { updateCall(callId, "end") }
        }
    }

    suspend fun signalCall(callId: String, kind: String, payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        syncLocal(); request("calls/$callId/signal", JSONObject().put("kind", kind).put("payload", payload), "POST")
    }

    suspend fun missedCalls(): Int = withContext(Dispatchers.IO) {
        syncLocal(); request("calls/missed").optInt("unread_count")
    }

    suspend fun callConfig(): org.json.JSONArray = withContext(Dispatchers.IO) {
        syncLocal(); request("calls/config").getJSONArray("ice_servers")
    }

    fun realtime(peerId: String): Flow<RealtimeEvent> = callbackFlow {
        val url = "wss://mukimov.site/salom/api/ws"
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        val socket = realtimeClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                activeRealtime = webSocket
                webSocket.send(JSONObject().put("type", "subscribe").put("peer_id", peerId).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val data = runCatching { JSONObject(text) }.getOrNull() ?: return
                val type = data.optString("type")
                if (type in setOf("message", "typing", "presence", "sync", "ready", "subscribed", "call_invite", "call_state", "call_signal")) trySend(RealtimeEvent(
                    type, connected = type == "subscribed", online = data.optBoolean("online").takeIf { data.has("online") },
                    typing = data.optBoolean("typing").takeIf { data.has("typing") }, callId = data.optString("call_id").takeIf { it.isNotBlank() },
                    peerId = data.optString("peer_id").takeIf { it.isNotBlank() }, callerName = data.optString("caller_name").takeIf { it.isNotBlank() },
                    state = data.optString("state").takeIf { it.isNotBlank() }, signalKind = data.optString("kind").takeIf { it.isNotBlank() },
                    signalPayload = data.optJSONObject("payload"), video = data.optString("mode") == "video",
                    message = if (type == "message") data.optJSONObject("message")?.let { runCatching { message(it) }.getOrNull() } else null))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (activeRealtime === webSocket) activeRealtime = null; trySend(RealtimeEvent("closed", connected = false)); close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (activeRealtime === webSocket) activeRealtime = null; trySend(RealtimeEvent("disconnected", connected = false)); close() }
        })
        awaitClose { if (activeRealtime === socket) activeRealtime = null; socket.close(1000, "Chat closed") }
    }

    fun sendRealtimeTyping(peerId: String, typing: Boolean) {
        activeRealtime?.send(JSONObject().put("type", "typing").put("peer_id", peerId).put("typing", typing).toString())
    }

    suspend fun messages(peerId: String, after: Long, receiptsAfter: Long = 0, eventsAfter: Long = 0): MessageBatch = withContext(Dispatchers.IO) {
        syncLocal()
        val result = request("messages?peer_id=" + URLEncoder.encode(peerId, "UTF-8") + "&after=$after&receipts_after=$receiptsAfter&events_after=$eventsAfter")
        val array = result.getJSONArray("messages")
        val receiptArray = result.optJSONArray("receipts")
        val receipts = if (receiptArray == null) emptyMap() else (0 until receiptArray.length()).associate { index -> receiptArray.getJSONObject(index).getLong("id") to receiptArray.getJSONObject(index).optString("receipt", "sent") }
        val eventsJson = result.optJSONArray("events")
        val events = if (eventsJson == null) emptyList() else (0 until eventsJson.length()).map { i -> eventsJson.getJSONObject(i).let { MessageEvent(it.getLong("id"),it.getLong("message_id"),it.getString("kind"),it.optJSONObject("message")?.let(::message)) } }
        MessageBatch((0 until array.length()).map { message(array.getJSONObject(it)) }, receipts, events, result.optBoolean("has_more") || events.size >= 300, result.optLong("cleared_through"))
    }

    suspend fun conversations(): List<ConversationPreview> = withContext(Dispatchers.IO) {
        syncLocal()
        val array = request("conversations").getJSONArray("conversations")
        (0 until array.length()).map { val item = array.getJSONObject(it); ConversationPreview(account(item.getJSONObject("profile")), item.getString("last_text"), item.optInt("unread_count").coerceAtLeast(0)) }
    }

    suspend fun registerPush(pushToken: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        syncLocal()
        request("push-token", JSONObject().put("token", pushToken).put("enabled", enabled))
        Unit
    }

    suspend fun peer(peerId: String): SalomAccount = withContext(Dispatchers.IO) {
        syncLocal()
        account(request("profiles/" + URLEncoder.encode(peerId, "UTF-8")))
    }


    suspend fun clearConversation(peerId: String): Long = withContext(Dispatchers.IO) {
        request("messages?peer_id=" + URLEncoder.encode(peerId, "UTF-8"), method = "DELETE").getLong("cleared_through")
    }
    suspend fun editMessage(id: Long, text: String): Message = withContext(Dispatchers.IO) { message(request("messages/$id", JSONObject().put("text",text), "PUT")) }

    suspend fun setReaction(id: Long, emoji: String?) = withContext(Dispatchers.IO) {
        request("messages/$id/reaction", JSONObject().put("emoji", emoji ?: JSONObject.NULL), "PUT")
        Unit
    }
    suspend fun deleteMessage(id: Long, everyone: Boolean) = withContext(Dispatchers.IO) { request("messages/$id?scope="+(if(everyone) "everyone" else "me"),method="DELETE"); Unit }
    suspend fun startLiveLocation(peerId: String, minutes: Int): Pair<String,Message> = withContext(Dispatchers.IO) {
        val result=request("locations",JSONObject().put("peer_id",peerId).put("duration_minutes",minutes),"POST")
        result.getString("id") to message(result.getJSONObject("message"))
    }
    suspend fun sendLocationOnce(peerId:String,latitude:Double,longitude:Double):Message = withContext(Dispatchers.IO) {
        message(request("locations/once",JSONObject().put("peer_id",peerId).put("latitude",latitude).put("longitude",longitude),"POST").getJSONObject("message"))
    }
    suspend fun updateLiveLocation(id:String,latitude:Double,longitude:Double) = withContext(Dispatchers.IO) { request("locations/$id",JSONObject().put("latitude",latitude).put("longitude",longitude),"PUT"); Unit }
    suspend fun liveLocation(id:String):JSONObject = withContext(Dispatchers.IO) { request("locations/$id") }
    suspend fun stopLiveLocation(id:String) = withContext(Dispatchers.IO) { request("locations/$id",method="DELETE"); Unit }
    private val mediaHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .followRedirects(false)
        .build()

    private fun mediaRequest(path: String) = Request.Builder()
        .url("https://mukimov.site/salom/api/$path")
        .header("Authorization", "Bearer $token")

    private suspend fun <T> transfer(request: Request, failureText: String, consume: (Response) -> T): T =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val call = mediaHttp.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(DirectoryException(failureText)))
                }
                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        val result = try {
                            if (!it.isSuccessful) {
                                val detail = runCatching { JSONObject(it.body?.string().orEmpty()).optString("detail") }.getOrNull()
                                throw DirectoryException(detail?.takeIf(String::isNotBlank) ?: failureText, it.code)
                            }
                            Result.success(consume(it))
                        } catch (failure: DirectoryException) { Result.failure(failure) }
                        catch (_: Exception) { Result.failure(DirectoryException(failureText)) }
                        continuation.resumeWith(result)
                    }
                }
            })
        }

    internal suspend fun uploadAttachment(peer: String, attachment: Attachment, file: java.io.File, progress: (Float) -> Unit = {}): Attachment {
        val size = file.length()
        if (size <= 0 || size != attachment.size) throw DirectoryException("Локальный файл недоступен")
        val checksum = file.inputStream().use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(64 * 1024)
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = attachment.mime.toMediaTypeOrNull()
            override fun contentLength() = size
            override fun writeTo(sink: okio.BufferedSink) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var lastPercent = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        total += count
                        val percent = (100 * total / size).toInt()
                        if (percent != lastPercent) { lastPercent = percent; progress(percent / 100f) }
                    }
                }
            }
        }
        val path = "attachments?peer_id=${URLEncoder.encode(peer, "UTF-8")}&name=${URLEncoder.encode(attachment.name, "UTF-8")}" +
            "&kind=${attachment.kind}&duration_ms=${attachment.durationMs}&waveform=${attachment.waveform.joinToString(",")}&upload_id=${attachment.id}"
        val request = mediaRequest(path).header("X-Content-SHA256", checksum).post(body).build()
        return transfer(request, "Не удалось загрузить файл. Проверь интернет и повтори") {
            Attachment.from(JSONObject(it.body?.string().orEmpty()))
        }
    }

    internal suspend fun downloadAttachment(attachment: Attachment, file: java.io.File, progress: (Float) -> Unit) {
        transfer(mediaRequest("attachments/${attachment.id}").build(), "Не удалось скачать файл. Проверь интернет и повтори") { response ->
            val temporary = java.io.File(file.parentFile, "${attachment.id}.${UUID.randomUUID()}.part")
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val expected = response.header("X-Content-SHA256")
            var total = 0L
            var lastPercent = -1
            try {
                val body = response.body ?: throw DirectoryException("Файл недоступен")
                body.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MediaFiles.MAX_FILE || total > attachment.size) throw DirectoryException("Неверный размер вложения")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val percent = (100 * total / attachment.size).toInt()
                        if (percent != lastPercent) { lastPercent = percent; progress(percent / 100f) }
                    }
                } }
                if (total != attachment.size) throw DirectoryException("Файл скачан не полностью")
                if (expected != null && expected != digest.digest().joinToString("") { "%02x".format(it) })
                    throw DirectoryException("Контрольная сумма файла не совпала")
                if (!temporary.renameTo(file)) throw DirectoryException("Не удалось сохранить файл")
            } finally { temporary.delete() }
        }
    }

    private fun message(json: JSONObject): Message {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date((json.getDouble("created") * 1000).toLong()))
        val quote = json.optJSONObject("reply_to")
        val quoteSender = quote?.optString("sender_id")
        val mine = json.getString("sender_id") == ownId
        val reactions = json.optJSONObject("reactions")
        val senderReaction = reactions?.opt("sender") as? String
        val recipientReaction = reactions?.opt("recipient") as? String
        return Message(json.getString("text"), mine, time, json.getLong("id"), json.optJSONObject("attachment")?.let {
            Attachment.from(it).copy(extraPhotos = Attachment.parsePhotos(json.optJSONArray("album")).drop(1))
        }, json.optString("receipt", "sent"), quote?.optLong("id")?.takeIf { it > 0 }, quoteSender?.let { if (it == ownId) "Вы" else "Собеседник" }, quote?.optString("text"), json.optDouble("edited_at",0.0)>0, json.optString("location_id").takeIf { it.isNotBlank() && it!="null" }, json.optString("client_id").takeIf { it.isNotBlank() && it != "null" },
            myReaction = if (mine) senderReaction else recipientReaction, peerReaction = if (mine) recipientReaction else senderReaction)
    }
}
