package com.mukimov.salom

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.lifecycle.withResumed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.UUID
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

private data class CallPresentation(val callId: String, val peerId: String, val peerName: String, val incoming: Boolean, val state: String, val video: Boolean = false)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun Conversation(person: Person, store: Store, directory: DirectoryClient, wallpaperId: String, incomingCall: IncomingCallIntent?, onCallIntentHandled: () -> Unit, onWallpaperChange: (String) -> Unit, onBack: () -> Unit) {
    val saved = person.id == "saved"
    val context = LocalContext.current
    val localCache = remember(context, directory.ownId) { LocalChatCache(context, directory.ownId) }
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
    var showPeerProfile by remember(person.id) { mutableStateOf(false) }
    var previewAttachment by remember(person.id) { mutableStateOf<Attachment?>(null) }
    var reactionTarget by remember(person.id) { mutableStateOf<Message?>(null) }
    var composerFocused by remember(person.id) { mutableStateOf(false) }
    var attachmentsMenu by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var transferJob by remember { mutableStateOf<Job?>(null) }
    var uploadingId by remember { mutableStateOf<String?>(null) }
    var pausedUploadId by remember { mutableStateOf<String?>(null) }
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
    var sendingId by remember(person.id) { mutableStateOf<String?>(null) }
    var failedIds by remember(person.id) { mutableStateOf(emptySet<String>()) }
    var blockedIds by remember(person.id) { mutableStateOf(emptySet<String>()) }
    var queuedOutgoing by remember(person.id) { mutableStateOf<List<CachedOutgoing>>(emptyList()) }
    var showWallpapers by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(!saved) }
    val syncRequests = remember(person.id) { Channel<Unit>(Channel.CONFLATED) }
    var clearedThrough by remember(person.id) { mutableLongStateOf(0) }
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
    var callVideoEnabled by remember { mutableStateOf(true) }
    var callError by remember { mutableStateOf<String?>(null) }
    var pendingOutgoing by remember(person.id) { mutableStateOf<Boolean?>(null) }
    var pendingAnswer by remember(person.id) { mutableStateOf<Pair<CallPresentation, Boolean>?>(null) }
    var engineReady by remember(person.id) { mutableStateOf(false) }
    val callLifecycle = LocalLifecycleOwner.current.lifecycle

    fun hasCallPermission(permission: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun prepareCall(call: CallPresentation, video: Boolean) {
        scope.launch {
            if (activeCall?.callId != call.callId) return@launch
            try {
                val engine = CallEngine(context, call.callId, call.peerId, call.peerName, directory, scope, video, caller = !call.incoming) { state ->
                    if (activeCall?.callId == call.callId) {
                        if (state == "video_unavailable") {
                            callVideoEnabled = false
                            callError = "Камера недоступна. Можно продолжить со звуком."
                        } else if (state == "failed" || state == "ended") {
                            engineReady = false
                            callEngine?.close()
                            callEngine = null
                            pendingCallSignals = emptyList()
                            CallSessionManager.end(call.callId)
                            activeCall = activeCall?.copy(state = "failed")
                            callError = "Связь прервалась. Попробуй позвонить снова."
                            directory.finishCall(call.callId)
                        } else activeCall = activeCall?.copy(state = state)
                    }
                }
                callEngine = engine
                callMuted = false
                callSpeaker = false
                callVideoEnabled = video
                kotlinx.coroutines.withTimeout(20_000) {
                    callLifecycle.withResumed { Unit }
                    engine.prepare()
                    snapshotFlow { realtimeConnected }.first { it }
                }
                if (activeCall?.callId != call.callId) return@launch
                engineReady = true
                if (call.incoming) {
                    android.util.Log.i("SalomCall", "Media and signaling ready, accepting incoming call")
                    val result = directory.updateCall(call.callId, "accept_leased")
                    check(result.optString("state") == "accepted") { "Вызов уже завершён" }
                } else if (CallSessionManager.current(call.callId) == CallSessionManager.State.CONNECTING) {
                    engine.createOffer()
                }
                if (activeCall?.callId != call.callId) return@launch
                val queued = pendingCallSignals
                pendingCallSignals = emptyList()
                queued.forEach { (kind, payload) -> engine.receive(kind, payload) }
            } catch (failure: Throwable) {
                if (failure is CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
                if (failure !is Exception && failure !is LinkageError) throw failure
                android.util.Log.e("SalomCall", "Call setup failed, incoming=" + call.incoming, failure)
                if (activeCall?.callId != call.callId) return@launch
                callEngine?.close()
                callEngine = null
                engineReady = false
                pendingCallSignals = emptyList()
                CallSessionManager.end(call.callId)
                PushNotifications.cancelCall(context, call.callId)
                activeCall = activeCall?.copy(state = "failed")
                callError = if (failure is kotlinx.coroutines.TimeoutCancellationException)
                    "Не удалось подключиться вовремя. Попробуй ещё раз."
                else failure.message?.takeIf { failure is DirectoryException } ?: "Не удалось подключить звонок. Проверь доступ к микрофону и сети."
                runCatching { directory.updateCall(call.callId, "end") }
            }
        }
    }

    fun createOutgoing(video: Boolean) {
        scope.launch {
            try {
                val id = directory.createCall(person.id, video).getString("call_id")
                if (!CallSessionManager.incoming(id)) {
                    runCatching { directory.updateCall(id, "cancel") }
                    error("Другой звонок уже открыт")
                }
                val call = CallPresentation(id, person.id, person.name, false, "ringing", video)
                activeCall = call
                callError = null
                prepareCall(call, video)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                sendError = failure.message ?: "Не удалось начать звонок"
            } finally {
                pendingOutgoing = null
            }
        }
    }

    val callMicPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val call = pendingAnswer?.first
        val wantsVideo = pendingAnswer?.second == true
        pendingAnswer = null
        val outgoing = pendingOutgoing
        if (call != null && activeCall?.callId != call.callId) return@rememberLauncherForActivityResult
        if (!hasCallPermission(Manifest.permission.RECORD_AUDIO)) {
            pendingOutgoing = null
            val message = "Разреши доступ к микрофону в настройках Android, чтобы звонить."
            if (call != null) {
                CallSessionManager.end(call.callId)
                activeCall = call.copy(state = "failed")
                callError = message
                scope.launch { runCatching { directory.updateCall(call.callId, "decline") } }
            } else sendError = message
        } else if (call != null) {
            val video = wantsVideo && hasCallPermission(Manifest.permission.CAMERA)
            if (wantsVideo && !video) callError = "Камера не разрешена. Звонок продолжится со звуком."
            prepareCall(call, video)
        } else if (outgoing != null) {
            val video = outgoing && hasCallPermission(Manifest.permission.CAMERA)
            if (outgoing && !video) sendError = "Камера не разрешена. Начинаем аудиозвонок."
            createOutgoing(video)
        }
    }

    fun startOutgoingCall(video: Boolean) {
        if (saved || activeCall != null || pendingOutgoing != null) return
        pendingOutgoing = video
        val permissions = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (permissions.all(::hasCallPermission)) createOutgoing(video)
        else callMicPermission.launch(permissions)
    }

    fun acceptIncomingCall(call: CallPresentation, withVideo: Boolean = false) {
        if (!CallSessionManager.accept(call.callId)) return
        PushNotifications.cancelCall(context, call.callId)
        activeCall = call.copy(state = "connecting")
        callError = null
        android.util.Log.i("SalomCall", "Incoming call accepted locally")
        val video = withVideo && call.video
        val permissions = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (permissions.all(::hasCallPermission)) prepareCall(call, video)
        else {
            pendingAnswer = call to video
            callMicPermission.launch(permissions)
        }
    }
    fun endCall(action: String) {
        val call = activeCall ?: return
        CallSessionManager.end(call.callId)
        engineReady = false
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
    DisposableEffect(person.id) {
        onDispose {
            activeCall?.let {
                CallSessionManager.end(it.callId)
                PushNotifications.cancelCall(context, it.callId)
                directory.finishCall(it.callId)
            }
            callEngine?.close()
        }
    }
    LaunchedEffect(incomingCall, person.id) {
        incomingCall?.takeIf { it.peerId == person.id }?.let { call ->
            if (activeCall != null && activeCall?.callId != call.callId) return@let
            if (!CallSessionManager.incoming(call.callId) && CallSessionManager.current(call.callId) == null) {
                onCallIntentHandled()
                return@let
            }
            val existing = CallSessionManager.current(call.callId)
            if (call.action == "end" && activeCall?.callId == call.callId) {
                endCall("end")
                return@let
            }
            if (existing == CallSessionManager.State.CONNECTING || existing == CallSessionManager.State.CONNECTED) {
                activeCall = activeCall ?: CallPresentation(call.callId, call.peerId, call.peerName, true,
                    if (existing == CallSessionManager.State.CONNECTED) "connected" else "connecting", call.video)
                onCallIntentHandled()
                return@let
            }
            activeCall = CallPresentation(call.callId, call.peerId, call.peerName, true, "incoming", call.video)
            onCallIntentHandled()
            if (call.action == "accept") activeCall?.let { acceptIncomingCall(it) }
            if (call.action == "decline") endCall("decline")
            if (call.action == "end") endCall("end")
        }
    }
    val renewingCall = activeCall?.state in setOf("connected", "reconnecting")
    LaunchedEffect(activeCall?.callId, renewingCall) {
        val call = activeCall ?: return@LaunchedEffect
        if (renewingCall) {
            while (isActive) {
                val state = try {
                    directory.updateCall(call.callId, "heartbeat").optString("state")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (state != null && state != "accepted" && activeCall?.callId == call.callId) {
                    endCall("end")
                    return@LaunchedEffect
                }
                delay(15_000)
            }
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
                else {
                    endCall(if (call.state == "ringing") "cancel" else "end")
                    sendError = if (connectingTimeout) "Не удалось установить связь. Проверь интернет и попробуй снова." else "Собеседник не ответил."
                }
            }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val listState = rememberLazyListState()
    val busy = sending || preparing || clearing || recording || startingVoice
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun cancelVoice() {
        if (!recording && !startingVoice) return
        voiceGeneration++
        recorder.cancelAsync()
        recording = false
        recordTime = 0
        liveLevels = emptyList()
    }

    suspend fun attemptQueued(item: CachedOutgoing, sendingClaimed: Boolean = false): Boolean {
        if (saved || (!sendingClaimed && sending)) return false
        if (!sendingClaimed) sending = true
        try {
            if (!localCache.hasQueued(person.id, item.clientId)) return true
            val started = android.os.SystemClock.elapsedRealtime()
            sendingId = item.clientId
            val attachment = item.attachment()
            val remoteAttachment = if (attachment != null) {
                uploadProgress = 0f
                uploadingId = item.clientId
                try {
                    coroutineScope {
                        val upload = async { media.upload(person.id, attachment) { uploadProgress = it } }
                        transferJob = upload
                        upload.await()
                    }
                } finally { transferJob = null; uploadingId = null }
            } else null
            val sent = directory.sendMessage(person.id, item.text, item.clientId, remoteAttachment, item.replyToId)
            android.util.Log.d("SalomMessage", "id=${item.clientId} stage=http_ack elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started}")
            localCache.confirmOutgoing(person.id, sent, item.clientId)
            failedIds = failedIds - item.clientId
            blockedIds = blockedIds - item.clientId
            queuedOutgoing = queuedOutgoing.filterNot { it.clientId == item.clientId }
            messages = (messages + sent).filter { it.remoteId > clearedThrough }.distinctBy { it.remoteId }.sortedBy { it.remoteId }
            attachment?.let { media.removeLocal(it) }
            sendError = null
            sent.attachment?.let { photo ->
                try { media.autoSaveOwnPhoto(photo) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { android.util.Log.w("SalomPhotos", "Could not save outgoing photo", failure) }
            }
            return true
        } catch (cancelled: CancellationException) {
            if (pausedUploadId != item.clientId || !kotlinx.coroutines.currentCoroutineContext().isActive) throw cancelled
            failedIds = failedIds + item.clientId
            sendError = "Передача отменена. Файл сохранён для повторной отправки."
            return false
        } catch (failure: Exception) {
            if (queuedOutgoing.none { it.clientId == item.clientId }) return true
            failedIds = failedIds + item.clientId
            if ((failure as? DirectoryException)?.status in setOf(400, 403, 404, 409, 413, 422)) {
                blockedIds = blockedIds + item.clientId
                sendError = failure.message
                return true
            }
            sendError = (failure as? DirectoryException)?.message ?: "Сообщение сохранено и отправится, когда связь восстановится"
            return false
        } finally {
            sending = false
            sendingId = null
        }
    }
    fun startVoice() {
        if (busy || startingVoice || recording || draft.isNotBlank() || selected != null || editingMessage != null) return
        audio.stop(); focusManager.clearFocus(); keyboard?.hide(); sendError = null; micDenied = false
        startingVoice = true; voiceGeneration++; val ticket = voiceGeneration
        scope.launch {
            try {
                recorder.start()
                if (ticket == voiceGeneration && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { recording = true; recordTime = 0; liveLevels = emptyList() }
                else recorder.cancelAsync()
            } catch (cancelled: CancellationException) { recorder.cancelAsync(); throw cancelled }
            catch (failure: Exception) {
                android.util.Log.w("SalomVoice", "Recorder start failed", failure)
                sendError = failure.message ?: "Не удалось начать запись"
            }
            finally { startingVoice = false }
        }
    }
    fun finishVoice() {
        if (!recording) return
        recording = false
        preparing = true
        val quote = replyingTo
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                val queued = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val voice = recorder.finish()
                    if (saved) {
                        val local = media.saveLocal(voice)
                        val now = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                        messages = messages + Message("", true, now, attachment = local)
                        store.saveMessages(person.id, messages)
                        media.removeLocal(voice)
                        null
                    } else {
                        val persistent = media.persistForQueue(voice)
                        val outgoing = CachedOutgoing.create(voice.id, person.id, "", System.currentTimeMillis(), persistent,
                            quote?.remoteId, if (quote?.mine == true) "Вы" else person.name, quote?.let(::replyPreview))
                        localCache.enqueue(outgoing)
                        try { media.removeLocal(voice) }
                        catch (failure: java.io.IOException) { android.util.Log.w("SalomVoice", "Could not clean recording cache", failure) }
                        outgoing
                    }
                }
                replyingTo = null
                sendError = null
                if (queued != null) {
                    queuedOutgoing = queuedOutgoing + queued
                    preparing = false
                    attemptQueued(queued)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                android.util.Log.w("SalomVoice", "Recorder finish failed", failure)
                sendError = failure.message ?: "Не удалось сохранить запись"
            } finally { preparing = false; liveLevels = emptyList() }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            micDenied = false
            sendError = "Удерживай микрофон, чтобы начать запись"
        } else {
            android.util.Log.i("SalomVoice", "Microphone permission was denied")
            micDenied = true
            sendError = "Для голосовых сообщений разреши доступ к микрофону"
        }
    }
    val microphoneAvailable by rememberUpdatedState(!busy && draft.isBlank() && selected == null && editingMessage == null)
    val beginHold by rememberUpdatedState<() -> Boolean>({
        if (hasCallPermission(Manifest.permission.RECORD_AUDIO)) {
            startVoice()
            true
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            false
        }
    })
    val releaseHold by rememberUpdatedState<() -> Unit>({
        if (recording) finishVoice()
        else if (startingVoice) {
            cancelVoice()
            sendError = "Запиши хотя бы одну секунду"
        }
    })
    val abortHold by rememberUpdatedState<() -> Unit>({ cancelVoice() })
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
                val queued = latestOutgoing.firstOrNull { it.clientId !in blockedIds && it.clientId != pausedUploadId }
                if (queued != null && !latestSending) {
                    if (attemptQueued(queued)) backoff = 1_000L else backoff = (backoff * 2).coerceAtMost(30_000L)
                }
                delay(backoff)
            }
        }
    }

    LaunchedEffect(person.id, cacheReady) {
        if (!saved && cacheReady) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    var synchronized = false
                    try {
                        do {
                            val receiptCursor = latestMessages.filter { it.mine && it.remoteId > 0 }.minOfOrNull { it.remoteId } ?: 0L
                            val batch = directory.messages(person.id, maxOf(messageCursor, clearedThrough), receiptCursor, eventCursor)
                            val nextCleared = maxOf(clearedThrough, batch.clearedThrough)
                            val refreshed = latestMessages.map { message -> if (message.mine) batch.receipts[message.remoteId]?.let { message.copy(receipt = it) } ?: message else message }
                            val merged = (refreshed.associateBy { it.remoteId } + batch.messages.associateBy { it.remoteId }).toMutableMap()
                            val changed = batch.messages.toMutableList()
                            val deletedIds = mutableListOf<Long>()
                            batch.events.forEach { event ->
                                when(event.kind) {
                                    "edit", "reaction" -> event.message?.let { merged[it.remoteId] = it; changed += it }
                                    "delete", "hide" -> { merged.remove(event.messageId); deletedIds += event.messageId }
                                }
                            }
                            val receiptUpdates = latestMessages.mapNotNull { old ->
                                batch.receipts[old.remoteId]?.takeIf { it != old.receipt }?.let { old.copy(receipt = it) }
                            }
                            changed += receiptUpdates
                            val nextEvent = maxOf(eventCursor, batch.events.lastOrNull()?.id ?: eventCursor)
                            val nextMessage = maxOf(messageCursor, nextCleared, batch.messages.lastOrNull()?.remoteId ?: 0)
                            val acknowledged = batch.messages.filter { it.mine }.mapNotNull { it.clientId }.distinct()
                            val removed = localCache.applySync(person.id, changed.filter { it.remoteId !in deletedIds }, deletedIds, nextMessage, nextEvent, nextCleared, acknowledged)
                            messageCursor = nextMessage
                            eventCursor = nextEvent
                            clearedThrough = maxOf(clearedThrough, nextCleared)
                            queuedOutgoing = queuedOutgoing.filterNot { it.clientId in acknowledged }
                            failedIds = failedIds - acknowledged.toSet()
                            blockedIds = blockedIds - acknowledged.toSet()
                            // Recheck the live list after Room yields so an HTTP acknowledgement cannot be lost.
                            val live = messages.filter { it.remoteId !in deletedIds && it.remoteId > clearedThrough }
                            messages = (live.associateBy { it.remoteId } + merged)
                                .values.filter { it.remoteId > clearedThrough }.sortedBy { it.remoteId }
                            removed.forEach { outgoing -> outgoing.attachment()?.let { runCatching { media.removeLocal(it) } } }
                        } while (batch.hasMore)
                        connectionError = null
                        synchronized = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: DirectoryException) { connectionError = failure.message }
                    catch (failure: Exception) {
                        android.util.Log.w("SalomMessage", "Chat sync failed", failure)
                        connectionError = "Не удалось обновить историю. Повторяем…"
                    }
                    finally { loading = false }
                    if (synchronized && realtimeConnected) syncRequests.receive()
                    else withTimeoutOrNull(1_500L) { syncRequests.receive() }
                }
            }
        }
    }
    LaunchedEffect(person.id) {
        if (!saved) lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
            var backoff = 1_000L
            while (isActive) {
                try {
                    directory.realtime(person.id).collect { event ->
                        if (event.connected) {
                            realtimeConnected = true
                            backoff = 1_000L
                            syncRequests.trySend(Unit) // Fetch anything missed before the socket became ready.
                        } else when (event.type) {
                            "message" -> {
                                val incoming = event.message
                                if (event.peerId == person.id && incoming != null && !incoming.mine &&
                                    incoming.remoteId > clearedThrough && messages.none { it.remoteId == incoming.remoteId }) {
                                    messages = (messages + incoming).sortedBy { it.remoteId }
                                    android.util.Log.d("SalomMessage", "id=${incoming.clientId} stage=websocket_visible")
                                }
                                syncRequests.trySend(Unit)
                            }
                            "sync" -> syncRequests.trySend(Unit)
                            "typing" -> peerTyping = event.typing ?: false
                            "presence" -> peerOnline = event.online ?: false
                            "call_invite" -> if (event.callId != null && event.peerId == person.id && activeCall == null) {
                                if (CallSessionManager.incoming(event.callId) || CallSessionManager.current(event.callId) == CallSessionManager.State.RINGING)
                                    activeCall = CallPresentation(event.callId, person.id, event.callerName ?: person.name, true, "incoming", event.video)
                            }
                            "call_state" -> if (event.callId == activeCall?.callId && event.state != null) {
                                if (event.state == "accepted" && activeCall?.incoming == false && CallSessionManager.accept(event.callId.orEmpty())) {
                                    activeCall = activeCall?.copy(state = "connecting")
                                    scope.launch { runCatching { directory.updateCall(event.callId.orEmpty(), "heartbeat") } }
                                    if (engineReady) callEngine?.createOffer()
                                } else if (event.state in setOf("declined", "cancelled", "ended", "missed")) {
                                    PushNotifications.cancelCall(context, event.callId.orEmpty())
                                    CallSessionManager.end(event.callId.orEmpty())
                                    callEngine?.close(); callEngine = null; activeCall = null
                                    engineReady = false
                                    pendingCallSignals = emptyList()
                                    onCallIntentHandled()
                                }
                            }
                            "call_signal" -> if (event.callId == activeCall?.callId && event.signalKind != null && event.signalPayload != null) {
                                val engine = callEngine
                                if (engine == null || !engineReady) {
                                    if (pendingCallSignals.size < 128) pendingCallSignals = pendingCallSignals + (event.signalKind to event.signalPayload)
                                }
                                else engine.receive(event.signalKind, event.signalPayload)
                            }
                            "closed", "disconnected" -> { realtimeConnected = false; syncRequests.trySend(Unit) }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
                realtimeConnected = false
                syncRequests.trySend(Unit)
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
            val clientId = UUID.randomUUID().toString()
            val queuedStarted = android.os.SystemClock.elapsedRealtime()
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
                        val queued = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            val queuedAttachment = attachment?.let { media.persistForQueue(it) }
                            CachedOutgoing.create(clientId, person.id, text, System.currentTimeMillis(), queuedAttachment,
                                quote?.remoteId, if (quote?.mine == true) "Вы" else person.name, quote?.let(::replyPreview))
                                .also { localCache.enqueue(it) }
                        }
                        queuedOutgoing = queuedOutgoing + queued
                        android.util.Log.d("SalomMessage", "id=$clientId stage=outbox_visible elapsed_ms=${android.os.SystemClock.elapsedRealtime() - queuedStarted}")
                        draft = ""; selected = null; uploaded = null; replyingTo = null
                        attemptQueued(queued, sendingClaimed = true)
                    }
                    if (saved || editing != null) {
                        editingMessage = null
                        draft = ""; selected = null; uploaded = null; replyingTo = null
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { sendError = (failure as? DirectoryException)?.message ?: "Не удалось отправить. Попробуй снова" }
                finally { sending = false }
            }
        }
    }
    val dark = MaterialTheme.colorScheme.background.red < .5f
    val background = if (dark) listOf(Color(0xFF111D27), Color(0xFF17232B)) else listOf(Color(0xFFF2F0E6), Color(0xFFECEEE6))
    val chatBackground = remember(dark) { Brush.verticalGradient(background) }
    val chatSurface = if (dark) Color(0xFF23303D) else Color.White
    val call = activeCall
    var minimizedCall by remember(call?.callId) { mutableStateOf(false) }
    var connectedAt by remember(call?.callId) { mutableLongStateOf(0L) }
    var callDuration by remember(call?.callId) { mutableStateOf<String?>(null) }
    LaunchedEffect(call?.callId, call?.state) {
        if (call?.state == "connected" && connectedAt == 0L) connectedAt = android.os.SystemClock.elapsedRealtime()
        if (call?.state == "incoming" || call?.state == "failed") minimizedCall = false
        while (connectedAt != 0L && (call?.state == "connected" || call?.state == "reconnecting")) {
            callDuration = audioTime(android.os.SystemClock.elapsedRealtime() - connectedAt)
            delay(1_000)
        }
    }
    androidx.activity.compose.BackHandler(call != null && minimizedCall) { minimizedCall = false }
    Column(Modifier.fillMaxSize().background(chatBackground).paint(painterResource(chatWallpaperResource(wallpaperId)), contentScale = ContentScale.Crop)) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF111721))
                .heightIn(min = 60.dp).padding(start = 2.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChatHeaderAction("back", "Назад") { if (recording || startingVoice) cancelVoice() else onBack() }
            Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !saved && !recording && !startingVoice, role = Role.Button, onClickLabel = "Открыть профиль") {
                    showPeerProfile = true
                }, verticalAlignment = Alignment.CenterVertically) {
            Avatar(person, size = 38)
            Column(Modifier.weight(1f).padding(start = 9.dp, end = 4.dp)) {
                Text(person.name, color = Color.White, fontSize = 17.sp, lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = when {
                    saved -> "Только для тебя"
                    peerTyping -> "печатает…"
                    peerOnline -> "в сети"
                    else -> "не в сети"
                }
                Text(status, color = if (!saved && (peerOnline || peerTyping)) Color(0xFFFFD766) else Color(0xFFB6BECC),
                    fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            }
            if (!saved) {
                ChatHeaderAction("video", "Видеозвонок") { startOutgoingCall(true) }
                ChatHeaderAction("phone", "Аудиозвонок") { startOutgoingCall(false) }
            }
            Box {
                ChatHeaderAction("menu", "Меню диалога", enabled = !busy) { menu = true }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Фон чата") }, onClick = { menu = false; showWallpapers = true })
                    DropdownMenuItem(text = { Text("Очистить диалог") }, onClick = { menu = false; confirmClear = true })
                }
            }
        }
        if (call != null && minimizedCall) {
            FilledTonalButton(onClick = { minimizedCall = false },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                Text(callDuration?.let { "Вернуться к звонку · $it" } ?: "Вернуться к звонку")
            }
        }
        val error = sendError ?: connectionError
        if (error != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (micDenied) context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName)))
                else {
                    val queued = queuedOutgoing.firstOrNull()
                    if (queued != null && !sending) {
                        pausedUploadId = null
                        scope.launch { attemptQueued(queued) }
                    }
                    else if (queued == null && (draft.isNotBlank() || selected != null)) send()
                    else { sendError = null; syncRequests.trySend(Unit) }
                }
            }) { Text(if (micDenied) "Настройки" else "Повторить", fontSize = 11.sp) }
        }
        if (uploadingId != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Загрузка ${(uploadProgress * 100).toInt()}%", fontSize = 12.sp)
                LinearProgressIndicator(progress = { uploadProgress }, modifier = Modifier.fillMaxWidth(), color = Yellow)
            }
            TextButton(onClick = {
                pausedUploadId = uploadingId
                transferJob?.cancel()
            }) { Text("Отмена") }
        }
        if (messages.isEmpty() && queuedOutgoing.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = Yellow, strokeWidth = 2.dp)
            else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (saved) "Избранное" else "Начни с «Салом»", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(if (saved) "Сообщения и файлы для себя" else "Напиши сообщение или отправь фото", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        } else LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom)) {
            items(queuedOutgoing.asReversed(), key = { "queued-${it.clientId}" }) { queued ->
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val bubbleWidth = maxWidth * .82f
                    val status = when (queued.clientId) {
                        sendingId -> "pending"
                        in failedIds -> "failed"
                        else -> "queued"
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        ChatMessage(queued.toMessage(status), bubbleWidth, media, audio, false, peerName = person.name,
                            onPreview = { previewAttachment = it })
                        if (status == "failed") TextButton(enabled = !sending, onClick = {
                            if (pausedUploadId == queued.clientId) pausedUploadId = null
                            blockedIds = blockedIds - queued.clientId
                            scope.launch { attemptQueued(queued) }
                        }) { Text("Повторить отправку") }
                    }
                }
            }
            itemsIndexed(messages.asReversed()) { index, message ->
                val newer = if (index > 0) messages[messages.lastIndex - index + 1] else null
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    ChatMessage(message, maxWidth * .82f, media, audio, newer != null && newer.mine == message.mine && newer.time == message.time,
                        onReply = { if (!saved) replyingTo = message }, onLongPress = { messageActions = message },
                        onReact = if (!saved && message.remoteId > 0) ({ reactionTarget = message }) else null,
                        onPreview = { previewAttachment = it },
                        onLocation = { openLocationMap(it) }, peerName = person.name, onQuote = { quoteId ->
                            val index = messages.asReversed().indexOfFirst { it.remoteId == quoteId }
                            if (index < 0) sendError = "Исходное сообщение недоступно в загруженной истории."
                            else scope.launch { listState.animateScrollToItem(index + queuedOutgoing.size) }
                        })
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
            val composerBorder by animateColorAsState(
                if (composerFocused && !recording) Yellow.copy(alpha = .62f) else
                    if (dark) Color.White.copy(alpha = .09f) else Color.Black.copy(alpha = .07f),
                animationSpec = tween(180), label = "composer-focus")
            Row(Modifier.weight(1f).animateContentSize(tween(170)).heightIn(min=40.dp).clip(inputShape).background(chatSurface)
                .border(.7.dp,composerBorder,inputShape)
                .padding(start=if(recording) 0.dp else 14.dp),verticalAlignment=Alignment.CenterVertically) {
                if (recording) {
                    ChatAction("close","Отменить запись",compact=true) { cancelVoice() }
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFE46B64)))
                    Text(audioTime(recordTime),fontSize=14.sp,lineHeight=18.sp,modifier=Modifier.padding(horizontal=8.dp),color=if(dark) Color.White else Ink)
                    Waveform(liveLevels,0f,Yellow,Modifier.weight(1f).height(26.dp).padding(end=12.dp))
                    Text("← Отмена", maxLines = 1, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    BasicTextField(draft, { draft = it.take(4000) }, enabled = !busy,
                        modifier = Modifier.weight(1f).onFocusChanged { composerFocused = it.isFocused }.padding(vertical = 8.dp),
                        textStyle = TextStyle(color = if (dark) Color(0xFFEEF2F5) else Ink, fontSize = 15.sp, lineHeight = 20.sp),
                        cursorBrush = SolidColor(Yellow), maxLines = 4, decorationBox = { inner ->
                            Box {
                                val placeholderOpacity by animateFloatAsState(
                                    if (draft.isEmpty()) 1f else 0f, tween(120), label = "composer-placeholder")
                                if (placeholderOpacity > .01f) {
                                    Text(if (selected == null) "Сообщение" else "Подпись",
                                        modifier = Modifier.graphicsLayer { alpha = placeholderOpacity },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp,
                                        lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                inner()
                            }
                        })
                    Box {
                        ChatAction("attach","Прикрепить файл",enabled=!busy) { attachmentsMenu=true }
                    }
                }
                    val hasContent=draft.isNotBlank() || selected!=null
                    val waiting=sending || preparing || clearing || startingVoice
                    val action=if(waiting) "busy" else if(recording) "holding" else if(hasContent) "send" else "mic"
                    Box(Modifier.padding(end=4.dp).size(42.dp)
                        // Keep the same pointer target while recording replaces the text field.
                        .pointerInput(person.id) {
                            val swipeDistance = 64.dp.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (!microphoneAvailable || down.isConsumed) return@awaitEachGesture
                                down.consume()
                                if (!beginHold()) return@awaitEachGesture
                                var ended = false
                                try {
                                    while (!ended) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                        val result = if (change == null) VoiceHoldAction.CANCEL else VoicePolicy.holdAction(
                                            leftDistance = down.position.x - change.position.x,
                                            threshold = swipeDistance,
                                            pressed = change.pressed,
                                            interrupted = change.isConsumed
                                        )
                                        change?.consume()
                                        ended = result != VoiceHoldAction.CONTINUE
                                        when (result) {
                                            VoiceHoldAction.RELEASE -> releaseHold()
                                            VoiceHoldAction.CANCEL -> abortHold()
                                            VoiceHoldAction.CONTINUE -> Unit
                                        }
                                    }
                                } finally {
                                    if (!ended) abortHold()
                                }
                            }
                        }
                        .clip(CircleShape).background(Yellow.copy(alpha=if(waiting) .65f else 1f))
                        .then(if (action == "send") Modifier.clickable(role = Role.Button, onClick = send) else Modifier)
                        .semantics { contentDescription=when(action){
                            "mic" -> "Удерживай для записи голосового"
                            "holding" -> "Отпусти для отправки голосового"
                            "busy" -> "Обработка сообщения"
                            else -> "Отправить"
                        } },contentAlignment=Alignment.Center) {
                        AnimatedContent(action,transitionSpec={ (fadeIn(tween(140))+scaleIn(initialScale=.6f,animationSpec=tween(160))) togetherWith (fadeOut(tween(90))+scaleOut(targetScale=.65f,animationSpec=tween(100))) },label="microphone-send") { mode ->
                            if(mode=="busy") CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp,color=Ink) else ChatGlyph(if (mode == "holding") "mic" else mode,Ink)
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
                        syncRequests.trySend(Unit)
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

    if (attachmentsMenu) AttachmentSheet(
        onDismiss = { attachmentsMenu = false },
        onPhoto = { attachmentsMenu = false; choose(it) },
        openPicker = {
            attachmentsMenu = false
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        },
        openFiles = { attachmentsMenu = false; pickFile.launch(arrayOf("*/*")) },
        openLocation = { attachmentsMenu = false; showLocationOptions = true }
    )

    if(showLocationOptions) AlertDialog(onDismissRequest={showLocationOptions=false},title={Text("Геопозиция")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Отправь точку или показывай, где ты находишься. Живую передачу можно остановить в уведомлении.",fontSize=13.sp,lineHeight=18.sp)
            OutlinedTextField(locationDuration,{locationDuration=it.filter(Char::isDigit).take(4)},label={Text("Время, минут")},supportingText={Text("От 15 минут до 1440 минут (24 часа)")},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number))
        }
    },confirmButton={Column(horizontalAlignment=Alignment.End) {
        TextButton(onClick={runLocationAction("once")}) { Text("Отправить место сейчас") }
        TextButton(onClick={runLocationAction("live")}) { Text("Начать передачу") }
    }},dismissButton={TextButton(onClick={showLocationOptions=false}) { Text("Отмена") }})

    (reactionTarget ?: messageActions)?.let { target ->
        val reactionsOnly = reactionTarget != null
        ModalBottomSheet(onDismissRequest = { reactionTarget = null; messageActions = null }, containerColor = chatSurface) {
            if (reactionsOnly) Text("Реакция", Modifier.padding(horizontal = 20.dp), fontWeight = FontWeight.SemiBold)
            if (!saved && target.remoteId > 0) {
                val currentReaction = messages.firstOrNull { it.remoteId == target.remoteId }?.myReaction
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("👍", "❤️", "😂", "😮", "😢", "🔥").forEach { emoji ->
                        TextButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                reactionTarget = null
                                messageActions = null
                                scope.launch {
                                    try {
                                        directory.setReaction(target.remoteId, emoji.takeUnless { it == currentReaction })
                                        syncRequests.trySend(Unit)
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: DirectoryException) {
                                        sendError = if (failure.status == 404 && failure.message == "Not Found")
                                            "Реакции появятся после обновления сервера." else failure.message
                                    }
                                    catch (_: Exception) { sendError = "Не удалось изменить реакцию. Попробуй ещё раз." }
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (emoji == currentReaction) Yellow.copy(alpha = .22f) else Color.Transparent)
                        ) { Text(emoji, fontSize = 19.sp) }
                    }
                }
            }
            if (!reactionsOnly) {
            Text("Сообщение",modifier=Modifier.padding(horizontal=20.dp,vertical=6.dp),fontSize=17.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
            ListItem(headlineContent={Text("Ответить")},leadingContent={Text("↩",fontSize=22.sp)},modifier=Modifier.clickable { replyingTo=target; editingMessage=null; messageActions=null },colors=ListItemDefaults.colors(containerColor=Color.Transparent))
            ListItem(headlineContent={Text("Копировать")},leadingContent={Text("▢",fontSize=20.sp)},modifier=Modifier.clickable {
                if(target.text.isNotBlank()) { val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager; clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Сообщение",target.text)) }
                messageActions=null
            },colors=ListItemDefaults.colors(containerColor=Color.Transparent))
            target.attachment?.takeIf { Build.VERSION.SDK_INT >= 29 && SalomPhotos.galleryMime(it.mime) }?.let { photo ->
                ListItem(headlineContent = { Text("Сохранить фото") }, modifier = Modifier.clickable {
                    messageActions = null
                    scope.launch {
                        try {
                            media.savePhoto(photo)
                            android.widget.Toast.makeText(context, "Фото сохранено в галерею SALOM", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { sendError = "Не удалось сохранить фото" }
                    }
                }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
            if(target.mine && target.attachment==null) ListItem(headlineContent={Text("Редактировать")},leadingContent={Text("✎",fontSize=20.sp)},modifier=Modifier.clickable { editingMessage=target; replyingTo=null; draft=target.text; selected=null; uploaded=null; messageActions=null },colors=ListItemDefaults.colors(containerColor=Color.Transparent))
            ListItem(headlineContent={Text("Удалить",color=MaterialTheme.colorScheme.error)},leadingContent={Text("⌫",fontSize=20.sp,color=MaterialTheme.colorScheme.error)},modifier=Modifier.clickable { confirmDeleteMessage=target; messageActions=null },colors=ListItemDefaults.colors(containerColor=Color.Transparent))
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showPeerProfile && activeCall == null) {
        PeerProfileDialog(person, directory, peerOnline, onDismiss = { showPeerProfile = false })
    }
    if (activeCall == null) previewAttachment?.let { attachment ->
        AttachmentViewer(attachment, media, onClose = { previewAttachment = null })
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

    if (call != null && (!minimizedCall || call.state == "incoming" || call.state == "failed")) key(call.callId) {
        val status = if (call.state == "reconnecting") "Восстанавливаем соединение…" else callError ?: when (call.state) {
            "incoming" -> if (call.video) "Входящий видеозвонок" else "Входящий аудиозвонок"
            "ringing" -> "Вызов…"
            "connecting" -> "Подключение…"
            "connected" -> if (call.video) "Видеозвонок" else "Аудиозвонок"
            "failed" -> "Связь не установлена"
            else -> "Звонок"
        }
        val engine = callEngine
        val incoming = call.state == "incoming"
        val endAction = when (call.state) {
            "incoming" -> "decline"
            "ringing" -> "cancel"
            else -> "end"
        }
        CallScreen(
            person.copy(name = call.peerName), status, callDuration, engine,
            showVideo = engineReady && call.video && engine != null,
            muted = callMuted, speaker = callSpeaker, camera = callVideoEnabled,
            actions = CallScreenActions(
                minimize = if (incoming || call.state == "failed") null else ({ minimizedCall = true }),
                end = { endCall(endAction) },
                answer = if (incoming) ({ acceptIncomingCall(call) }) else null,
                answerVideo = if (incoming && call.video) ({ acceptIncomingCall(call, true) }) else null,
                mic = engine?.let { { callMuted = !callMuted; it.setMuted(callMuted) } },
                speaker = engine?.let { { callSpeaker = !callSpeaker; it.setSpeaker(callSpeaker) } },
                camera = engine?.takeIf { it.hasLocalVideo }?.let {
                    { callVideoEnabled = !callVideoEnabled; it.setVideoEnabled(callVideoEnabled) }
                },
                switchCamera = engine?.takeIf { it.canSwitchCamera }?.let {
                    { it.switchCamera { callError = "Не удалось переключить камеру" } }
                }
            )
        )
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
internal fun AttachmentBubble(
    attachment: Attachment, media: MediaFiles, contentColor: Color, maxWidth: androidx.compose.ui.unit.Dp,
    onLongPress: (() -> Unit)?, onPreview: ((Attachment) -> Unit)?, photoMeta: (@Composable () -> Unit)?
) {
    val scope = rememberCoroutineScope()
    var bitmap by remember(attachment.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember(attachment.id) { mutableStateOf(false) }
    var error by remember(attachment.id) { mutableStateOf<String?>(null) }
    var openJob by remember(attachment.id) { mutableStateOf<Job?>(null) }
    var downloadProgress by remember(attachment.id) { mutableFloatStateOf(0f) }
    var attempt by remember { mutableIntStateOf(0) }
    val preview = attachment.previewKind()
    val photo = preview == PreviewKind.PHOTO
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
    val open: () -> Unit = open@{
        if (preview != null && onPreview != null) {
            onPreview(attachment)
            return@open
        }
        if (loading) return@open
        if (photo && error != null && bitmap == null) attempt++ else {
            openJob = scope.launch {
                loading = true; error = null; downloadProgress = 0f
                try { media.open(attachment) { downloadProgress = it } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = (failure as? DirectoryException)?.message ?: "Не удалось открыть файл" }
                finally { loading = false; openJob = null }
            }
        }
    }
    Column(Modifier.widthIn(max = minOf(maxWidth, 248.dp))
        .combinedClickable(role = Role.Button, onLongClick = onLongPress, onClick = open)
        .padding(bottom = if (photo) 0.dp else 3.dp)) {
        val image = bitmap
        if (image != null) {
            val ratio = image.width.toFloat() / image.height
            val width = minOf(maxWidth, 248.dp, 300.dp * ratio)
            Box(Modifier.size(width = width, height = width / ratio).clip(RoundedCornerShape(13.dp))) {
                Image(image.asImageBitmap(), contentDescription = attachment.name, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit)
                photoMeta?.let { Box(Modifier.align(Alignment.BottomEnd)) { it() } }
            }
        }
        else Row(Modifier.padding(horizontal = if (photo) 10.dp else 0.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = color)
            else Text(if (attachment.mime.startsWith("video/")) "▶" else "↓", fontSize = 24.sp, lineHeight = 28.sp, color = color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.widthIn(max = 185.dp)) { Text(attachment.name, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = color); Text(fileSize(attachment.size), fontSize = 10.sp, lineHeight = 13.sp, color = color.copy(alpha = .65f)) }
        }
        if (loading && openJob != null) {
            LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth(), color = color)
            TextButton(onClick = { openJob?.cancel() }) { Text("Отмена · ${(downloadProgress * 100).toInt()}%", fontSize = 11.sp) }
        }
        if (photo && image == null) photoMeta?.let { Box(Modifier.align(Alignment.End)) { it() } }
        error?.let { Text(it, fontSize = 10.sp, color = color, modifier = Modifier.padding(horizontal = if (photo) 10.dp else 0.dp)) }
    }
}

@Composable
private fun ChatHeaderAction(kind: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp, 48.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        ChatGlyph(kind, Color.White.copy(alpha = if (enabled) 1f else .4f))
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
            "switch_camera" -> {
                drawRoundRect(color, Offset(w * .10f, h * .28f), Size(w * .80f, h * .52f),
                    androidx.compose.ui.geometry.CornerRadius(w * .10f), style = Stroke(line))
                drawPath(Path().apply {
                    moveTo(w*.31f,h*.27f); lineTo(w*.40f,h*.15f); lineTo(w*.60f,h*.15f); lineTo(w*.69f,h*.27f)
                    moveTo(w*.67f,h*.47f); lineTo(w*.70f,h*.61f); lineTo(w*.55f,h*.59f)
                    moveTo(w*.33f,h*.64f); lineTo(w*.30f,h*.50f); lineTo(w*.45f,h*.52f)
                }, color, style = Stroke(line))
                drawArc(color, 195f, 130f, false, Offset(w*.33f,h*.39f), Size(w*.34f,h*.32f), style = Stroke(line))
                drawArc(color, 15f, 130f, false, Offset(w*.33f,h*.39f), Size(w*.34f,h*.32f), style = Stroke(line))
            }
            "video" -> {
                drawRoundRect(color, Offset(w * .08f, h * .24f), Size(w * .57f, h * .52f),
                    androidx.compose.ui.geometry.CornerRadius(w * .10f), style = Stroke(line))
                drawPath(Path().apply {
                    moveTo(w * .65f, h * .42f); lineTo(w * .91f, h * .28f)
                    lineTo(w * .91f, h * .72f); lineTo(w * .65f, h * .58f)
                }, color, style = Stroke(line))
            }
            "phone" -> drawPath(Path().apply {
                moveTo(w * .19f, h * .10f)
                quadraticTo(w * .10f, h * .11f, w * .12f, h * .25f)
                cubicTo(w * .19f, h * .59f, w * .49f, h * .87f, w * .82f, h * .89f)
                quadraticTo(w * .91f, h * .90f, w * .91f, h * .80f)
                lineTo(w * .90f, h * .69f); lineTo(w * .68f, h * .60f)
                lineTo(w * .56f, h * .71f)
                quadraticTo(w * .40f, h * .64f, w * .29f, h * .44f)
                lineTo(w * .40f, h * .33f); lineTo(w * .32f, h * .11f); close()
            }, color)
            "mic" -> { drawRoundRect(color, Offset(w*.36f,h*.09f), Size(w*.28f,h*.51f), androidx.compose.ui.geometry.CornerRadius(w*.14f)); drawPath(Path().apply { moveTo(w*.22f,h*.42f); cubicTo(w*.22f,h*.88f,w*.78f,h*.88f,w*.78f,h*.42f) },color,style=Stroke(line)); drawLine(color,Offset(w*.5f,h*.76f),Offset(w*.5f,h*.94f),line); drawLine(color,Offset(w*.33f,h*.94f),Offset(w*.67f,h*.94f),line) }
            "speaker" -> {
                drawPath(Path().apply {
                    moveTo(w * .09f, h * .38f); lineTo(w * .29f, h * .38f); lineTo(w * .52f, h * .19f)
                    lineTo(w * .52f, h * .81f); lineTo(w * .29f, h * .62f); lineTo(w * .09f, h * .62f); close()
                }, color)
                drawArc(color, -52f, 104f, false, Offset(w * .37f, h * .29f), Size(w * .36f, h * .42f), style = Stroke(line))
                drawArc(color, -52f, 104f, false, Offset(w * .31f, h * .12f), Size(w * .60f, h * .76f), style = Stroke(line))
            }
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
