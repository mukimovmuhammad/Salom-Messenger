package com.mukimov.salom

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Audio-only peer connection. Media stays between the two devices; only SDP/ICE is relayed by SALOM. */
internal class CallEngine(
    context: Context,
    private val callId: String,
    private val peerId: String,
    private val peerName: String,
    private val directory: DirectoryClient,
    private val scope: CoroutineScope,
    private val onState: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val previousMode = audioManager.mode
    private val previousSpeaker = audioManager.isSpeakerphoneOn
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var peer: PeerConnection? = null
    private val pendingIce = ConcurrentLinkedQueue<IceCandidate>()
    @Volatile private var remoteDescriptionReady = false
    private var closed = false

    suspend fun prepare() {
        if (peer != null) return
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
        val factory = CallWebRtc.factory(app)
        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = factory.createPeerConnection(configuration, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onState(when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> "connected"
                    PeerConnection.IceConnectionState.CHECKING -> "connecting"
                    PeerConnection.IceConnectionState.DISCONNECTED -> "reconnecting"
                    PeerConnection.IceConnectionState.FAILED -> "failed"
                    PeerConnection.IceConnectionState.CLOSED -> "ended"
                    else -> "connecting"
                })
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                val data = JSONObject().put("sdp_mid", candidate.sdpMid).put("sdp_mline_index", candidate.sdpMLineIndex).put("candidate", candidate.sdp)
                scope.launch { runCatching { directory.signalCall(callId, "ice", data) } }
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
                (transceiver.receiver.track() as? AudioTrack)?.setEnabled(true)
            }
        }) ?: error("Не удалось создать аудиозвонок")
        source = factory.createAudioSource(MediaConstraints())
        track = factory.createAudioTrack("salom-audio", source).also { it.setEnabled(true) }
        peer?.addTrack(track, listOf("salom-call"))
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        CallForegroundService.start(app, callId, peerId, peerName)
    }

    fun createOffer() {
        val connection = checkNotNull(peer)
        connection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                connection.setLocalDescription(object : SdpObserverAdapter() {}, description)
                scope.launch {
                    runCatching { directory.signalCall(callId, "offer", JSONObject().put("type", description.type.canonicalForm()).put("sdp", description.description)) }
                        .onFailure { onState("failed") }
                }
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) { onState("failed") }
            override fun onSetFailure(error: String) { onState("failed") }
        }, MediaConstraints())
    }

    fun receive(kind: String, payload: JSONObject) {
        val connection = peer ?: return
        when (kind) {
            "offer" -> {
                val remote = SessionDescription(SessionDescription.Type.OFFER, payload.optString("sdp"))
                connection.setRemoteDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() { remoteDescriptionReady = true; drainIce(connection); createAnswer() }
                    override fun onSetFailure(error: String) { onState("failed") }
                }, remote)
            }
            "answer" -> connection.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() { remoteDescriptionReady = true; drainIce(connection) }
            },
                SessionDescription(SessionDescription.Type.ANSWER, payload.optString("sdp")))
            "ice" -> {
                val mid = payload.optString("sdp_mid").takeIf(String::isNotBlank)
                val candidate = IceCandidate(mid, payload.optInt("sdp_mline_index"), payload.optString("candidate"))
                if (remoteDescriptionReady) connection.addIceCandidate(candidate) else pendingIce.add(candidate)
            }
        }
    }

    private fun drainIce(connection: PeerConnection) {
        while (true) connection.addIceCandidate(pendingIce.poll() ?: break)
    }

    private fun createAnswer() {
        val connection = checkNotNull(peer)
        connection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                connection.setLocalDescription(object : SdpObserverAdapter() {}, description)
                scope.launch {
                    runCatching { directory.signalCall(callId, "answer", JSONObject().put("type", description.type.canonicalForm()).put("sdp", description.description)) }
                        .onFailure { onState("failed") }
                }
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) { onState("failed") }
            override fun onSetFailure(error: String) { onState("failed") }
        }, MediaConstraints())
    }

    fun setMuted(muted: Boolean) { track?.setEnabled(!muted) }
    fun setSpeaker(enabled: Boolean) { audioManager.isSpeakerphoneOn = enabled }

    fun close() {
        if (closed) return
        closed = true
        runCatching { track?.setEnabled(false); track?.dispose(); source?.dispose(); peer?.close(); peer?.dispose() }
        track = null; source = null; peer = null
        audioManager.mode = previousMode
        audioManager.isSpeakerphoneOn = previousSpeaker
        CallForegroundService.stop(app)
    }
}

private open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) = Unit
    override fun onSetFailure(error: String) = Unit
}

private object CallWebRtc {
    @Volatile private var instance: PeerConnectionFactory? = null
    fun factory(context: Context): PeerConnectionFactory = instance ?: synchronized(this) {
        instance ?: run {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            PeerConnectionFactory.builder().createPeerConnectionFactory().also { instance = it }
        }
    }
}
