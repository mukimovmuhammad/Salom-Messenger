package com.mukimov.salom

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

@Composable
internal fun ProfileAccountDialog(directory: DirectoryClient, onClose: () -> Unit, onVerify: () -> Unit) {
    val context = LocalContext.current
    val auth = remember(directory) { AuthClient(context, directory) }
    var linked by remember { mutableStateOf<Boolean?>(null) }
    var email by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf<JSONArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var devicesError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(directory, retry) {
        error = null
        try {
            val status = auth.status()
            linked = status.optBoolean("google_linked")
            email = status.optString("email")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Не удалось загрузить аккаунт." }
    }
    LaunchedEffect(directory, retry) {
        devicesError = null
        try {
            devices = withContext(Dispatchers.IO) { directory.request("auth/devices").getJSONArray("devices") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: DirectoryException) {
            devicesError = if (failure.status == 404) "Список появится после обновления сервера." else "Не удалось загрузить устройства."
        } catch (_: Exception) { devicesError = "Не удалось загрузить устройства." }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("Аккаунт") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Способы входа", fontWeight = FontWeight.SemiBold)
            when {
                error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                linked == null -> CircularProgressIndicator(Modifier.size(20.dp), color = Yellow, strokeWidth = 2.dp)
                linked == true -> Text("✓ Google подключён")
                else -> TextButton(onClick = onVerify) { Text("Подключить Google") }
            }
            if (email.isNotBlank()) Text(email, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            HorizontalDivider(Modifier.padding(vertical = 5.dp))
            Text("Устройства", fontWeight = FontWeight.SemiBold)
            val activeDevices = devices
            when {
                devicesError != null -> Text(devicesError.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                activeDevices == null -> CircularProgressIndicator(Modifier.size(20.dp), color = Yellow, strokeWidth = 2.dp)
                activeDevices.length() == 0 -> Text("Активных сеансов пока нет.")
                else -> {
                    val date = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
                    for (index in 0 until activeDevices.length()) {
                        val device = activeDevices.getJSONObject(index)
                        val current = device.optBoolean("current")
                        Text(device.optString("label", "").takeUnless { it.isBlank() || it == "null" } ?: "Неизвестное устройство",
                            fontWeight = FontWeight.Medium)
                        val signedInAt = date.format(java.util.Date((device.optDouble("created", 0.0) * 1000).toLong()))
                        Text(if (current) "Этот сеанс · вход $signedInAt" else "Вход $signedInAt",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
            if (error != null || devicesError != null) TextButton(onClick = { retry++ }) { Text("Повторить") }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Готово") } })
}
