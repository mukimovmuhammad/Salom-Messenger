package com.mukimov.salom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun UpdateCard(updater: AppUpdater) {
    val status by updater.status.collectAsState()
    val downloadError by updater.downloadError.collectAsState()
    LaunchedEffect(updater) { updater.check(force = true) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Обновление Салома", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("Установлена версия ${updater.installedDisplayVersion}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (val value = status) {
                UpdateStatus.Unchecked, UpdateStatus.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Yellow)
                    Spacer(Modifier.width(10.dp)); Text("Проверяем обновления…", fontSize = 13.sp)
                }
                is UpdateStatus.Current -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF23844A).copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                            Text("✓", color = Color(0xFF23844A), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(9.dp))
                        Text("У вас последняя версия", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(value.checkedAt))
                    Text("Проверено на сервере в $time", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { updater.check(force = true) }) { Text("Проверить ещё раз") }
                }
                is UpdateStatus.LocalAhead -> {
                    Text("На сервере пока опубликована версия ${value.release.displayVersion}. Эта сборка ещё не опубликована для обновления.", fontSize = 13.sp)
                    TextButton(onClick = { updater.check(force = true) }) { Text("Проверить ещё раз") }
                }
                is UpdateStatus.Failed -> {
                    Text(value.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { updater.check(force = true) }) { Text("Повторить проверку") }
                }
                is UpdateStatus.Incompatible -> Text("Версия ${value.release.version} требует более новой версии Android", fontSize = 13.sp)
                is UpdateStatus.Available -> {
                    ReleaseDetails(value.release)
                    downloadError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = updater::download, shape = RoundedCornerShape(14.dp)) { Text("Скачать обновление") }
                }
                is UpdateStatus.Downloading -> {
                    val progress = (value.bytes.toFloat() / value.release.size).coerceIn(0f, 1f)
                    Text("Скачивание · ${(progress * 100).toInt()}%", fontSize = 13.sp)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Yellow)
                    TextButton(onClick = updater::cancelDownload) { Text("Приостановить") }
                }
                is UpdateStatus.Ready -> {
                    ReleaseDetails(value.release)
                    Text("Обновление скачано и проверено", fontSize = 12.sp)
                    value.message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Button(onClick = updater::install, shape = RoundedCornerShape(14.dp)) { Text("Установить обновление") }
                }
            }
        }
    }
}

@Composable
private fun ReleaseDetails(release: AppRelease) {
                    Text("Доступна версия ${release.displayVersion}", fontWeight = FontWeight.Medium, fontSize = 14.sp)
    Text(String.format(java.util.Locale.getDefault(), "%.2f МБ", release.size / 1_000_000.0), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    release.notes.forEach { Text("• $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
