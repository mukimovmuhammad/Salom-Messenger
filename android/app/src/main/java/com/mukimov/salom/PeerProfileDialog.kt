package com.mukimov.salom

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException

@Composable
internal fun PeerProfileDialog(person: Person, directory: DirectoryClient, online: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val photos = remember(context) { SalomPhotos(context) }
    val colors = MaterialTheme.colorScheme
    var profile by remember(person) { mutableStateOf(person) }
    var viewingPhoto by remember(person.id) { mutableStateOf(false) }
    var avatar by remember(profile.avatarUrl) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(profile.avatarUrl) { mutableStateOf(profile.avatarUrl.isNotBlank()) }
    var attempt by remember(profile.avatarUrl) { mutableIntStateOf(0) }

    LaunchedEffect(person.id, directory) {
        try {
            val account = directory.peer(person.id)
            profile = person.copy(name = account.name, initials = account.name.take(1).uppercase(),
                username = account.username, avatarUrl = account.avatarUrl)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The chat already has a cached profile. */ }
    }
    LaunchedEffect(profile.avatarUrl, attempt) {
        if (profile.avatarUrl.isBlank()) return@LaunchedEffect
        loading = true
        try { avatar = photos.avatar(profile.avatarUrl) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { avatar = null }
        finally { loading = false }
    }

    val back = { if (viewingPhoto) viewingPhoto = false else onDismiss() }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(if (viewingPhoto) Color(0xFF101217) else colors.background)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back) { Text("Назад", color = if (viewingPhoto) Color.White else colors.onSurface) }
                Text(if (viewingPhoto) "Фото профиля" else "Профиль",
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    color = if (viewingPhoto) Color.White else colors.onSurface,
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            if (viewingPhoto) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val image = avatar
                    when {
                        image != null -> Image(image.asImageBitmap(), "Фото профиля", Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit)
                        loading -> CircularProgressIndicator(color = Yellow)
                        else -> TextButton(onClick = { attempt++ }) { Text("Повторить загрузку", color = Yellow) }
                    }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.size(110.dp).clip(CircleShape).background(profile.color)
                        .clickable(enabled = profile.avatarUrl.isNotBlank(), role = Role.Button,
                            onClickLabel = "Посмотреть аватар") { viewingPhoto = true },
                        contentAlignment = Alignment.Center) {
                        val image = avatar
                        if (image != null) Image(image.asImageBitmap(), "Аватар собеседника", Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop)
                        else Text(profile.initials, color = Ink, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(profile.name, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    profile.username.trimStart('@').takeIf(String::isNotBlank)?.let {
                        Text("@$it", color = colors.onSurfaceVariant, fontSize = 15.sp)
                    }
                    Text(if (online) "В сети" else "Не в сети", color = colors.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
    }
}
