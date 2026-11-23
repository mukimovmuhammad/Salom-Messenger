package com.mukimov.salom

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal enum class ProfileCover(val label: String, private val light: List<Color>, private val dark: List<Color>) {
    HONEY("Мёд", listOf(Color(0xFFFFF2C5), Color(0xFFF8C449)), listOf(Color(0xFF272215), Color(0xFF74591C))),
    LAVENDER("Лаванда", listOf(Color(0xFFF3E8FF), Color(0xFFB6A9F9)), listOf(Color(0xFF252137), Color(0xFF55457D))),
    SEA("Море", listOf(Color(0xFFE4F7F8), Color(0xFF8DCDDF)), listOf(Color(0xFF132A39), Color(0xFF295975))),
    MINT("Мята", listOf(Color(0xFFE7F9EA), Color(0xFF97D3AC)), listOf(Color(0xFF142D27), Color(0xFF366653))),
    SUNSET("Закат", listOf(Color(0xFFFFEAD6), Color(0xFFF4A69E)), listOf(Color(0xFF302029), Color(0xFF7A484B)));

    fun brush(darkTheme: Boolean) = Brush.horizontalGradient(if (darkTheme) dark else light)
}

@Composable
internal fun ProfileCoverPicker(dark: Boolean, selected: ProfileCover, onClose: () -> Unit, onSelect: (ProfileCover) -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("Оформление профиля") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ProfileCover.entries.forEach { cover ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { onSelect(cover) }.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(52.dp).height(28.dp).clip(RoundedCornerShape(8.dp)).background(cover.brush(dark)))
                    Text(cover.label, Modifier.weight(1f))
                    if (cover == selected) Text("✓", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Готово") } })
}

internal data class AvatarCrop(val zoom: Float = 1f, val x: Float = .5f, val y: Float = .5f) {
    fun region(bitmap: Bitmap): Rect {
        val edge = (minOf(bitmap.width, bitmap.height) / zoom).roundToInt().coerceAtLeast(1)
        val left = ((bitmap.width - edge) * x).roundToInt()
        val top = ((bitmap.height - edge) * y).roundToInt()
        return Rect(left, top, left + edge, top + edge)
    }
}

@Composable
internal fun AvatarCropDialog(bitmap: Bitmap, onDismiss: () -> Unit, onApply: (AvatarCrop) -> Unit) {
    var crop by remember(bitmap) { mutableStateOf(AvatarCrop()) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Настроить аватар") }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape)
                .pointerInput(bitmap) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        val region = crop.region(bitmap)
                        val scale = region.width().toFloat() / size.width.coerceAtLeast(1)
                        crop = crop.copy(
                            x = (crop.x - delta.x * scale / (bitmap.width - region.width()).coerceAtLeast(1)).coerceIn(0f, 1f),
                            y = (crop.y - delta.y * scale / (bitmap.height - region.height()).coerceAtLeast(1)).coerceIn(0f, 1f)
                        )
                    }
                }) {
                val region = crop.region(bitmap)
                drawImage(image, srcOffset = IntOffset(region.left, region.top), srcSize = IntSize(region.width(), region.height()),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
            }
            Slider(value = crop.zoom, onValueChange = { crop = crop.copy(zoom = it) }, valueRange = 1f..3f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Дальше"); Text("Ближе") }
        }
    }, confirmButton = { TextButton(onClick = { onApply(crop) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
