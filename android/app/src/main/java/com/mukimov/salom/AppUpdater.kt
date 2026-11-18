package com.mukimov.salom

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal sealed interface UpdateStatus {
    data object Unchecked : UpdateStatus
    data object Checking : UpdateStatus
    data class Current(val checkedAt: Long) : UpdateStatus
    data class LocalAhead(val release: AppRelease) : UpdateStatus
    data class Available(val release: AppRelease) : UpdateStatus
    data class Incompatible(val release: AppRelease) : UpdateStatus
    data class Downloading(val release: AppRelease, val bytes: Long) : UpdateStatus
    data class Ready(val release: AppRelease, val file: File, val message: String? = null) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

internal class AppUpdater(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val installedVersion: String = packageInfo.versionName.orEmpty()
    @Suppress("DEPRECATION")
    val installedCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
    val installedDisplayVersion: String = when (installedVersion) {
        "0.15.0" -> "$installedVersion Real-Time"
        "0.15.1" -> "$installedVersion CallCookie"
        else -> installedVersion
    }
    private val mutableStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Unchecked)
    val status = mutableStatus.asStateFlow()
    private var job: Job? = null
    private var lastCheck = 0L
    private var waitingForInstallPermission = false
    private val folder get() = File(context.filesDir, "updates").apply { mkdirs() }
    private fun apkFile(release: AppRelease) = File(folder, "salom-${release.code}-${release.sha256.take(12)}.apk")

    fun onResume() {
        if (waitingForInstallPermission) {
            waitingForInstallPermission = false
            if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) {
                install(); return
            }
            val ready = mutableStatus.value as? UpdateStatus.Ready
            if (ready != null) mutableStatus.value = ready.copy(message = "Разреши установку из Салома и нажми «Установить» ещё раз")
            return
        }
        if (mutableStatus.value !is UpdateStatus.Ready) check()
    }

    fun check(force: Boolean = false) {
        if (job?.isActive == true) return
        if (!force && lastCheck > 0 && android.os.SystemClock.elapsedRealtime() - lastCheck < 15 * 60_000) return
        mutableStatus.value = UpdateStatus.Checking
        job = viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { fetchRelease() }
                mutableStatus.value = when (releaseRelation(installedCode, installedVersion, Build.VERSION.SDK_INT, release)) {
                    ReleaseRelation.CURRENT -> UpdateStatus.Current(System.currentTimeMillis())
                    ReleaseRelation.LOCAL_AHEAD -> UpdateStatus.LocalAhead(release)
                    ReleaseRelation.INCOMPATIBLE -> UpdateStatus.Incompatible(release)
                    ReleaseRelation.NEWER_AVAILABLE -> withContext(Dispatchers.IO) {
                        val file = apkFile(release)
                        if (file.isFile && runCatching { verify(file, release) }.isSuccess) UpdateStatus.Ready(release, file)
                        else UpdateStatus.Available(release)
                    }
                }
                lastCheck = android.os.SystemClock.elapsedRealtime()
            } catch (exception: CancellationException) { throw exception }
            catch (_: Exception) {
                lastCheck = 0
                mutableStatus.value = UpdateStatus.Failed("Не удалось проверить обновления. Проверь интернет и повтори попытку")
            } finally { job = null }
        }
    }

    private fun connection(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 7000; readTimeout = 12000
        instanceFollowRedirects = false; useCaches = false
        setRequestProperty("Cache-Control", "no-cache")
        setRequestProperty("Accept-Encoding", "identity")
    }

    private fun fetchRelease(): AppRelease {
        val connection = connection("https://mukimov.site/salom/version.json")
        try {
            require(connection.responseCode == 200)
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 32_768)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val notes = json.optJSONArray("releaseNotes")
            return AppRelease(json.getString("version"), json.getLong("versionCode"), json.getInt("minSdk"),
                json.getString("downloadUrl"), json.getLong("size"), json.getString("sha256").lowercase(),
                (0 until minOf(notes?.length() ?: 0, 5)).map { notes!!.getString(it).take(240) }, json.optString("displayName"))
        } finally { connection.disconnect() }
    }

    fun download() {
        val release = (mutableStatus.value as? UpdateStatus.Available)?.release ?: return
        if (job?.isActive == true) return
        mutableStatus.value = UpdateStatus.Downloading(release, 0)
        job = viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { downloadFile(release) }
                mutableStatus.value = UpdateStatus.Ready(release, file)
            } catch (exception: CancellationException) {
                mutableStatus.value = UpdateStatus.Available(release)
                throw exception
            } catch (exception: Exception) {
                mutableStatus.value = UpdateStatus.Available(release)
                downloadError.value = exception.message?.takeIf { it.startsWith("Недостаточно") || it.startsWith("Проверка") }
                    ?: "Скачивание прервано. Нажми «Скачать», чтобы продолжить"
            } finally { job = null }
        }
    }
    val downloadError = MutableStateFlow<String?>(null)
    fun cancelDownload() { if (mutableStatus.value is UpdateStatus.Downloading) job?.cancel() }

    private suspend fun downloadFile(release: AppRelease): File {
        downloadError.value = null
        val file = apkFile(release)
        val part = File(file.path + ".part")
        if (part.length() > release.size) part.delete()
        var offset = part.length()
        if (StatFs(folder.path).availableBytes < release.size - offset + 5_242_880) error("Недостаточно свободного места для обновления")
        if (offset < release.size) {
            val connection = connection(release.url)
            try {
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val code = connection.responseCode
                if (code == 206) {
                    val range = connection.getHeaderField("Content-Range")
                    require(range == "bytes $offset-${release.size - 1}/${release.size}")
                } else {
                    require(code == 200)
                    offset = 0
                }
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(part, offset > 0).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(offset + count <= release.size)
                            output.write(buffer, 0, count); offset += count
                            mutableStatus.value = UpdateStatus.Downloading(release, offset)
                        }
                        output.fd.sync()
                    }
                }
            } finally { connection.disconnect() }
        }
        try { verify(part, release) }
        catch (_: Exception) { part.delete(); error("Проверка файла не пройдена. Скачай обновление заново") }
        require(part.renameTo(file))
        // Retain only the target build; partial downloads for other releases are obsolete.
        folder.listFiles()?.filter { it != file }?.forEach { it.delete() }
        return file
    }

    @Suppress("DEPRECATION")
    private fun verify(file: File, release: AppRelease) {
        require(file.length() == release.size)
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        require(hash.digest().joinToString("") { "%02x".format(it) } == release.sha256)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: error("Invalid APK")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName && archive.versionName == release.version)
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(code == release.code && code > installedCode)
        fun certificates(info: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { it.toCharsString() }.toSet()
        }
        val expected = certificates(installed)
        require(expected.isNotEmpty() && certificates(archive) == expected)
    }

    fun install() {
        val ready = mutableStatus.value as? UpdateStatus.Ready ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                waitingForInstallPermission = true
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", ready.file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { mutableStatus.value = ready.copy(message = "Не удалось открыть установку. Попробуй ещё раз") }
    }
}
