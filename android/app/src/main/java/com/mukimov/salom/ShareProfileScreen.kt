package com.mukimov.salom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ProfileQrStyle(val title: String, val ink: Int, val paper: Int) {
    HONEY("Мёд", 0xFF332500.toInt(), 0xFFFFF1BA.toInt()),
    CLASSIC("Классика", 0xFF161616.toInt(), 0xFFFFFFFF.toInt()),
    MINT("Мята", 0xFF103E34.toInt(), 0xFFE0FFF0.toInt()),
}

internal object ProfileQr {
    const val EDGE = 720

    fun pixels(url: String, style: ProfileQrStyle): IntArray {
        val modules = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, EDGE, EDGE,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
        return IntArray(EDGE * EDGE) { index ->
            if (modules.get(index % EDGE, index / EDGE)) style.ink else style.paper
        }
    }
}

@Composable
internal fun ShareProfileScreen(name: String, url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var styleName by rememberSaveable { mutableStateOf(ProfileQrStyle.HONEY.name) }
    val style = ProfileQrStyle.valueOf(styleName)
    var image by remember(url, style) { mutableStateOf<Bitmap?>(null) }
    var exportingStyle by rememberSaveable { mutableStateOf<String?>(null) }
    var failed by remember(url, style) { mutableStateOf(false) }

    LaunchedEffect(url, style) {
        try {
            image = withContext(Dispatchers.Default) {
                Bitmap.createBitmap(ProfileQr.pixels(url, style), ProfileQr.EDGE, ProfileQr.EDGE, Bitmap.Config.ARGB_8888)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { destination ->
        val exportStyle = exportingStyle?.let(ProfileQrStyle::valueOf)
        exportingStyle = null
        if (destination != null && exportStyle != null) scope.launch {
            val status = try {
                withContext(Dispatchers.IO) {
                    val source = Bitmap.createBitmap(ProfileQr.pixels(url, exportStyle), ProfileQr.EDGE, ProfileQr.EDGE, Bitmap.Config.ARGB_8888)
                    try {
                        check(context.contentResolver.openOutputStream(destination, "wt")?.use {
                            source.compress(Bitmap.CompressFormat.PNG, 100, it)
                        } == true)
                    } finally { source.recycle() }
                }
                "QR-код сохранён"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "Не удалось сохранить QR-код" }
            Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
        }
    }

    PreviewScreen("Поделиться профилем", dark = false, onClose = onClose) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Text(url.removePrefix("https://"), Modifier.padding(top = 4.dp, bottom = 14.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Surface(Modifier.widthIn(max = 264.dp).fillMaxWidth().aspectRatio(1f),
                            color = Color(style.paper), shape = RoundedCornerShape(16.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                val bitmap = image
                                if (bitmap != null) Image(bitmap.asImageBitmap(), "QR-код профиля", Modifier.fillMaxSize())
                                else if (failed) Text("Не удалось создать QR-код", color = Color(style.ink))
                                else CircularProgressIndicator(color = Color(style.ink))
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileQrStyle.entries.forEach { option ->
                        FilterChip(selected = style == option, onClick = { styleName = option.name }, label = { Text(option.title) })
                    }
                }
                Button(onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(Intent.createChooser(share, "Поделиться профилем"))
                }, modifier = Modifier.fillMaxWidth()) { Text("Поделиться ссылкой") }
                FilledTonalButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Ссылка на профиль Салома", url))
                    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.fillMaxWidth()) { Text("Скопировать ссылку") }
                OutlinedButton(onClick = {
                    exportingStyle = style.name
                    save.launch("SALOM-${url.substringAfterLast('/')}-${style.name.lowercase(java.util.Locale.ROOT)}.png")
                }, enabled = image != null && exportingStyle == null, modifier = Modifier.fillMaxWidth()) { Text("Скачать QR-код") }
            }
        }
    }
}
