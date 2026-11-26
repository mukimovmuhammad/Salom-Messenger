package com.mukimov.salom

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun PeerProfileDialog(person: Person, directory: DirectoryClient, online: Boolean, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var account by remember(person.id) { mutableStateOf<SalomAccount?>(null) }
    var viewingPhoto by remember(person.id) { mutableStateOf(false) }
    var sharingProfile by remember(person.id) { mutableStateOf(false) }
    var profileMenu by remember(person.id) { mutableStateOf(false) }
    LaunchedEffect(person.id, directory) {
        try { account = directory.peer(person.id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep the identity already loaded by the chat. */ }
    }
    val current = account
    val profile = current?.let {
        person.copy(name = it.name, initials = it.name.trim().take(1).uppercase(),
            username = it.username, avatarUrl = it.avatarUrl)
    } ?: person
    val about = current?.about.orEmpty()
    val since = profileJoinedDate(current?.joinedAt)
    val shareUrl = ProfileLinks.shareUrl(profile.username)
    PreviewScreen("Профиль", dark = false, onClose = onDismiss, trailing = {
        if (shareUrl != null) Box {
            IconButton(onClick = { profileMenu = true },
                modifier = Modifier.semantics { contentDescription = "Меню профиля" }) {
                ChatGlyph("menu", colors.onSurface)
            }
            DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                DropdownMenuItem(text = { Text("Поделиться профилем") },
                    onClick = { profileMenu = false; sharingProfile = true })
            }
        }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(168.dp)) {
                Box(Modifier.fillMaxWidth().height(116.dp).background(
                    ProfileCover.HONEY.brush(colors.background.red < .5f)))
                Surface(Modifier.align(Alignment.BottomCenter).size(106.dp),
                    color = colors.surface, shape = CircleShape, shadowElevation = 6.dp) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier
                        .clickable(enabled = profile.avatarUrl.isNotBlank(), role = Role.Button,
                            onClickLabel = "Посмотреть фото профиля", onClick = { viewingPhoto = true })) {
                        Avatar(profile, size = 98)
                    }
                }
            }
            Text(profile.name, Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            profile.username.trimStart('@').takeIf(String::isNotBlank)?.let {
                Text("@$it", Modifier.padding(top = 4.dp), color = colors.onSurfaceVariant, fontSize = 14.sp)
            }
            Row(Modifier.padding(top = 10.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape)
                    .background(if (online) Color(0xFF37AD70) else colors.outline))
                Text(if (online) "В сети" else "Не в сети", Modifier.padding(start = 7.dp),
                    color = colors.onSurfaceVariant, fontSize = 13.sp)
            }
            if (about.isNotBlank() || since != null) {
                Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 18.dp),
                    shape = RoundedCornerShape(18.dp), color = colors.surface) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        if (about.isNotBlank()) PeerDetail("О себе", about)
                        if (since != null && about.isNotBlank()) HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
                        if (since != null) PeerDetail("В Саломе с", since)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (viewingPhoto) AvatarViewer(profile.avatarUrl) { viewingPhoto = false }
    if (sharingProfile && shareUrl != null) ShareProfileScreen(profile.name, shareUrl) { sharingProfile = false }
}

@Composable
private fun PeerDetail(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun AvatarViewer(url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val photos = remember(context) { SalomPhotos(context) }
    var avatar by remember(url) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    LaunchedEffect(url, attempt) {
        loading = true
        try { avatar = photos.avatar(url) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { avatar = null }
        finally { loading = false }
    }
    PreviewScreen("Фото профиля", dark = true, onClose = onClose) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val image = avatar
            when {
                image != null -> ZoomablePhoto(image, "Фото профиля")
                loading -> CircularProgressIndicator(color = Yellow)
                else -> TextButton(onClick = { attempt++ }) { Text("Повторить загрузку", color = Yellow) }
            }
        }
    }
}

internal fun profileJoinedDate(seconds: Long?): String? =
    seconds?.takeIf { it in 1..253402300799L }?.let {
        SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("ru")).format(Date(it * 1000))
    }
