package com.mukimov.salom

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class Attachment(val id: String, val name: String, val mime: String, val size: Long, val local: Boolean = false, val kind: String = "file", val durationMs: Long = 0, val waveform: List<Int> = emptyList()) {
    fun json() = JSONObject().put("id", id).put("name", name).put("mime", mime).put("size", size).put("local", local).put("kind", kind).put("duration_ms", durationMs).put("waveform", org.json.JSONArray(waveform))
    companion object {
        fun from(json: JSONObject) = Attachment(json.getString("id"), json.getString("name"), json.getString("mime"), json.getLong("size"), json.optBoolean("local"), json.optString("kind", "file"), json.optLong("duration_ms"), json.optJSONArray("waveform")?.let { array -> (0 until array.length()).map { array.optInt(it).coerceIn(0, 100) }.take(32) }.orEmpty())
    }
}
internal class MediaFiles(private val context: Context, private val directory: DirectoryClient) {
    private val photos = SalomPhotos(context)

    suspend fun savePhoto(attachment: Attachment) = photos.saveChat(attachment, file(attachment), automatic = false)

    suspend fun autoSaveOwnPhoto(attachment: Attachment) {
        if (android.os.Build.VERSION.SDK_INT >= 29 && !attachment.local && photos.autoSave &&
            SalomPhotos.galleryMime(attachment.mime)) {
            photos.saveChat(attachment, file(attachment), automatic = true)
        }
    }
    private val lock = Mutex()
    private fun target(attachment: Attachment): File {
        require(runCatching { UUID.fromString(attachment.id) }.isSuccess)
        val base = if (attachment.local) context.filesDir else context.cacheDir
        return File(base, "media/${directory.ownId}/${attachment.id}").also { it.parentFile!!.mkdirs() }
    }
    internal fun newVoiceFile(): Pair<Attachment, File> {
        val id = UUID.randomUUID().toString()
        val attachment = Attachment(id, "voice_$id.m4a", "audio/mp4", 0, kind = "voice")
        return attachment to target(attachment).also { trimCache(it) }
    }
    private fun trimCache(keep: File) {
        val files = keep.parentFile?.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty()
        var total = files.sumOf { it.length() }
        for (old in files.filter { it != keep }.sortedBy { it.lastModified() }) {
            if (total <= 100L * 1024 * 1024) break
            val size = old.length()
            if (old.delete()) total -= size
        }
    }
    suspend fun prepare(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        var name = "Файл"
        var declared = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) { name = it.getString(0).orEmpty().take(180).ifBlank { "Файл" }; if (!it.isNull(1)) declared = it.getLong(1) }
        }
        if (declared > MAX_FILE) throw DirectoryException("Максимальный размер файла — 25 МБ")
        val initial = Attachment(UUID.randomUUID().toString(), name, context.contentResolver.getType(uri) ?: "application/octet-stream", 0)
        val file = target(initial)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw DirectoryException("Не удалось открыть файл")
            var size = 0L
            input.use { source -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = source.read(buffer); if (count < 0) break; size += count
                    if (size > MAX_FILE) throw DirectoryException("Максимальный размер файла — 25 МБ")
                    output.write(buffer, 0, count)
                }
            } }
            if (size == 0L) throw DirectoryException("Файл пустой")
            trimCache(file)
            initial.copy(size = size)
        } catch (failure: Exception) { file.delete(); throw failure }
    }
    suspend fun upload(peer: String, attachment: Attachment, progress: (Float) -> Unit = {}): Attachment = withContext(Dispatchers.IO) {
        val result = directory.uploadAttachment(peer, attachment, target(attachment), progress)
        target(attachment).copyTo(target(result), overwrite = true)
        result
    }
    suspend fun saveLocal(attachment: Attachment): Attachment = withContext(Dispatchers.IO) {
        val result = attachment.copy(local = true)
        target(attachment).copyTo(target(result), overwrite = true)
        result
    }
    suspend fun persistForQueue(attachment: Attachment): Attachment = withContext(Dispatchers.IO) {
        val result = attachment.copy(local = true)
        val source = target(attachment)
        if (!source.isFile) throw DirectoryException("Файл вложения уже недоступен")
        source.copyTo(target(result), overwrite = true)
        result
    }
    suspend fun file(attachment: Attachment, progress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        lock.withLock {
            val file = target(attachment)
            if (!file.isFile || file.length() != attachment.size) {
                if (attachment.local) throw DirectoryException("Локальный файл недоступен")
                if (!photos.restoreChat(attachment, file)) directory.downloadAttachment(attachment, file, progress)
                trimCache(file)
            }
            file
        }
    }
    suspend fun bitmap(attachment: Attachment) = withContext(Dispatchers.IO) {
        val file = file(attachment)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1000 || bounds.outHeight / sample > 1000) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    suspend fun removeLocal(attachment: Attachment) = withContext(Dispatchers.IO) { target(attachment).delete(); Unit }
    suspend fun open(attachment: Attachment, progress: (Float) -> Unit = {}) {
        val file = file(attachment, progress)
        val uri = FileProvider.getUriForFile(context, "com.mukimov.salom.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_TITLE, attachment.name).apply { clipData = android.content.ClipData.newRawUri(attachment.name, uri) }
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) { throw DirectoryException("На телефоне нет приложения для открытия этого файла") }
    }
    companion object { const val MAX_FILE = 25L * 1024 * 1024 }
}
