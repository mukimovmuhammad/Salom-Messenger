package com.mukimov.salom

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentSheet(onDismiss: () -> Unit, onPhoto: (Uri) -> Unit,
    openPicker: () -> Unit, openFiles: () -> Unit, openLocation: () -> Unit) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val colors = MaterialTheme.colorScheme
    var permissionResult by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionResult++ }
    val hasAccess = remember(permissionResult) {
        (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) ||
            (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    }
    val recent by produceState(emptyList<Uri>(), hasAccess, permissionResult) {
        value = if (!hasAccess) emptyList() else withContext(Dispatchers.IO) {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            try {
                buildList {
                    resolver.query(collection, arrayOf(MediaStore.Images.Media._ID), null, null,
                        "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                        while (size < 30 && cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0)))
                    }
                }
            } catch (_: SecurityException) { emptyList() }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Галерея", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = openPicker) { Text("Все фото и видео") }
            }
            if (recent.isNotEmpty()) {
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth().heightIn(max = 270.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(recent, key = Uri::toString) { uri ->
                        val bitmap by produceState<Bitmap?>(null, uri) {
                            value = withContext(Dispatchers.IO) {
                                if (Build.VERSION.SDK_INT >= 29) {
                                    runCatching { resolver.loadThumbnail(uri, android.util.Size(240, 240), null) }.getOrNull()
                                } else null
                            }
                        }
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant)
                            .clickable(role = Role.Button, onClickLabel = "Прикрепить фото") { onPhoto(uri) },
                            contentAlignment = Alignment.Center) {
                            bitmap?.let { Image(it.asImageBitmap(), "Недавнее фото", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                ?: AttachmentIcon(0)
                        }
                    }
                }
            } else {
                Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(18.dp), color = colors.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        AttachmentIcon(0)
                        Text(if (hasAccess) "Недавних фото пока нет" else "Выбери фото или видео", Modifier.padding(top = 7.dp), textAlign = TextAlign.Center)
                        if (!hasAccess && Build.VERSION.SDK_INT >= 33) TextButton(onClick = {
                            permission.launch(if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
                        }) { Text("Показать недавние фото") }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 9.dp))
            Row(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Галерея" to openPicker, "Файл" to openFiles, "Геопозиция" to openLocation).forEachIndexed { index, (title, action) ->
                    Column(Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (index == 0) colors.secondaryContainer else colors.surface)
                        .clickable(role = Role.Button, onClick = action).padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        AttachmentIcon(index)
                        Text(title, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentIcon(kind: Int) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(22.dp)) {
        val unit = size.width / 24
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, point(x, y), point(a, b), 1.7f * unit)
        when (kind) {
            0 -> {
                drawRect(color, point(3f, 4f), Size(18 * unit, 16 * unit), style = Stroke(1.7f * unit))
                drawCircle(color, 1.7f * unit, point(8f, 9f))
                line(4f, 18f, 12f, 12f); line(12f, 12f, 20f, 17f)
            }
            1 -> {
                drawRect(color, point(5f, 3f), Size(14 * unit, 18 * unit), style = Stroke(1.7f * unit))
                line(8f, 9f, 16f, 9f); line(8f, 14f, 16f, 14f)
            }
            else -> {
                drawCircle(color, 7f * unit, point(12f, 10f), style = Stroke(1.7f * unit))
                drawCircle(color, 2f * unit, point(12f, 10f))
                line(8f, 16f, 12f, 22f); line(12f, 22f, 16f, 16f)
            }
        }
    }
}
