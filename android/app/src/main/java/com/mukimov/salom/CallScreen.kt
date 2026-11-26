package com.mukimov.salom

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import kotlin.math.roundToInt

internal data class CallScreenActions(
    val minimize: (() -> Unit)?,
    val end: () -> Unit,
    val answer: (() -> Unit)?,
    val answerVideo: (() -> Unit)?,
    val mic: (() -> Unit)?,
    val speaker: (() -> Unit)?,
    val camera: (() -> Unit)?,
    val switchCamera: (() -> Unit)?
)

@Composable
internal fun CallScreen(
    person: Person, status: String, duration: String?, engine: CallEngine?,
    showVideo: Boolean, muted: Boolean, speaker: Boolean, camera: Boolean,
    actions: CallScreenActions
) {
    val panel = Color(0xDB25242A)
    var controlsVisible by remember { mutableStateOf(true) }
    var remoteHasFrame by remember(engine) { mutableStateOf(false) }
    val canHide = showVideo && actions.answer == null && duration != null
    val showControls = !canHide || controlsVisible
    Dialog(
        onDismissRequest = { actions.minimize?.invoke() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false,
            dismissOnBackPress = actions.minimize != null, decorFitsSystemWindows = false)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color(0xFF201D27), Color(0xFF101116), Color(0xFF171418)
        )))) {
            val avatarSize = minOf(150.dp, maxHeight / 3, maxWidth / 2).value.toInt().coerceAtLeast(1)
            if (showVideo && engine != null) {
                AndroidView(factory = { ctx ->
                    CallVideoView(ctx, mirror = false, onFirstFrame = { remoteHasFrame = true })
                        .also { engine.attachRemoteRenderer(it) }
                }, modifier = Modifier.fillMaxSize(), onRelease = {
                    engine.attachRemoteRenderer(null)
                    it.release()
                })
            }
            if (!showVideo || !remoteHasFrame) {
                Box(Modifier.align(Alignment.Center).clip(CircleShape)
                    .background(Color(0xFF2C2731)).padding(8.dp)) {
                    Avatar(person, avatarSize)
                }
            }
            if (canHide) {
                Box(Modifier.fillMaxSize().clickable(
                    onClickLabel = if (showControls) "Скрыть управление" else "Показать управление",
                    onClick = { controlsVisible = !controlsVisible }))
            }
            if (showControls) {
                if (remoteHasFrame && showVideo) Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color(0xBF100F16), .28f to Color.Transparent,
                        .68f to Color.Transparent, 1f to Color(0xD9100F16))))
                Box(Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 12.dp, vertical = 10.dp)) {
                    actions.minimize?.let { minimize ->
                        IconButton(onClick = minimize, modifier = Modifier.align(Alignment.TopStart)
                            .semantics { contentDescription = "Свернуть звонок" }) {
                            ChatGlyph("back", Color.White)
                        }
                    }
                    Column(Modifier.align(Alignment.TopCenter).padding(horizontal = 50.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(person.name, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        duration?.let { Text(it, color = Yellow, fontSize = 16.sp) }
                        Text(status, color = Color(0xFFE0DDE5), fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
            } else if (status.startsWith("Восстанавливаем")) {
                Text(status, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp)
                    .clip(RoundedCornerShape(16.dp)).background(panel).padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White, fontSize = 13.sp)
            }
            if (showVideo && engine != null && camera && engine.hasLocalVideo) {
                DragPreview(engine, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 12.dp, top = 92.dp, end = 12.dp, bottom = if (showControls) 94.dp else 12.dp))
            }
            if (showControls) {
                Row(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 10.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (actions.answer != null) {
                        CallControl("phone", "Отклонить", Color(0xFFC83448), hangup = true, onClick = actions.end)
                        CallControl("phone", if (actions.answerVideo == null) "Ответить" else "Аудио",
                            Color(0xFF177A4B), onClick = actions.answer)
                        actions.answerVideo?.let { CallControl("video", "Ответить с видео", Color(0xFF177A4B), onClick = it) }
                    } else {
                        actions.mic?.let { CallControl("mic", "Микрофон", panel, selected = muted, off = muted, onClick = it) }
                        actions.camera?.let { CallControl("video", "Камера", panel, selected = !camera, off = !camera, onClick = it) }
                        actions.speaker?.let { CallControl("speaker", "Динамик", panel, selected = speaker, onClick = it) }
                        if (camera) actions.switchCamera?.let { CallControl("switch_camera", "Сменить камеру", panel, onClick = it) }
                        CallControl("phone", "Завершить", Color(0xFFC83448), hangup = true, onClick = actions.end)
                    }
                }
            }
        }
    }
}

@Composable
private fun CallControl(kind: String, label: String, background: Color,
    selected: Boolean = false, off: Boolean = false, hangup: Boolean = false, onClick: () -> Unit) {
    val iconColor = if (selected) Ink else Color.White
    Box(Modifier.size(48.dp).clip(CircleShape).background(if (selected) Yellow else background)
        .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Box(Modifier.rotate(if (hangup) 135f else 0f)) { ChatGlyph(kind, iconColor) }
        if (off) Box(Modifier.rotate(-42f).size(width = 24.dp, height = 2.dp).background(iconColor))
    }
}

@Composable
private fun DragPreview(engine: CallEngine, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val width = minOf(maxWidth, 104.dp)
        val height = minOf(maxHeight, 144.dp)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val travelX = with(density) { (maxWidth - width).toPx() }
        val travelY = with(density) { (maxHeight - height).toPx() }
        var x by remember(engine) { mutableFloatStateOf(1f) }
        var y by remember(engine) { mutableFloatStateOf(0f) }
        val shape = RoundedCornerShape(14.dp)
        AndroidView(factory = { ctx -> CallVideoView(ctx, mirror = engine.localCameraMirrored).also { engine.attachLocalRenderer(it) } },
            modifier = Modifier.offset { IntOffset((x * travelX).roundToInt(), (y * travelY).roundToInt()) }
                .size(width, height).clip(shape).border(1.dp, Color.White.copy(alpha = .35f), shape)
                .pointerInput(travelX, travelY) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        if (travelX > 0f) x = (x + delta.x / travelX).coerceIn(0f, 1f)
                        if (travelY > 0f) y = (y + delta.y / travelY).coerceIn(0f, 1f)
                    }
                },
            onRelease = { engine.attachLocalRenderer(null); it.release() })
    }
}

// A TextureView participates in the dialog's composition, so controls and the preview can layer above video.
internal class CallVideoView(context: Context, mirror: Boolean, private val onFirstFrame: (() -> Unit)? = null) :
    TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = EglRenderer("SalomCallVideo")
    private var ready = false
    private var released = false

    init {
        isOpaque = false
        renderer.init(CallWebRtc.egl.eglBaseContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        renderer.setMirror(mirror)
        surfaceTextureListener = this
    }

    fun setMirror(mirror: Boolean) { renderer.setMirror(mirror) }
    override fun onFrame(frame: VideoFrame) { renderer.onFrame(frame) }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        renderer.createEglSurface(surface)
        onSurfaceTextureSizeChanged(surface, width, height)
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (!released && height > 0) renderer.setLayoutAspectRatio(width.toFloat() / height)
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (!released && !ready) {
            ready = true
            onFirstFrame?.invoke()
        }
    }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (!released) {
            val done = CountDownLatch(1)
            renderer.releaseEglSurface { done.countDown() }
            org.webrtc.ThreadUtils.awaitUninterruptibly(done)
        }
        return true
    }
    fun release() {
        if (released) return
        released = true
        renderer.release()
    }
}
