package com.mukimov.salom

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.UUID
import android.Manifest
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.compose.ui.window.Dialog

private data class CallPresentation(val callId: String, val peerId: String, val peerName: String, val incoming: Boolean, val state: String)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun Conversation(person: Person, store: Store, directory: DirectoryClient, wallpaperId: String, incomingCall: IncomingCallIntent?, onCallIntentHandled: () -> Unit, onWallpaperChange: (String) -> Unit, onBack: () -> Unit) {
    val saved = person.id == "saved"
    val context = LocalContext.current
    val localCache = remember(context) { LocalChatCache(context) }
    val media = remember(directory) { MediaFiles(context, directory) }
    val audio = remember(media) { ChatAudioPlayer(context, media) }
    val recorder = remember(media) { VoiceRecorder(context, media) }
    var recording by remember { mutableStateOf(false) }
    var startingVoice by remember { mutableStateOf(false) }
    var voiceGeneration by remember { mutableIntStateOf(0) }
    var recordTime by remember { mutableLongStateOf(0) }
    var liveLevels by remember { mutableStateOf<List<Int>>(emptyList()) }
    var micDenied by remember { mutableStateOf(false) }
    var messages by remember(person.id) { mutableStateOf(if (saved) store.messages(person.id) else emptyList()) }
    var cacheReady by remember(person.id) { mutableStateOf(saved) }
    var draft by remember(person.id) { mutableStateOf("") }
    var selected by remember { mutableStateOf<Attachment?>(null) }
    var uploaded by remember { mutableStateOf<Attachment?>(null) }
    var sending by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var attachmentsMenu by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var confirmClear by remember { mutableStateOf(false) }
    var showLocationOptions by remember { mutableStateOf(false) }
    var locationDuration by remember { mutableStateOf("60") }
    var requestedLocationAction by remember { mutableStateOf("") }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var peerOnline by remember(person.id) { mutableStateOf(false) }
    var peerTyping by remember(person.id) { mutableStateOf(false) }
    var realtimeConnected by remember(person.id) { mutableStateOf(false) }
    var replyingTo by remember(person.id) { mutableStateOf<Message?>(null) }
    var messageActions by remember { mutableStateOf<Message?>(null) }
    var confirmDeleteMessage by remember { mutableStateOf<Message?>(null) }
    var editingMessage by remember(person.id) { mutableStateOf<Message?>(null) }
    var pendingMessage by remember(person.id) { mutableStateOf<Message?>(null) }
    var queuedOutgoing by remember(person.id) { mutableStateOf<List<CachedOutgoing>>(emptyList()) }
    var showWallpapers by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(!saved) }
    var retry by remember { mutableIntStateOf(0) }
    var clearedThrough by remember { mutableLongStateOf(0) }
    var pendingSignature by remember { mutableStateOf("") }
    var pendingId by remember { mutableStateOf("") }
    var eventCursor by remember(person.id) { mutableLongStateOf(0L) }
    var messageCursor by remember(person.id) { mutableLongStateOf(0L) }
    val latestMessages by rememberUpdatedState(messages)
    val latestOutgoing by rememberUpdatedState(queuedOutgoing)
    val latestSending by rememberUpdatedState(sending)
    val scope = rememberCoroutineScope()
    var activeCall by remember(person.id) { mutableStateOf<CallPresentation?>(null) }
    var callEngine by remember(person.id) { mutableStateOf<CallEngine?>(null) }
    var pendingCallSignals by remember(person.id) { mutableStateOf<List<Pair<String, org.json.JSONObject>>>(emptyList()) }
    var callMuted by remember { mutableStateOf(false) }
    var callSpeaker by remember { mutableStateOf(false) }
    var callError by remember { mutableStateOf<String?>(null) }
    var pendingOutgoing by remember { mutableStateOf(false) }
    val callMicPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val call = activeCall
        if (granted && call == null && pendingOutgoing) {
            pendingOutgoing = false
            scope.launch {
                try {
                    val result = directory.createCall(person.id)
                    activeCall = CallPresentation(result.getString("call_id"), person.id, person.name, false, "ringing")
                    val engine = CallEngine(context, result.getString("call_id"), person.id, person.name, directory, scope) { state -> activeCall = activeCall?.copy(state = state) }
                    engine.prepare(); callEngine = engine
                    if (activeCall?.state == "accepted") { activeCall = activeCall?.copy(state = "connecting"); engine.createOffer() }
                } catch (failure: Exception) {
                    activeCall?.let { failed -> activeCall = failed.copy(state = "failed"); scope.launch { runCatching { directory.updateCall(failed.callId, "end") } } }
                    callError = failure.message ?: "Не удалось начать звонок"; sendError = callError
                }
            }
        } else if (granted && call != null) scope.launch {
            runCatching {
                if (call.incoming) { directory.updateCall(call.callId, "accept"); PushNotifications.cancelCall(context, call.callId) }
                val engine = CallEngine(context, call.callId, call.peerId, call.peerName, directory, scope) { state -> activeCall = activeCall?.copy(state = state) }
                engine.prepare(); callEngine = engine; callMuted = false; callSpeaker = false
                activeCall = call.copy(state = if (call.incoming || call.state == "accepted") "connecting" else "ringing")
                if (!call.incoming && call.state == "accepted") engine.createOffer()
                val queued = pendingCallSignals; pendingCallSignals = emptyList()
                queued.forEach { (kind, payload) -> engine.receive(kind, payload) }
            }.onFailure {
                callEngine?.close(); callEngine = null
                activeCall = activeCall?.copy(state = "failed")
                callError = "Не удалось подключить звонок: ${it.message ?: "проверь интернет"}"
                scope.launch { runCatching { directory.updateCall(call.callId, "end") } }
            }
        } else if (!granted) { pendingOutgoing = false; callError = "Для звонка нужно разрешить доступ к микрофону"; sendError = callError }
    }
    fun requestCallMicrophone() = callMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    fun startOutgoingCall() {
        if (saved || activeCall != null) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingOutgoing = true
            callMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        scope.launch {
            try {
                val result = directory.createCall(person.id)
                activeCall = CallPresentation(result.getString("call_id"), person.id, person.name, false, "ringing")
                val engine = CallEngine(context, result.getString("call_id"), person.id, person.name, directory, scope) { state -> activeCall = activeCall?.copy(state = state) }
                engine.prepare(); callEngine = engine
                if (activeCall?.state == "accepted") { activeCall = activeCall?.copy(state = "connecting"); engine.createOffer() }
            } catch (failure: Exception) {
                activeCall?.let { failed -> activeCall = failed.copy(state = "failed"); scope.launch { runCatching { directory.updateCall(failed.callId, "end") } } }
                callError = failure.message ?: "Не удалось начать звонок"; sendError = callError
            }
        }
    }
    fun endCall(action: String) {
        val call = activeCall ?: return
        activeCall = null; callEngine?.close(); callEngine = null; pendingCallSignals = emptyList()
        PushNotifications.cancelCall(context, call.callId)
        scope.launch {
            runCatching { directory.updateCall(call.callId, action) }
            if (action == "missed") runCatching { directory.missedCalls() }.getOrNull()?.takeIf { it > 0 }?.let { count ->
                android.widget.Toast.makeText(context, if (count == 1) "Пропущенный звонок" else "Пропущенные звонки ($count)", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        onCallIntentHandled()
    }
    LaunchedEffect(incomingCall, person.id) {
        incomingCall?.takeIf { it.peerId == person.id }?.let { call ->
            activeCall = CallPresentation(call.callId, call.peerId, call.peerName, true, "incoming")
            onCallIntentHandled()
            if (call.action == "accept") requestCallMicrophone()
            if (call.action == "decline") endCall("decline")
            if (call.action == "end") endCall("end")
        }
    }
    LaunchedEffect(activeCall?.callId, activeCall?.state) {
        val call = activeCall ?: return@LaunchedEffect
        val ringingTimeout = if (call.incoming) call.state == "incoming" else call.state == "ringing"
        val connectingTimeout = call.state in setOf("connecting", "reconnecting")
        if (ringingTimeout || connectingTimeout) {
            delay(if (connectingTimeout) 30_000 else 45_000)
            if (activeCall?.callId == call.callId && activeCall?.state == call.state) {
                if (call.incoming && call.state == "incoming") endCall("missed")
                else endCall(if (call.state == "ringing") "cancel" else "end")
            }
        }
    }
    DisposableEffect(activeCall?.callId, activeCall?.state) {
        var ringtone: Ringtone? = null
        if (activeCall?.state == "incoming") {
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            if (Build.VERSION.SDK_INT >= 28) ringtone?.isLooping = true
            ringtone?.play()
        }
        onDispose { runCatching { ringtone?.stop() } }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val listState = rememberLazyListState()
    val busy = sending || preparing || clearing || recording || startingVoice
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun cancelVoice() { voiceGeneration++; recorder.cancel(); recording = false; startingVoice = false; recordTime = 0; liveLevels = emptyList() }

    suspend fun attemptQueued(item: CachedOutgoing, sendingClaimed: Boolean = false): Boolean {
        if (saved || (!sendingClaimed && sending)) return false
        if (!sendingClaimed) sending = true
        try {
            val attachment = item.attachment()
            val remoteAttachment = if (attachment != null) {
                uploadProgress = 0f
                media.upload(person.id, attachment) { uploadProgress = it }
            } else null
            val sent = directory.sendMessage(person.id, item.text, item.clientId, remoteAttachment, item.replyToId)
            localCache.upsertMessages(person.id, listOf(sent))
            localCache.removeQueued(item.clientId)
            queuedOutgoing = queuedOutgoing.filterNot { it.clientId == item.clientId }
            messages = (messages + sent).filter { it.remoteId > clearedThrough }.distinctBy { it.remoteId }.sortedBy { it.remoteId }
            attachment?.let { media.removeLocal(it) }
            if (pendingId == item.clientId) {
                draft = ""; selected = null; uploaded = null; replyingTo = null; pendingId = ""; pendingSignature = ""
            }
            sendError = null
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            sendError = (failure as? DirectoryException)?.message ?: "Сообщение сохранено и отправится, когда связь восстановится"
            return false
        } finally {
            sending = false
            pendingMessage = null
        }
    }
    fun startVoice() {
        if (busy || draft.isNotBlank() || selected != null) return
        audio.stop(); focusManager.clearFocus(); keyboard?.hide(); sendError = null; micDenied = false
        startingVoice = true; voiceGeneration++; val ticket = voiceGeneration
        scope.launch {
            try {
                recorder.start()
                if (ticket == voiceGeneration && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { recording = true; recordTime = 0; liveLevels = emptyList() }
                else recorder.cancel()
            } catch (cancelled: CancellationException) { recorder.cancel(); throw cancelled }
            catch (failure: Exception) { sendError = failure.message ?: "Не удалось начать запись" }
            finally { startingVoice = false }
        }
    }
    fun finishVoice() {
        if (!recording) return
        recording = false; preparing = true
        scope.launch {
            try { selected = recorder.finish(); uploaded = null; sendError = null }
            catch (cancelled: CancellationException) { recorder.cancel(); throw cancelled }
            catch (failure: Exception) { sendError = failure.message ?: "Не удалось сохранить запись" }
            finally { preparing = false; liveLevels = emptyList() }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else { micDenied = true; sendError = "Для голосовых сообщений разреши доступ к микрофону" }
    }
    DisposableEffect(lifecycle, recorder, audio) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { cancelVoice(); audio.stop() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancelVoice(); audio.stop() }
    }
    androidx.activity.compose.BackHandler(recording || startingVoice) { cancelVoice() }
    LaunchedEffect(recording) {
        while (recording) {
            delay(120); recordTime = recorder.elapsed(); liveLevels = (liveLevels.takeLast(31) + recorder.sample())
            if (recordTime >= VoicePolicy.MAX_MS || recorder.reachedLimit) { finishVoice(); break }
        }
    }
    val choose: (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) {
            preparing = true; sendError = null; micDenied = false
            scope.launch {
                try { val prepared = media.prepare(uri); selected?.let { media.removeLocal(it) }; selected = prepared; uploaded = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { sendError = (failure as? DirectoryException)?.message ?: "Не удалось открыть файл" }
                finally { preparing = false }
            }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), choose)
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), choose)

    fun openLocationMap(id:String) {
        scope.launch {
            try {
                val point=directory.liveLocation(id)
                if(point.isNull("latitude") || point.isNull("longitude")) throw DirectoryException("Собеседник ещё не поделился первой точкой")
                val lat=point.getDouble("latitude"); val lon=point.getDouble("longitude")
                val uri=android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon")
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,uri))
            } catch(failure:Exception) { sendError=(failure as? DirectoryException)?.message ?: "Не удалось открыть карту" }
        }
    }

    var locationPermissionResult by remember { mutableStateOf<Boolean?>(null) }
    val locationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants -> locationPermissionResult=grants.values.any { it } }
    fun runLocationAction(action: String) {
        if (saved) { sendError="Для геопозиции открой чат с собеседником"; return }
        requestedLocationAction=action
        val fine=androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse=androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.ACCESS_COARSE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED
        if(fine || coarse) {
            scope.launch {
                try {
                    sendError=null
                    val location=currentDeviceLocation(context)
                    if(action=="once") {
                        val sent=directory.sendLocationOnce(person.id,location.latitude,location.longitude)
                        runCatching { localCache.upsertMessages(person.id, listOf(sent)) }
                        messages=(messages+sent).distinctBy { it.remoteId }.sortedBy { it.remoteId }
                    } else {
                        val minutes=locationDuration.toIntOrNull()?.takeIf { it in 15..1440 } ?: throw DirectoryException("Укажи время от 15 до 1440 минут")
                        val (id,message)=directory.startLiveLocation(person.id,minutes)
                        runCatching { localCache.upsertMessages(person.id, listOf(message)) }
                        messages=(messages+message).distinctBy { it.remoteId }.sortedBy { it.remoteId }
                        val service=android.content.Intent(context,LiveLocationService::class.java).putExtra(LiveLocationService.EXTRA_ID,id).putExtra(LiveLocationService.EXTRA_PEER,person.name).putExtra(LiveLocationService.EXTRA_EXPIRES,System.currentTimeMillis()+minutes*60_000L)
                        androidx.core.content.ContextCompat.startForegroundService(context,service)
                    }
                    showLocationOptions=false
                } catch(failure:Exception) { sendError=(failure as? DirectoryException)?.message ?: "Не удалось получить геопозицию. Проверь GPS и повтори" }
            }
        } else locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION,android.Manifest.permission.ACCESS_FINE_LOCATION))
    }
    LaunchedEffect(locationPermissionResult) {
        when(locationPermissionResult) {
            true -> { locationPermissionResult=null; runLocationAction(requestedLocationAction) }
            false -> { locationPermissionResult=null; sendError="Разреши доступ к геопозиции, чтобы отправить её собеседнику" }
            null -> Unit
        }
    }

    LaunchedEffect(person.id) { if (!saved) PushNotifications.cancelChat(context, person.id) }

    LaunchedEffect(person.id) {
        if (!saved) {
            queuedOutgoing = localCache.queuedMessages(person.id)
            val state = localCache.chatState(person.id)
            val cachedMessages = localCache.messages(person.id)
            messages = cachedMessages
            messageCursor = state?.messageCursor ?: cachedMessages.maxOfOrNull { it.remoteId } ?: 0L
            eventCursor = state?.eventCursor ?: 0L
            clearedThrough = state?.clearedThrough ?: 0L
            cacheReady = true
        }
    }

    LaunchedEffect(person.id, cacheReady) {
        if (!saved && cacheReady) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var backoff = 1_000L
            while (isActive) {
                val queued = latestOutgoing.firstOrNull()
                if (queued != null && !latestSending) {
                    if (attemptQueued(queued)) backoff = 1_000L else backoff = (backoff * 2).coerceAtMost(30_000L)
                }
                delay(backoff)
            }
        }
    }

    LaunchedEffect(person.id, retry, cacheReady) {
        if (!saved && cacheReady) {
            var cursor = maxOf(messageCursor, clearedThrough)
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    try {
                        do {
                            val receiptCursor = latestMessages.filter { it.mine && it.remoteId > 0 }.minOfOrNull { it.remoteId } ?: 0L
                            val batch = directory.messages(person.id, cursor, receiptCursor, eventCursor)
                            clearedThrough = maxOf(clearedThrough, batch.clearedThrough)
                            val refreshed = latestMessages.map { message -> if (message.mine) batch.receipts[message.remoteId]?.let { message.copy(receipt = it) } ?: message else message }
                            val merged = (refreshed.associateBy { it.remoteId } + batch.messages.associateBy { it.remoteId }).toMutableMap()
                            val changed = batch.messages.toMutableList()
                            val deletedIds = mutableListOf<Long>()
                            batch.events.forEach { event ->
                                when(event.kind) {
                                    "edit" -> event.message?.let { merged[it.remoteId] = it; changed += it }
                                    "delete", "hide" -> { merged.remove(event.messageId); deletedIds += event.messageId }
                                }
                            }
                            val receiptUpdates = latestMessages.mapNotNull { old ->
                                batch.receipts[old.remoteId]?.takeIf { it != old.receipt }?.let { old.copy(receipt = it) }
                            }
                            changed += receiptUpdates
                            eventCursor = maxOf(eventCursor, batch.events.lastOrNull()?.id ?: eventCursor)
                            cursor = maxOf(cursor, clearedThrough, batch.messages.lastOrNull()?.remoteId ?: 0)
                            messageCursor = maxOf(messageCursor, cursor)
                            localCache.applySync(person.id, changed, deletedIds, messageCursor, eventCursor, clearedThrough)
                            messages = merged.values.filter { it.remoteId > clearedThrough }.sortedBy { it.remoteId }
                        } while (batch.hasMore)
                        connectionError = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: DirectoryException) { connectionError = failure.message }
                    finally { loading = false }
                        if (realtimeConnected) awaitCancellation() else delay(1500)
                }
            }
        }
    }
    LaunchedEffect(person.id) {
        if (!saved) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var backoff = 1_000L
            while (isActive) {
                try {
                    directory.realtime(person.id).collect { event ->
                        if (event.connected) {
                            realtimeConnected = true
                            backoff = 1_000L
                            retry++ // Fetch anything missed before the socket became ready.
                        } else when (event.type) {
                            "message", "sync" -> retry++
                            "typing" -> peerTyping = event.typing ?: false
                            "presence" -> { peerOnline = event.online ?: false; retry++ }
                            "call_invite" -> if (event.callId != null && event.peerId == person.id && activeCall == null) {
                                activeCall = CallPresentation(event.callId, person.id, event.callerName ?: person.name, true, "incoming")
                            }
                            "call_state" -> if (event.callId == activeCall?.callId && event.state != null) {
                                if (event.state == "accepted" && activeCall?.incoming == false) {
                                    activeCall = activeCall?.copy(state = if (callEngine == null) "accepted" else "connecting")
                                    callEngine?.createOffer()
                                } else if (event.state in setOf("declined", "cancelled", "ended", "missed")) {
                                    PushNotifications.cancelCall(context, event.callId.orEmpty())
                                    callEngine?.close(); callEngine = null; activeCall = null; onCallIntentHandled()
                                }
                            }
                            "call_signal" -> if (event.callId == activeCall?.callId && event.signalKind != null && event.signalPayload != null) {
                                val engine = callEngine
                                if (engine == null) pendingCallSignals = pendingCallSignals + (event.signalKind to event.signalPayload)
                                else engine.receive(event.signalKind, event.signalPayload)
                            }
                            "closed", "disconnected" -> { realtimeConnected = false; retry++ }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
                realtimeConnected = false
                retry++
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }
    LaunchedEffect(person.id, realtimeConnected) {
        if (!saved) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (realtimeConnected) awaitCancellation()
            while (isActive) {
                try { peerOnline = directory.peerOnline(person.id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { peerOnline = false }
                delay(5_000)
            }
        }
    }
    LaunchedEffect(person.id, realtimeConnected) {
        if (!saved) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (realtimeConnected) awaitCancellation()
            while (isActive) {
                try { peerTyping = directory.peerTyping(person.id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { peerTyping = false }
                delay(1_000)
            }
        }
    }
    LaunchedEffect(person.id) {
        if (!saved) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { draft.isNotBlank() to realtimeConnected }.distinctUntilChanged().collectLatest { (typing, connected) ->
                if (connected) { directory.sendRealtimeTyping(person.id, typing); return@collectLatest }
                if (typing) {
                    while (true) {
                        try { directory.setTyping(person.id, true) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                        delay(2_000)
                    }
                } else try { directory.setTyping(person.id, false) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            }
        }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(0) }
    val send: () -> Unit = {
        val text = draft.trim()
        val attachment = selected
        val editing = editingMessage
        if ((text.isNotEmpty() || attachment != null) && !busy) {
            if (attachment != null && audio.activeId == attachment.id) audio.stop()
            sending = true; sendError = null; micDenied = false
            val quote = replyingTo
            val signature = text + ":" + attachment?.id.orEmpty() + ":" + (quote?.remoteId ?: 0)
            if (signature != pendingSignature || pendingId.isEmpty()) { pendingSignature = signature; pendingId = UUID.randomUUID().toString() }
            val clientId = pendingId
            val sentAt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            if (!saved && editing == null) pendingMessage = Message(text, true, sentAt, attachment = attachment, receipt = "pending", replyToId = quote?.remoteId, replyAuthor = if (quote?.mine == true) "Вы" else person.name, replyPreview = quote?.let(::replyPreview))
            scope.launch {
                try {
                    if (editing != null && !saved) {
                        val changed = directory.editMessage(editing.remoteId, text)
                        runCatching { localCache.upsertMessages(person.id, listOf(changed)) }
                        messages = messages.map { if (it.remoteId == changed.remoteId) changed else it }
                    } else if (saved) {
                        val local = attachment?.let { media.saveLocal(it) }
                        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                        val sent = Message(text, true, time, attachment = local)
                        messages = messages + sent
                        store.saveMessages(person.id, messages)
                    } else {
                        val queuedAttachment = attachment?.let { media.persistForQueue(it) }
                        val queued = CachedOutgoing.create(clientId, person.id, text, System.currentTimeMillis(), queuedAttachment, quote?.remoteId, if (quote?.mine == true) "Вы" else person.name, quote?.let(::replyPreview))
                        localCache.enqueue(queued)
                        queuedOutgoing = localCache.queuedMessages(person.id)
                        attemptQueued(queued, sendingClaimed = true)
                    }
                    if (saved || editing != null) {
                        editingMessage = null
                        pendingMessage = null
                        draft = ""; selected = null; uploaded = null; replyingTo = null; pendingId = ""; pendingSignature = ""
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { sendError = (failure as? DirectoryException)?.message ?: "Не удалось отправить. Попробуй снова" }
                finally { pendingMessage = null; sending = false }
            }
        }
    }
    val dark = MaterialTheme.colorScheme.background.red < .5f
    val background = if (dark) listOf(Color(0xFF111D27), Color(0xFF17232B)) else listOf(Color(0xFFF2F0E6), Color(0xFFECEEE6))
    val chatBackground = remember(dark) { Brush.verticalGradient(background) }
    val chatSurface = if (dark) Color(0xFF23303D) else Color.White
    Column(Modifier.fillMaxSize().background(chatBackground).paint(painterResource(chatWallpaperResource(wallpaperId)), contentScale = ContentScale.Crop)) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 5.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChatAction("back", "Назад", onClick = { if (recording || startingVoice) cancelVoice() else onBack() })
            Spacer(Modifier.width(4.dp))
            Row(Modifier.weight(1f).heightIn(min = 42.dp).clip(RoundedCornerShape(24.dp)).background(chatSurface).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(person.color), contentAlignment = Alignment.Center) {
                    Text(person.initials, color = Ink, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    if (!saved && peerOnline) Box(Modifier.align(Alignment.BottomEnd).size(9.dp).clip(CircleShape).background(Color(0xFF40D27A)).border(1.5.dp, chatSurface, CircleShape))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                    Text(person.name, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (peerTyping) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("печатает", fontSize = 10.sp, lineHeight = 13.sp, color = Color(0xFF24B566))
                        Spacer(Modifier.width(4.dp)); TypingDots()
                    } else Text(if (saved) "Только для тебя" else if (peerOnline) "в сети" else "не в сети", fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, color = if (peerOnline) Color(0xFF24B566) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!saved) Box(Modifier.size(38.dp).clip(CircleShape).background(chatSurface).clickable { startOutgoingCall() }, contentAlignment = Alignment.Center) {
                Text("☎", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 19.sp)
            }
            Spacer(Modifier.width(4.dp))
            Box {
                Box(Modifier.size(40.dp).clip(CircleShape).background(chatSurface)) { ChatAction("menu", "Меню диалога", enabled = !busy) { menu = true } }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Фон чата") }, onClick = { menu = false; showWallpapers = true })
                    DropdownMenuItem(text = { Text("Очистить диалог") }, onClick = { menu = false; confirmClear = true })
                }
            }
        }
        val error = sendError ?: connectionError
        if (error != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (micDenied) context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName)))
                else { sendError = null; retry++ }
            }) { Text(if (micDenied) "Настройки" else "Повторить", fontSize = 11.sp) }
        }
        if (messages.isEmpty() && pendingMessage == null && queuedOutgoing.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = Yellow, strokeWidth = 2.dp)
            else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (saved) "Избранное" else "Начни с «Салом»", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(if (saved) "Сообщения и файлы для себя" else "Напиши сообщение или отправь фото", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        } else LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom)) {
            item(key = "pending-message") { pendingMessage?.let { BoxWithConstraints(Modifier.fillMaxWidth()) { ChatMessage(it, maxWidth * .82f, media, audio, false) } } }
            items(queuedOutgoing.asReversed(), key = { "queued-${it.clientId}" }) { queued ->
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    ChatMessage(queued.toMessage(), maxWidth * .82f, media, audio, false, peerName = person.name)
                }
            }
            itemsIndexed(messages.asReversed()) { index, message ->
                val newer = if (index > 0) messages[messages.lastIndex - index + 1] else null
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    ChatMessage(message, maxWidth * .82f, media, audio, newer != null && newer.mine == message.mine && newer.time == message.time, onReply = { if (!saved) replyingTo = message }, onLongPress = { messageActions = message }, onLocation = { openLocationMap(it) }, peerName = person.name)
                }
            }
        }

        selected?.let { attachment ->
            Row(Modifier.padding(horizontal=8.dp,vertical=3.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(chatSurface).padding(start=7.dp,top=5.dp,bottom=5.dp), verticalAlignment=Alignment.CenterVertically) {
                if (attachment.kind == "voice") Box(Modifier.weight(1f)) { AudioBubble(attachment,audio,if (dark) Color(0xFFE1E8ED) else Ink) }
                else {
                    if (attachment.mime.startsWith("image/")) SelectedAttachmentPreview(attachment, media)
                    Column(Modifier.weight(1f).padding(start=if (attachment.mime.startsWith("image/")) 8.dp else 2.dp)) {
                        Text(attachment.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp,lineHeight=16.sp)
                        Text(if(sending) "Загрузка ${(uploadProgress * 100).toInt().coerceIn(0,100)}%" else fileSize(attachment.size),fontSize=10.sp,lineHeight=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if (sending) LinearProgressIndicator(progress={uploadProgress},modifier=Modifier.fillMaxWidth().padding(top=3.dp),color=Yellow,trackColor=Yellow.copy(alpha=.18f))
                    }
                }
                ChatAction("close","Убрать вложение",enabled=!busy) { audio.stop(); scope.launch { media.removeLocal(attachment); selected=null; uploaded=null } }
            }
        }
        (editingMessage ?: replyingTo)?.let { quote ->
            Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(chatSurface).padding(start = 10.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(2.dp).heightIn(min = 28.dp).background(Yellow))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(if (editingMessage != null) "Редактирование" else if (quote.mine) "Ответ себе" else "Ответ ${person.name}", fontSize = 11.sp, lineHeight = 14.sp, color = if (dark) Yellow else Color(0xFF987400), fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(replyPreview(quote), fontSize = 12.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ChatAction("close", "Отменить") { if(editingMessage!=null) { editingMessage=null; draft="" } else replyingTo = null }
            }
        }
        if (preparing || clearing) LinearProgressIndicator(Modifier.fillMaxWidth(),color=Yellow)
        Row(Modifier.fillMaxWidth().padding(horizontal=7.dp,vertical=5.dp),verticalAlignment=Alignment.Bottom) {
            val inputShape=RoundedCornerShape(24.dp)
            Row(Modifier.weight(1f).heightIn(min=40.dp).clip(inputShape).background(chatSurface)
                .border(.7.dp,if (dark) Color.White.copy(alpha=.09f) else Color.Black.copy(alpha=.07f),inputShape)
                .padding(start=if(recording) 0.dp else 14.dp),verticalAlignment=Alignment.CenterVertically) {
                if (recording) {
                    ChatAction("close","Отменить запись",compact=true) { cancelVoice() }
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFE46B64)))
                    Text(audioTime(recordTime),fontSize=14.sp,lineHeight=18.sp,modifier=Modifier.padding(horizontal=8.dp),color=if(dark) Color.White else Ink)
                    Waveform(liveLevels,0f,Yellow,Modifier.weight(1f).height(26.dp).padding(end=12.dp))
                    ChatAction("stop","Завершить голосовую запись") { finishVoice() }
                } else {
                    BasicTextField(draft,{draft=it.take(4000)},enabled=!busy,modifier=Modifier.weight(1f).padding(vertical=8.dp),textStyle=TextStyle(color=if(dark) Color(0xFFEEF2F5) else Ink,fontSize=15.sp,lineHeight=20.sp),cursorBrush=SolidColor(Yellow),maxLines=4,decorationBox={inner ->
                        if(draft.isEmpty()) Text(if(selected==null) "Сообщение" else "Подпись",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=15.sp,lineHeight=20.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        inner()
                    })
                    Box {
                        ChatAction("attach","Прикрепить файл",enabled=!busy) { attachmentsMenu=true }
                    }
                    val hasContent=draft.isNotBlank() || selected!=null
                    val waiting=sending || preparing || clearing || startingVoice
                    val action=if(waiting) "busy" else if(recording) "stop" else if(hasContent) "send" else "mic"
                    Box(Modifier.padding(end=4.dp).size(34.dp).clip(CircleShape).background(Yellow.copy(alpha=if(waiting) .65f else 1f))
                        .clickable(enabled=!waiting,role=Role.Button) {
                            when(action) { "stop" -> finishVoice(); "send" -> send(); else -> if(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED) startVoice() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO) }
                        }.semantics { contentDescription=when(action){"mic"->"Записать голосовое";"stop"->"Остановить запись";"busy"->"Обработка сообщения";else->"Отправить"} },contentAlignment=Alignment.Center) {
                        AnimatedContent(action,transitionSpec={ (fadeIn(tween(140))+scaleIn(initialScale=.6f,animationSpec=tween(160))) togetherWith (fadeOut(tween(90))+scaleOut(targetScale=.65f,animationSpec=tween(100))) },label="microphone-send") { mode ->
                            if(mode=="busy") CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp,color=Ink) else ChatGlyph(mode,Ink)
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) AlertDialog(onDismissRequest = { if (!clearing) confirmClear = false }, title = { Text("Очистить диалог?") }, text = { Text(if (saved) "Все сообщения и файлы из Избранного будут удалены с этого телефона." else "Все сообщения этого диалога будут скрыты у тебя на всех устройствах. У собеседника история останется.") }, confirmButton = {
        TextButton(enabled = !clearing, onClick = {
            audio.stop(); clearing = true; sendError = null
            scope.launch {
                try {
                    if (saved) { val removed = messages; kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.clearMessages(person.id) }; messages = emptyList(); removed.forEach { it.attachment?.let { attachment -> media.removeLocal(attachment) } } }
                    else {
                        clearedThrough = maxOf(clearedThrough, directory.clearConversation(person.id))
                        runCatching { localCache.deleteThrough(person.id, clearedThrough) }
                        val queued = localCache.queuedMessages(person.id)
                        localCache.clearQueued(person.id)
                        queuedOutgoing = emptyList()
                        queued.mapNotNull { it.attachment() }.forEach { media.removeLocal(it) }
                        val old = messages.filter { it.remoteId <= clearedThrough }
                        messages = messages.filter { it.remoteId > clearedThrough }
                        old.forEach { it.attachment?.let { attachment -> media.removeLocal(attachment) } }
                        retry++
                    }
                    confirmClear = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { sendError = (failure as? DirectoryException)?.message ?: "Не удалось очистить диалог"; confirmClear = false }
                finally { clearing = false }
            }
        }) { Text("Очистить", color = MaterialTheme.colorScheme.error) }
    }, dismissButton = { TextButton(enabled = !clearing, onClick = { confirmClear = false }) { Text("Отмена") } })

    if (showWallpapers) AlertDialog(
        onDismissRequest = { showWallpapers = false },
        title = { Text("Фон чата") },
        text = {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("midnight" to "Ночь", "garden" to "Сад", "indigo" to "Индиго").forEach { (id, name) ->
                    Column(Modifier.width(88.dp).clip(RoundedCornerShape(13.dp)).clickable {
                        onWallpaperChange(id); showWallpapers = false
                    }.padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(painterResource(chatWallpaperResource(id)), contentDescription = name, modifier = Modifier.fillMaxWidth().height(152.dp).clip(RoundedCornerShape(11.dp)).then(if (wallpaperId == id) Modifier.border(2.dp, Yellow, RoundedCornerShape(11.dp)) else Modifier), contentScale = ContentScale.Crop)
                        Text(if (wallpaperId == id) "$name ✓" else name, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp), color = if (wallpaperId == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showWallpapers = false }) { Text("Готово") } }
    )

    if (attachmentsMenu) ModalBottomSheet(onDismissRequest={attachmentsMenu=false}, containerColor=chatSurface) {
        Text("Добавить",modifier=Modifier.padding(horizontal=20.dp,vertical=6.dp),fontSize=18.sp,fontWeight=FontWeight.SemiBold)
        listOf("Фото / Видео","Файл","Геопозиция").forEachIndexed { index, title ->
            ListItem(headlineContent={Text(title)}, supportingContent={Text(when(index){0->"Выбрать из галереи";1->"Документы и другие файлы";else->"Отправить место или делиться в реальном времени"})}, leadingContent={Text(when(index){0->"▧";1->"▤";else->"⌖"},fontSize=22.sp)}, modifier=Modifier.clickable {
                attachmentsMenu=false
                when(index){0->pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo));1->pickFile.launch(arrayOf("*/*"));else->showLocationOptions=true}
            }, colors=ListItemDefaults.colors(containerColor=Color.Transparent))
        }
        Text("Файлы до 25 МБ",modifier=Modifier.padding(start=20.dp,bottom=24.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if(showLocationOptions) AlertDialog(onDismissRequest={showLocationOptions=false},title={Text("Геопозиция")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Отправь точку или показывай, где ты находишься. Живую передачу можно остановить в уведомлении.",fontSize=13.sp,lineHeight=18.sp)
            OutlinedTextField(locationDuration,{locationDuration=it.filter(Char::isDigit).take(4)},label={Text("Время, минут")},supportingText={Text("От 15 минут до 1440 минут (24 часа)")},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number))
        }
    },confirmButton={Column(horizontalAlignment=Alignment.End) {
        TextButton(onClick={runLocationAction("once")}) { Text("Отправить место сейчас") }
        TextButton(onClick={runLocationAction("live")}) { Text("Начать передачу") }
    }},dismissButton={TextButton(onClick={showLocationOptions=false}) { Text("Отмена") }})

    messageActions?.let { target ->
        ModalBottomSheet(onDismissRequest={messageActions=null},containerColor=chatSurface) {
            Text("Сообщение",modifier=Modifier.padding(horizontal=20.dp,vertical=6.dp),fontSize=17.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
            ListItem(headlineContent={Text("Ответить")},leadingContent={Text("↩",fontSize=22.sp)},modifier=Modifier.clickable { replyingTo=target; editingMessage=null; messageActions=null },colors=ListItemDefaults.colors(containerColor=Color.Transparent))
            ListItem(headlineContent={Text("Копировать")},leadingContent={Text("▢",fontSize=20.sp)},modifier=Modifier.clickable {
                if(target.text.isNotBlank()) { val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager; clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Сообщение",target.text)) }
                messageActions=null
            },colors=ListItemDefaults.colors(containerColor=Color.Transparent))
            if(target.mine && target.attachment==null) ListItem(headlineContent={Text("Редактировать")},leadingContent={Text("✎",fontSize=20.sp)},modifier=Modifier.clickable { editingMessage=target; replyingTo=null; draft=target.text; selected=null; uploaded=null; messageActions=null },colors=ListItemDefaults.colors(containerColor=Color.Transparent))
            ListItem(headlineContent={Text("Удалить",color=MaterialTheme.colorScheme.error)},leadingContent={Text("⌫",fontSize=20.sp,color=MaterialTheme.colorScheme.error)},modifier=Modifier.clickable { confirmDeleteMessage=target; messageActions=null },colors=ListItemDefaults.colors(containerColor=Color.Transparent))
            Spacer(Modifier.height(20.dp))
        }
    }

    confirmDeleteMessage?.let { target ->
        AlertDialog(onDismissRequest={confirmDeleteMessage=null},title={Text("Удалить сообщение?")},text={Text(if(target.mine) "Можно удалить только у себя или у обоих участников." else "Сообщение будет скрыто только у тебя.")},
            confirmButton={Row { TextButton(onClick={
                val snapshot=target; confirmDeleteMessage=null
                scope.launch { try { if(!saved && snapshot.remoteId>0) directory.deleteMessage(snapshot.remoteId,false); messages=messages.filterNot { it.remoteId==snapshot.remoteId }; if(saved) store.saveMessages(person.id,messages) }
                    catch(failure:Exception){sendError=(failure as? DirectoryException)?.message ?: "Не удалось удалить сообщение"} }
            }) { Text("У себя") }; if(target.mine && !saved) TextButton(onClick={
                val snapshot=target; confirmDeleteMessage=null
                scope.launch { try { directory.deleteMessage(snapshot.remoteId,true); messages=messages.filterNot { it.remoteId==snapshot.remoteId } }
                    catch(failure:Exception){sendError=(failure as? DirectoryException)?.message ?: "Не удалось удалить сообщение"} }
            }) { Text("У всех") } } },dismissButton={TextButton(onClick={confirmDeleteMessage=null}) { Text("Отмена") }})
    }

    activeCall?.let { call ->
        Dialog(onDismissRequest = { }) {
            var callSeconds by remember(call.callId) { mutableIntStateOf(0) }
            LaunchedEffect(call.callId, call.state) {
                if (call.state == "connected") while (isActive) { delay(1_000); callSeconds++ }
            }
            Surface(shape = RoundedCornerShape(28.dp), color = if (dark) Color(0xFF17232B) else Color(0xFFFFFCF1), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(82.dp).clip(CircleShape).background(Yellow), contentAlignment = Alignment.Center) {
                        Text(call.peerName.take(1).uppercase(), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink)
                    }
                    Text(call.peerName, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(callError ?: when (call.state) {
                        "incoming" -> "Входящий голосовой звонок"
                        "ringing" -> "Вызов…"
                        "connecting" -> "Подключение…"
                        "connected" -> "Звонок продолжается"
                        "reconnecting" -> "Восстанавливаем связь…"
                        "failed" -> "Связь не установлена"
                        else -> "Голосовой звонок"
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (call.state == "connected") Text("%02d:%02d".format(callSeconds / 60, callSeconds % 60), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (call.state == "incoming") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            FilledTonalButton(onClick = { endCall("decline") }) { Text("Отклонить") }
                            Button(onClick = { callError = null; requestCallMicrophone() }) { Text("Ответить") }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = { callMuted = !callMuted; callEngine?.setMuted(callMuted) }) { Text(if (callMuted) "Включить микрофон" else "Без звука") }
                            FilledTonalButton(onClick = { callSpeaker = !callSpeaker; callEngine?.setSpeaker(callSpeaker) }) { Text(if (callSpeaker) "Динамик выкл." else "Динамик") }
                        }
                        Button(onClick = { endCall(if (call.state == "ringing") "cancel" else "end") }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Text("Завершить звонок")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedAttachmentPreview(attachment: Attachment, media: MediaFiles) {
    var bitmap by remember(attachment.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(attachment.id) { bitmap = runCatching { media.bitmap(attachment) }.getOrNull() }
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Yellow.copy(alpha=.15f)),contentAlignment=Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop) } ?: Text("▧",fontSize=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fileSize(bytes: Long): String = if (bytes >= 1024 * 1024) String.format(java.util.Locale.getDefault(), "%.1f МБ", bytes / (1024.0 * 1024.0)) else "${(bytes + 1023) / 1024} КБ"

private suspend fun currentDeviceLocation(context: android.content.Context): android.location.Location = kotlinx.coroutines.withTimeout(15_000) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
        val fine=androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse=androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.ACCESS_COARSE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED
        if(!fine && !coarse) throw DirectoryException("Разреши доступ к геопозиции")
        val manager=context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val providers=listOf(android.location.LocationManager.NETWORK_PROVIDER,android.location.LocationManager.GPS_PROVIDER).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val cached=providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if(cached!=null && System.currentTimeMillis()-cached.time<120_000) return@withContext cached
        val provider=providers.firstOrNull() ?: throw DirectoryException("Включи геолокацию на телефоне и попробуй снова")
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val listener=object:android.location.LocationListener {
                override fun onLocationChanged(location:android.location.Location) { if(continuation.isActive) continuation.resumeWith(Result.success(location)) }
            }
            continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
            try { manager.requestSingleUpdate(provider,listener,android.os.Looper.getMainLooper()) }
            catch (_:SecurityException) { continuation.resumeWith(Result.failure(DirectoryException("Нет разрешения на геопозицию"))) }
        }
    }
}

private fun replyPreview(message: Message): String = message.text.ifBlank {
    when {
        message.attachment?.kind == "voice" -> "Голосовое сообщение"
        message.attachment?.mime?.startsWith("image/") == true -> "Фото"
        message.attachment?.mime?.startsWith("video/") == true -> "Видео"
        message.attachment != null -> message.attachment.name
        else -> "Сообщение"
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(.25f, 1f, infiniteRepeatable(tween(420, delayMillis = index * 140), RepeatMode.Reverse), label = "typing-dot-$index")
            Box(Modifier.padding(end = 2.dp).size(3.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(Color(0xFF24B566)))
        }
    }
}

@Composable
internal fun AttachmentBubble(attachment: Attachment, media: MediaFiles, contentColor: Color) {
    val scope = rememberCoroutineScope()
    var bitmap by remember(attachment.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember(attachment.id) { mutableStateOf(false) }
    var error by remember(attachment.id) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val photo = attachment.mime.startsWith("image/")
    LaunchedEffect(attachment.id, attempt) {
        if (photo) {
            loading = true
            try { bitmap = media.bitmap(attachment); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Нажми, чтобы повторить" }
            finally { loading = false }
        }
    }
    val color = contentColor
    Column(Modifier.widthIn(max = 240.dp).clickable(role = Role.Button) {
        if (photo && error != null) attempt++ else scope.launch {
            loading = true; error = null
            try { media.open(attachment) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = (failure as? DirectoryException)?.message ?: "Не удалось открыть файл" }
            finally { loading = false }
        }
    }.padding(bottom = 3.dp)) {
        if (bitmap != null) {
            val ratio = (bitmap!!.width.toFloat() / bitmap!!.height.coerceAtLeast(1)).coerceIn(.48f, 2.05f)
            val imageWidth = if (ratio < 1f) (240.dp * ratio).coerceAtLeast(132.dp) else (240.dp).coerceAtMost(240.dp * ratio)
            Image(bitmap!!.asImageBitmap(), contentDescription = attachment.name, modifier = Modifier.width(imageWidth).heightIn(min = 132.dp, max = 320.dp).aspectRatio(ratio).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
        }
        else Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = color)
            else Text(if (attachment.mime.startsWith("video/")) "▶" else "↓", fontSize = 24.sp, lineHeight = 28.sp, color = color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.widthIn(max = 185.dp)) { Text(attachment.name, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = color); Text(fileSize(attachment.size), fontSize = 10.sp, lineHeight = 13.sp, color = color.copy(alpha = .65f)) }
        }
        error?.let { Text(it, fontSize = 10.sp, color = color) }
    }
}

@Composable
internal fun ChatAction(kind: String, label: String, enabled: Boolean = true, compact: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(if (compact) 36.dp else 40.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        ChatGlyph(kind, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .4f))
    }
}
@Composable
internal fun ChatGlyph(kind: String, color: Color) {
    Canvas(Modifier.size(21.dp)) {
        val w = size.width; val h = size.height; val line = w * .075f
        when (kind) {
            "mic" -> { drawRoundRect(color, Offset(w*.36f,h*.09f), Size(w*.28f,h*.51f), androidx.compose.ui.geometry.CornerRadius(w*.14f)); drawPath(Path().apply { moveTo(w*.22f,h*.42f); cubicTo(w*.22f,h*.88f,w*.78f,h*.88f,w*.78f,h*.42f) },color,style=Stroke(line)); drawLine(color,Offset(w*.5f,h*.76f),Offset(w*.5f,h*.94f),line); drawLine(color,Offset(w*.33f,h*.94f),Offset(w*.67f,h*.94f),line) }
            "play" -> drawPath(Path().apply { moveTo(w*.3f,h*.16f); lineTo(w*.8f,h*.5f); lineTo(w*.3f,h*.84f); close() },color)
            "pause" -> { drawRoundRect(color,Offset(w*.28f,h*.18f),Size(w*.15f,h*.64f),androidx.compose.ui.geometry.CornerRadius(w*.03f)); drawRoundRect(color,Offset(w*.57f,h*.18f),Size(w*.15f,h*.64f),androidx.compose.ui.geometry.CornerRadius(w*.03f)) }
            "stop" -> drawRoundRect(color,Offset(w*.23f,h*.23f),Size(w*.54f,h*.54f),androidx.compose.ui.geometry.CornerRadius(w*.08f))
            "menu" -> for (y in listOf(.22f, .5f, .78f)) drawCircle(color, w*.065f, Offset(w*.5f, h*y))
            "back" -> { drawLine(color, Offset(w*.65f,h*.2f), Offset(w*.35f,h*.5f), line); drawLine(color, Offset(w*.35f,h*.5f), Offset(w*.65f,h*.8f), line) }
            "close" -> { drawLine(color, Offset(w*.28f,h*.28f), Offset(w*.72f,h*.72f), line); drawLine(color, Offset(w*.72f,h*.28f), Offset(w*.28f,h*.72f), line) }
            "send" -> { drawPath(Path().apply { moveTo(w*.22f,h*.24f); lineTo(w*.83f,h*.5f); lineTo(w*.22f,h*.76f); lineTo(w*.34f,h*.5f); close() }, color); drawLine(Color.White.copy(alpha=.6f), Offset(w*.34f,h*.5f), Offset(w*.66f,h*.5f), w*.035f) }
            else -> drawPath(Path().apply { moveTo(w*.37f,h*.61f); lineTo(w*.66f,h*.32f); cubicTo(w*.78f,h*.2f,w*.93f,h*.36f,w*.79f,h*.51f); lineTo(w*.43f,h*.86f); cubicTo(w*.18f,h*1.08f,-w*.03f,h*.76f,w*.17f,h*.55f); lineTo(w*.57f,h*.15f); cubicTo(w*.82f,-h*.1f,w*1.14f,h*.23f,w*.92f,h*.46f); lineTo(w*.53f,h*.85f) }, color, style = Stroke(line))
        }
    }
}
