package com.mukimov.salom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.webrtc.SurfaceViewRenderer

internal data class CallScreenActions(
    val minimize: (() -> Unit)?,
    val end: () -> Unit,
    val answer: (() -> Unit)?,
    val answerVideo: (() -> Unit)?,
    val mic: (() -> Unit)?,
    val speaker: (() -> Unit)?,
    val camera: (() -> Unit)?
)

@Composable
internal fun CallScreen(
    person: Person, status: String, duration: String?, wallpaperId: String,
    engine: CallEngine?, showVideo: Boolean, muted: Boolean, speaker: Boolean, camera: Boolean,
    actions: CallScreenActions
) {
    val panel = Color(0xFF20232B)
    Dialog(
        onDismissRequest = { actions.minimize?.invoke() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false,
            dismissOnBackPress = actions.minimize != null, decorFitsSystemWindows = false)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF181D29))
            .paint(painterResource(chatWallpaperResource(wallpaperId)), contentScale = ContentScale.Crop)) {
            val avatarSize = minOf(164.dp, maxHeight / 3, maxWidth / 2).value.toInt().coerceAtLeast(1)
            if (showVideo && engine != null) {
                CallVideo(engine, camera, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 110.dp, bottom = 125.dp, start = 12.dp, end = 12.dp))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                Color(0xC9101522), Color(0x24101522), Color(0xE5101522)
            ))))
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(48.dp)) {
                    actions.minimize?.let { minimize ->
                        IconButton(onClick = minimize, modifier = Modifier.align(Alignment.CenterStart)
                            .semantics { contentDescription = "Свернуть звонок" }) {
                            ChatGlyph("back", Color.White)
                        }
                    }
                    Text("Звонок SALOM", Modifier.align(Alignment.Center), fontSize = 13.sp, color = Color.White.copy(alpha = .8f))
                }
                Text(person.name, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(status, Modifier.padding(top = 5.dp), color = Color(0xFFE0E4EB), fontSize = 14.sp,
                    textAlign = TextAlign.Center)
                duration?.let { Text(it, Modifier.padding(top = 4.dp), color = Yellow, fontSize = 18.sp) }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (!showVideo) Box(Modifier.clip(CircleShape).background(Yellow.copy(alpha = .18f)).padding(7.dp)) {
                        Avatar(person, avatarSize)
                    }
                }
                if (actions.answer != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CallControl("phone", "Отклонить", Color(0xFFD6384B), hangup = true, onClick = actions.end)
                        CallControl("phone", if (actions.answerVideo == null) "Ответить" else "Аудио",
                            Color(0xFF177A4B), onClick = actions.answer)
                        actions.answerVideo?.let { CallControl("video", "Ответить с видео", Color(0xFF177A4B), onClick = it) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        actions.speaker?.let { CallControl("speaker", "Динамик", panel, selected = speaker, onClick = it) }
                        actions.camera?.let { CallControl("video", "Камера", panel, selected = camera, off = !camera, onClick = it) }
                        actions.mic?.let { CallControl("mic", "Микрофон", panel, selected = muted, off = muted, onClick = it) }
                        CallControl("phone", "Завершить", Color(0xFFD6384B), hangup = true, onClick = actions.end)
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun CallControl(kind: String, label: String, background: Color,
    selected: Boolean = false, off: Boolean = false, hangup: Boolean = false, onClick: () -> Unit) {
    val iconColor = if (selected) Ink else Color.White
    Column(Modifier.width(65.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(50.dp).clip(CircleShape).background(if (selected) Yellow else background),
            contentAlignment = Alignment.Center) {
            Box(Modifier.rotate(if (hangup) 135f else 0f)) { ChatGlyph(kind, iconColor) }
            if (off) Box(Modifier.rotate(-42f).size(width = 24.dp, height = 2.dp).background(iconColor))
        }
        Text(label, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 13.sp)
    }
}

@Composable
private fun CallVideo(engine: CallEngine, camera: Boolean, modifier: Modifier) {
    var local by remember(engine) { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remote by remember(engine) { mutableStateOf<SurfaceViewRenderer?>(null) }
    DisposableEffect(engine, local, remote) {
        engine.attachRenderers(local, remote)
        onDispose { engine.attachRenderers(null, null) }
    }
    Box(modifier.clip(RoundedCornerShape(22.dp)).background(Color.Black)) {
        AndroidView(factory = { ctx -> SurfaceViewRenderer(ctx).also { CallWebRtc.initRenderer(it, false); remote = it } },
            modifier = Modifier.fillMaxSize(),
            onRelease = { engine.attachRenderers(local, null); it.release(); if (remote === it) remote = null })
        if (camera && engine.hasLocalVideo) {
            AndroidView(factory = { ctx -> SurfaceViewRenderer(ctx).also {
                it.setZOrderMediaOverlay(true)
                CallWebRtc.initRenderer(it, true)
                local = it
            } }, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(92.dp, 128.dp),
                onRelease = { engine.attachRenderers(null, remote); it.release(); if (local === it) local = null })
        }
    }
}
