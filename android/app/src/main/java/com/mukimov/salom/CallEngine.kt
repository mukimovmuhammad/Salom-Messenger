package com.mukimov.salom

import android.content.Context
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.CameraVideoCapturer
import org.webrtc.RtpParameters
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Peer connection with optional, explicitly enabled video. Media stays between the two devices; only SDP/ICE is relayed by SALOM. */
internal class CallEngine(
    context: Context,
    private val callId: String,
    private val peerId: String,
    private val peerName: String,
    private val directory: DirectoryClient,
    private val scope: CoroutineScope,
    private val video: Boolean,
    private val caller: Boolean,
    private val onState: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val previousMode = audioManager.mode
    private val previousSpeaker = audioManager.isSpeakerphoneOn
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var camera: CameraVideoCapturer? = null
    var localCameraMirrored = true
        private set
    var canSwitchCamera = false
        private set
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    val hasLocalVideo: Boolean get() = videoTrack != null
    private var remoteVideo: VideoTrack? = null
    private var localRenderer: CallVideoView? = null
    private var remoteRenderer: CallVideoView? = null
    private var peer: PeerConnection? = null
    private val pendingIce = ConcurrentLinkedQueue<IceCandidate>()
    private var offerStarted = false
    private var lastRemoteSdp: String? = null
    private var lastLocalSdp: SessionDescription? = null
    private var negotiationInProgress = false
    @Volatile private var wasConnected = false
    private var recovery: Job? = null
    private var stats: Job? = null
    @Volatile private var remoteDescriptionReady = false
    @Volatile private var closed = false
    private fun reportState(state: String) {
        scope.launch(Dispatchers.Main.immediate) {
            if (!closed) {
                trace("media=$state")
                if (state == "connected") CallSessionManager.connected(callId)
                onState(state)
            }
        }
    }

    private fun trace(event: String) {
        android.util.Log.i("SalomCall", "call=$callId $event")
    }

    suspend fun prepare() {
        if (peer != null) return
        CallForegroundService.start(app, callId, peerId, peerName, video)
        val iceServers = directory.callConfig().let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val urlsJson = item.optJSONArray("urls") ?: return@mapNotNull null
                val urls = (0 until urlsJson.length()).mapNotNull { urlsJson.optString(it).takeIf(String::isNotBlank) }
                if (urls.isEmpty()) return@mapNotNull null
                PeerConnection.IceServer.builder(urls).apply {
                    item.optString("username").takeIf(String::isNotBlank)?.let { setUsername(it) }
                    item.optString("credential").takeIf(String::isNotBlank)?.let { setPassword(it) }
                }.createIceServer()
            }
        }
        check(!closed) { "Звонок уже завершён" }
        trace("platform=${android.os.Build.VERSION.SDK_INT} ice_servers=${iceServers.size} video=$video")
        val factory = CallWebRtc.factory(app)
        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = factory.createPeerConnection(configuration, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = trace("signaling=$state")
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                scope.launch(Dispatchers.Main) { handleIceState(state) }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = trace("gathering=$state")
            override fun onIceCandidate(candidate: IceCandidate) {
                val data = JSONObject().put("sdp_mid", candidate.sdpMid ?: JSONObject.NULL).put("sdp_mline_index", candidate.sdpMLineIndex).put("candidate", candidate.sdp)
                scope.launch {
                    if (closed) return@launch
                    try { directory.signalCall(callId, "ice", data) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { trace("ice_send_failed") }
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {
                (receiver.track() as? AudioTrack)?.setEnabled(true)
            }
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                val received = transceiver.receiver.track()
                scope.launch(Dispatchers.Main) {
                    if (closed) return@launch
                    if (received is VideoTrack) {
                        remoteRenderer?.let { remoteVideo?.removeSink(it) }
                        remoteVideo = received
                        remoteRenderer?.let(received::addSink)
                    }
                }
            }
        }) ?: error("Не удалось создать аудиозвонок")
        source = factory.createAudioSource(MediaConstraints())
        track = factory.createAudioTrack("salom-audio", source).also { it.setEnabled(true) }
        peer?.addTrack(track, listOf("salom-call"))?.let { sender ->
            val parameters = sender.parameters
            parameters.encodings.forEach { it.bitratePriority = 2.0 }
            sender.setParameters(parameters)
        }
        if (video) {
            runCatching {
                val enumerator = Camera2Enumerator(app)
                val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull()
                    ?: error("Камера не найдена")
                localCameraMirrored = enumerator.isFrontFacing(cameraName)
                camera = enumerator.createCapturer(cameraName, null) ?: error("Не удалось открыть камеру")
                canSwitchCamera = enumerator.deviceNames.size > 1
                videoSource = factory.createVideoSource(false)
                textureHelper = SurfaceTextureHelper.create("SalomCamera", CallWebRtc.egl.eglBaseContext)
                camera!!.initialize(textureHelper, app, videoSource!!.capturerObserver)
                camera!!.startCapture(1280, 720, 30)
                videoTrack = factory.createVideoTrack("salom-video", videoSource).also { it.setEnabled(true) }
                peer?.addTrack(videoTrack, listOf("salom-call"))?.let { sender ->
                    val parameters = sender.parameters
                    parameters.degradationPreference = RtpParameters.DegradationPreference.BALANCED
                    parameters.encodings.forEach { it.maxBitrateBps = 2_000_000; it.maxFramerate = 30 }
                    if (!sender.setParameters(parameters)) trace("video_limits_unavailable")
                }
                localRenderer?.let { videoTrack?.addSink(it) }
            }.onFailure { android.util.Log.w("SalomCall", "Camera setup failed", it); reportState("video_unavailable") }
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
        else audioManager.isSpeakerphoneOn = false
    }

    fun createOffer() {
        val connection = peer ?: return
        if (closed || offerStarted) return
        offerStarted = true
        createOffer(connection, restarting = false)
    }

    private fun handleIceState(state: PeerConnection.IceConnectionState) {
        if (closed) return
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                wasConnected = true
                recovery?.cancel()
                recovery = null
                reportState("connected")
                stats?.cancel()
                stats = scope.launch {
                    delay(2_000)
                    peer?.getStats { report ->
                        val values = report.statsMap.values
                        val codecs = values.filter { it.type == "codec" }.mapNotNull {
                            (it.members["mimeType"] as? String)?.takeIf { kind -> kind.startsWith("video/") || kind == "audio/opus" }
                        }.distinct()
                        val pairId = values.firstOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId") as? String
                        val pair = pairId?.let { report.statsMap[it] }
                        val candidate = pair?.members?.get("localCandidateId") as? String
                        val route = candidate?.let { report.statsMap[it]?.members?.get("candidateType") }
                        val rtt = (pair?.members?.get("currentRoundTripTime") as? Number)?.toDouble()
                        if (!closed) trace("quality codecs=$codecs route=$route rtt_ms=${rtt?.times(1_000)?.toInt()}")
                    }
                }
            }
            PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.FAILED -> {
                if (!wasConnected) {
                    if (state == PeerConnection.IceConnectionState.FAILED) reportState("failed")
                    return
                }
                reportState("reconnecting")
                if (!caller || recovery?.isActive == true) return
                recovery = scope.launch {
                    delay(2_500)
                    repeat(3) {
                        val pc = peer ?: return@launch
                        if (closed) return@launch
                        if (!negotiationInProgress) when (pc.signalingState()) {
                            PeerConnection.SignalingState.STABLE -> {
                                pc.restartIce()
                                createOffer(pc, restarting = true)
                            }
                            PeerConnection.SignalingState.HAVE_LOCAL_OFFER -> lastLocalSdp?.let { sendSdp(it, retryable = true) }
                            else -> Unit
                        }
                        delay(6_000)
                    }
                }
            }
            PeerConnection.IceConnectionState.CHECKING -> reportState(if (wasConnected) "reconnecting" else "connecting")
            PeerConnection.IceConnectionState.CLOSED -> reportState("ended")
            else -> Unit
        }
    }

    private fun createOffer(connection: PeerConnection, restarting: Boolean) {
        negotiationInProgress = true
        connection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                publishLocalSdp(connection, description)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) { negotiationFailed(restarting) }
            override fun onSetFailure(error: String) { negotiationFailed(restarting) }
        }, MediaConstraints())
    }

    private fun negotiationFailed(retryable: Boolean) {
        scope.launch(Dispatchers.Main) {
            negotiationInProgress = false
            if (!retryable) reportState("failed")
            else if (!closed) trace("negotiation_retryable")
        }
    }

    fun receive(kind: String, payload: JSONObject) {
        val connection = peer ?: return
        when (kind) {
            "offer", "answer" -> {
                if (closed || negotiationInProgress || (kind == "answer") != caller) return
                val sdp = payload.optString("sdp")
                if (sdp.isBlank()) return
                if (sdp == lastRemoteSdp) {
                    if (kind == "offer") lastLocalSdp?.let { answer -> scope.launch { sendSdp(answer, retryable = true) } }
                    return
                }
                if (kind == "answer" && connection.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
                negotiationInProgress = true
                remoteDescriptionReady = false
                val type = if (kind == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
                val remote = SessionDescription(type, sdp)
                connection.setRemoteDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        scope.launch(Dispatchers.Main) {
                            if (closed || peer !== connection) return@launch
                            lastRemoteSdp = sdp
                            remoteDescriptionReady = true
                            trace("remote_sdp=$kind")
                            drainIce(connection)
                            if (kind == "offer") createAnswer() else negotiationInProgress = false
                        }
                    }
                    override fun onSetFailure(error: String) { negotiationFailed(wasConnected) }
                }, remote)
            }
            "ice" -> {
                val mid = if (payload.isNull("sdp_mid")) null else payload.optString("sdp_mid").takeIf(String::isNotBlank)
                val candidate = IceCandidate(mid, payload.optInt("sdp_mline_index"), payload.optString("candidate"))
                if ((!remoteDescriptionReady || !connection.addIceCandidate(candidate)) && pendingIce.size < 128) pendingIce.add(candidate)
            }
        }
    }

    private fun drainIce(connection: PeerConnection) {
        while (true) connection.addIceCandidate(pendingIce.poll() ?: break)
    }

    private fun createAnswer() {
        val connection = peer ?: return
        connection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                publishLocalSdp(connection, description)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) { negotiationFailed(wasConnected) }
            override fun onSetFailure(error: String) { negotiationFailed(wasConnected) }
        }, MediaConstraints())
    }

    private fun publishLocalSdp(connection: PeerConnection, description: SessionDescription) {
        scope.launch(Dispatchers.Main) {
            if (closed || peer !== connection) return@launch
            connection.setLocalDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    scope.launch(Dispatchers.Main) {
                        if (closed) return@launch
                        lastLocalSdp = description
                        negotiationInProgress = false
                        sendSdp(description, retryable = wasConnected)
                    }
                }
                override fun onSetFailure(error: String) { negotiationFailed(wasConnected) }
            }, description)
        }
    }

    private suspend fun sendSdp(description: SessionDescription, retryable: Boolean) {
        if (closed) return
        val kind = description.type.canonicalForm()
        try {
            directory.signalCall(callId, kind, JSONObject().put("type", kind).put("sdp", description.description))
            trace("local_sdp=$kind")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (retryable) trace("sdp_send_retryable") else reportState("failed")
        }
    }

    fun setMuted(muted: Boolean) { track?.setEnabled(!muted) }
    fun setSpeaker(enabled: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                if (enabled) audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let(audioManager::setCommunicationDevice)
                else audioManager.clearCommunicationDevice()
            } else audioManager.isSpeakerphoneOn = enabled
        }.onFailure { android.util.Log.w("SalomCall", "Audio route change failed", it) }
    }
    fun setVideoEnabled(enabled: Boolean) { videoTrack?.setEnabled(enabled) }
    fun switchCamera(onError: () -> Unit) {
        if (closed || !canSwitchCamera) return
        camera?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                scope.launch(Dispatchers.Main) {
                    localCameraMirrored = isFrontCamera
                    localRenderer?.setMirror(isFrontCamera)
                }
            }
            override fun onCameraSwitchError(error: String) { scope.launch(Dispatchers.Main) { if (!closed) onError() } }
        })
    }
    fun attachLocalRenderer(local: CallVideoView?) {
        if (closed) return
        localRenderer?.let { videoTrack?.removeSink(it) }
        localRenderer = local
        local?.let { videoTrack?.addSink(it) }
    }
    fun attachRemoteRenderer(remote: CallVideoView?) {
        if (closed) return
        remoteRenderer?.let { remoteVideo?.removeSink(it) }
        remoteRenderer = remote
        remote?.let { remoteVideo?.addSink(it) }
    }

    fun close() {
        if (closed) return
        attachLocalRenderer(null)
        attachRemoteRenderer(null)
        closed = true
        recovery?.cancel()
        stats?.cancel()
        runCatching { camera?.stopCapture() }
        runCatching { camera?.dispose() }
        runCatching { textureHelper?.dispose() }
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        camera = null; textureHelper = null; videoTrack = null; videoSource = null; track = null; source = null; peer = null
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
            else audioManager.isSpeakerphoneOn = previousSpeaker
            audioManager.mode = previousMode
        }
        runCatching { CallForegroundService.stop(app) }
    }
}

private open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) = Unit
    override fun onSetFailure(error: String) = Unit
}

internal object CallWebRtc {
    @Volatile private var instance: PeerConnectionFactory? = null
    val egl: EglBase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { EglBase.create() }
    fun factory(context: Context): PeerConnectionFactory = instance ?: synchronized(this) {
        instance ?: run {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            val audio = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
                .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
                .createAudioDeviceModule()
            try {
                PeerConnectionFactory.builder().setAudioDeviceModule(audio)
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                    .createPeerConnectionFactory().also { instance = it }
            } finally {
                audio.release()
            }
        }
    }
}
