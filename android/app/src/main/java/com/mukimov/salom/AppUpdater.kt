package com.mukimov.salom

import android.app.Application
import android.content.Context
import androidx.work.*
import androidx.lifecycle.asFlow
import java.io.IOException
import java.util.concurrent.TimeUnit
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
    private val files = UpdateFiles(context)
    private val work = WorkManager.getInstance(context)
    private val preferences = context.getSharedPreferences("salom_updates", Context.MODE_PRIVATE)


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
        if (mutableStatus.value is UpdateStatus.Downloading) return
        if (job?.isActive == true) return
        if (!force && lastCheck > 0 && android.os.SystemClock.elapsedRealtime() - lastCheck < 15 * 60_000) return
        mutableStatus.value = UpdateStatus.Checking
        job = viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    try { fetchRelease() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        val cached = preferences.getString("download_release", null)?.let { value ->
                            runCatching { parseRelease(JSONObject(value)) }.getOrNull()
                        }
                        if (cached == null || cached.code <= installedCode) throw failure
                        cached
                    }
                }
                var pendingId: java.util.UUID? = null
                mutableStatus.value = when (releaseRelation(installedCode, installedVersion, Build.VERSION.SDK_INT, release)) {
                    ReleaseRelation.CURRENT -> UpdateStatus.Current(System.currentTimeMillis())
                    ReleaseRelation.LOCAL_AHEAD -> UpdateStatus.LocalAhead(release)
                    ReleaseRelation.INCOMPATIBLE -> UpdateStatus.Incompatible(release)
                    ReleaseRelation.NEWER_AVAILABLE -> withContext(Dispatchers.IO) {
                        val file = files.apkFile(release)
                        val pending = work.getWorkInfosForUniqueWork(UpdateDownloadWorker.NAME).get()
                            .firstOrNull { !it.state.isFinished && release.workTag() in it.tags }
                        if (pending != null) {
                            pendingId = pending.id
                            return@withContext UpdateStatus.Downloading(release, files.partFile(release).length())
                        }
                        if (file.isFile && runCatching { files.verify(file, release) }.isSuccess) UpdateStatus.Ready(release, file)
                        else UpdateStatus.Available(release)
                    }
                }
                pendingId?.let { observeDownload(release, it) }
                lastCheck = android.os.SystemClock.elapsedRealtime()
            } catch (exception: CancellationException) { throw exception }
            catch (_: Exception) {
                lastCheck = 0
                mutableStatus.value = UpdateStatus.Failed("Не удалось проверить обновления. Проверь интернет и повтори попытку")
            } finally { job = null }
        }
    }

    private fun fetchRelease(): AppRelease {
        val connection = updateConnection("https://mukimov.site/salom/version.json")
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
            return parseRelease(JSONObject(bytes.toString(Charsets.UTF_8)))
        } finally { connection.disconnect() }
    }

    private fun parseRelease(json: JSONObject): AppRelease {
            val notes = json.optJSONArray("releaseNotes")
            val base = AppRelease(json.getString("version"), json.getLong("versionCode"), json.getInt("minSdk"),
                json.getString("downloadUrl"), json.getLong("size"), json.getString("sha256").lowercase(),
                (0 until minOf(notes?.length() ?: 0, 5)).map { notes!!.getString(it).take(240) }, json.optString("displayName"))
            val variants = json.optJSONArray("androidVariants")
            for (abi in Build.SUPPORTED_ABIS) {
                for (index in 0 until (variants?.length() ?: 0)) {
                    val item = variants?.optJSONObject(index) ?: continue
                    if (item.optString("abi") != abi) continue
                    val variant = runCatching {
                        val url = item.getString("downloadUrl")
                        require(url == "https://mukimov.site/salom/salom-${base.version}-$abi.apk")
                        base.copy(url = url, size = item.getLong("size"), sha256 = item.getString("sha256").lowercase())
                    }.getOrNull()
                    if (variant != null && variant.size < base.size) return variant
                }
            }
            return base
    }

    private fun observeDownload(release: AppRelease, id: java.util.UUID) {
        observer?.cancel()
        observer = viewModelScope.launch {
            work.getWorkInfoByIdLiveData(id).asFlow().collect { info ->
                if (info == null) return@collect
                val file = files.apkFile(release)
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        mutableStatus.value = UpdateStatus.Ready(release, file)
                        cancel()
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        mutableStatus.value = UpdateStatus.Available(release)
                        downloadError.value = info.outputData.getString("error")
                        cancel()
                    }
                    else -> mutableStatus.value = UpdateStatus.Downloading(release, files.partFile(release).length())
                }
            }
        }
    }

    private var observer: Job? = null
    val downloadError = MutableStateFlow<String?>(null)

    fun download() {
        val release = (mutableStatus.value as? UpdateStatus.Available)?.release ?: return
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setInputData(release.workData())
            .addTag(release.workTag())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        downloadError.value = null
        preferences.edit().putString("download_release", JSONObject()
            .put("version", release.version).put("versionCode", release.code).put("minSdk", release.minSdk)
            .put("downloadUrl", release.url).put("size", release.size).put("sha256", release.sha256)
            .put("releaseNotes", org.json.JSONArray(release.notes)).put("displayName", release.displayName).toString()).apply()
        mutableStatus.value = UpdateStatus.Downloading(release, files.partFile(release).length())
        work.enqueueUniqueWork(UpdateDownloadWorker.NAME, ExistingWorkPolicy.REPLACE, request)
        observeDownload(release, request.id)
    }

    fun cancelDownload() {
        if (mutableStatus.value is UpdateStatus.Downloading) {
            work.cancelUniqueWork(UpdateDownloadWorker.NAME)
            UpdateNotifications.clear(context)
        }
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

private fun updateConnection(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 7000; readTimeout = 12000
        instanceFollowRedirects = false; useCaches = false
        setRequestProperty("Cache-Control", "no-cache")
        setRequestProperty("Accept-Encoding", "identity")
    }

internal class UpdateFiles(private val context: Context) {
    private val folder get() = File(context.filesDir, "updates").apply { mkdirs() }
    fun apkFile(release: AppRelease) = File(folder, "salom-${release.code}-${release.sha256}.apk")
    fun partFile(release: AppRelease) = File(apkFile(release).path + ".part")


    suspend fun downloadFile(release: AppRelease, report: suspend (Long) -> Unit): File {

        val file = apkFile(release)
        val part = partFile(release)
        if (part.length() > release.size) part.delete()
        var offset = part.length()
        if (StatFs(folder.path).availableBytes < release.size - offset + 5_242_880) error("Недостаточно свободного места для обновления")
        if (offset < release.size) {
            val connection = updateConnection(release.url)
            try {
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val code = connection.responseCode
                if (code == 206) {
                    val range = connection.getHeaderField("Content-Range")
                    require(range == "bytes $offset-${release.size - 1}/${release.size}")
                } else {
                    if (code != 200) throw IOException("Ошибка HTTP $code")
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
                            if (offset / (512 * 1024) != (offset - count) / (512 * 1024)) report(offset)
                        }
                        output.fd.sync()
                    }
                }
            } finally { connection.disconnect() }
        }
        if (part.length() != release.size) throw IOException("Неполная загрузка")
        try { verify(part, release) }
        catch (_: Exception) { part.delete(); error("Проверка файла не пройдена. Скачай обновление заново") }
        require(part.renameTo(file))
        folder.listFiles()?.filter { it != file && it.isFile && it.name.startsWith("salom-") }?.forEach { it.delete() }
        return file
    }

    @Suppress("DEPRECATION")
    fun verify(file: File, release: AppRelease) {
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
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(code == release.code && code > installedCode)
        fun certificates(info: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { it.toCharsString() }.toSet()
        }
        val expected = certificates(installed)
        require(expected.isNotEmpty() && certificates(archive) == expected)
    }

}

private fun AppRelease.workTag() = "salom-apk-$code-$sha256"

private fun AppRelease.workData() = workDataOf(
    "version" to version, "code" to code, "minSdk" to minSdk,
    "url" to url, "size" to size, "sha256" to sha256,
)

class UpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    internal companion object { const val NAME = "salom-apk-download" }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val version = inputData.getString("version").orEmpty()
        try {
            val release = AppRelease(version, inputData.getLong("code", 0),
                inputData.getInt("minSdk", 0), inputData.getString("url").orEmpty(),
                inputData.getLong("size", 0), inputData.getString("sha256").orEmpty())
            val installed = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
            @Suppress("DEPRECATION")
            val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
            if (release.code <= installedCode || release.minSdk > Build.VERSION.SDK_INT) return@withContext Result.failure()
            val files = UpdateFiles(applicationContext)
            val ready = files.apkFile(release)
            if (!ready.isFile || runCatching { files.verify(ready, release) }.isFailure) {
                fun percent(bytes: Long) = (100 * bytes / release.size).coerceIn(0, 100).toInt()
                var shown = percent(files.partFile(release).length())
                var shownAt = android.os.SystemClock.elapsedRealtime()
                UpdateNotifications.progress(applicationContext, version, shown)
                files.downloadFile(release) { bytes ->
                    setProgress(workDataOf("bytes" to bytes))
                    val now = android.os.SystemClock.elapsedRealtime()
                    val next = percent(bytes)
                    if (next > shown && now - shownAt >= 750) {
                        shown = next
                        shownAt = now
                        UpdateNotifications.progress(applicationContext, version, next)
                    }
                }
            }
            ensureActive()
            UpdateNotifications.ready(applicationContext, version)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            UpdateNotifications.failed(applicationContext, version)
            if (runAttemptCount < 5) Result.retry()
            else Result.failure(workDataOf("error" to "Скачивание прервано. Нажми «Скачать», чтобы продолжить"))
        } catch (error: Exception) {
            UpdateNotifications.failed(applicationContext, version)
            val message = error.message?.takeIf { it.startsWith("Недостаточно") || it.startsWith("Проверка") }
            Result.failure(workDataOf("error" to (message ?: "Не удалось скачать обновление. Попробуй снова")))
        }
    }
}
