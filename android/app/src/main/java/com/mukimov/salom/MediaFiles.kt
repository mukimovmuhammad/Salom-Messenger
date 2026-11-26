package com.mukimov.salom

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.LruCache
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class Attachment(val id: String, val name: String, val mime: String, val size: Long, val local: Boolean = false, val kind: String = "file", val durationMs: Long = 0, val waveform: List<Int> = emptyList(), val extraPhotos: List<Attachment> = emptyList()) {
    val photos get() = listOf(copy(extraPhotos = emptyList())) + extraPhotos
    val isAlbumPhoto get() = mime in setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("mime", mime).put("size", size).put("local", local).put("kind", kind).put("duration_ms", durationMs).put("waveform", org.json.JSONArray(waveform))
        .apply { if (extraPhotos.isNotEmpty()) put("album", org.json.JSONArray(extraPhotos.map { it.json() })) }
    companion object {
        fun from(json: JSONObject): Attachment = Attachment(json.getString("id"), json.getString("name"), json.getString("mime"), json.getLong("size"), json.optBoolean("local"), json.optString("kind", "file"), json.optLong("duration_ms"), json.optJSONArray("waveform")?.let { array -> (0 until array.length()).map { array.optInt(it).coerceIn(0, 100) }.take(32) }.orEmpty(), parsePhotos(json.optJSONArray("album")))
        fun parsePhotos(array: org.json.JSONArray?): List<Attachment> = array?.let { (0 until minOf(it.length(), 12)).mapNotNull { index -> it.optJSONObject(index)?.let(::from) } }.orEmpty()
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
    private val received = MediaDiskCache(
        File(context.filesDir, "received-media/${directory.ownId}"),
        File(context.cacheDir, "media/${directory.ownId}")
    )
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
    suspend fun prepare(uri: Uri, durable: Boolean = false, optimizePhoto: Boolean = false): Attachment = withContext(Dispatchers.IO) {
        var name = "Файл"
        var declared = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) { name = it.getString(0).orEmpty().take(180).ifBlank { "Файл" }; if (!it.isNull(1)) declared = it.getLong(1) }
        }
        if (declared > MAX_FILE) throw DirectoryException("Максимальный размер файла — 25 МБ")
        val initial = Attachment(UUID.randomUUID().toString(), name, context.contentResolver.getType(uri) ?: "application/octet-stream", 0, local = durable)
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
            if (!durable) trimCache(file)
            val prepared = initial.copy(size = size)
            if (optimizePhoto) optimize(file, prepared) else prepared
        } catch (failure: Exception) { file.delete(); throw failure }
    }
    suspend fun upload(peer: String, attachment: Attachment, progress: (Float) -> Unit = {}): Attachment = withContext(Dispatchers.IO) {
        val source = target(attachment)
        val result = directory.uploadAttachment(peer, attachment, source, progress)
        val cached = received.destination(result.id)
        try {
            MediaDiskCache.gate(cached).withLock {
                if (!received.reuse(result.id, result.size, cached)) {
                    val staged = File(cached.parentFile, "${cached.name}.upload.part")
                    try {
                        source.copyTo(staged, overwrite = true)
                        if (staged.length() == result.size && staged.renameTo(cached)) received.prune(cached)
                    } finally { staged.delete() }
                }
            }
        } catch (failure: java.io.IOException) {
            android.util.Log.w("SalomMedia", "Could not keep local copy of uploaded file", failure)
        }
        result
    }
    suspend fun saveLocal(attachment: Attachment): Attachment = persistForQueue(attachment)

    suspend fun persistForQueue(attachment: Attachment): Attachment = withContext(Dispatchers.IO) {
        val photos = attachment.photos.map { photo ->
            val result = photo.copy(local = true)
            val source = target(photo)
            if (!source.isFile) throw DirectoryException("Файл вложения уже недоступен")
            if (!photo.local) source.copyTo(target(result), overwrite = true)
            result
        }
        photos.first().copy(extraPhotos = photos.drop(1))
    }
    suspend fun file(attachment: Attachment, progress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val draft = target(attachment)
        if (attachment.local) {
            if (draft.isFile && draft.length() == attachment.size) return@withContext draft
            throw DirectoryException("Локальный файл недоступен")
        }
        val file = received.destination(attachment.id)
        MediaDiskCache.gate(file).withLock {
            if (!received.reuse(attachment.id, attachment.size, file)) {
                downloads.withPermit {
                    if (!photos.restoreChat(attachment, file)) directory.downloadAttachment(attachment, file, progress)
                }
                received.prune(file)
            }
            file
        }
    }
    suspend fun preview(attachment: Attachment, maximumEdge: Int): Bitmap? {
        val key = "${directory.ownId}:${attachment.id}:$maximumEdge"
        val job = synchronized(inFlight) {
            thumbnails.get(key)?.let { return it }
            inFlight[key] ?: previewScope.async(start = CoroutineStart.LAZY) {
                bitmap(attachment, maximumEdge)?.also { thumbnails.put(key, it) }
            }.also { deferred ->
                inFlight[key] = deferred
                deferred.invokeOnCompletion { synchronized(inFlight) { inFlight.remove(key, deferred) } }
            }
        }
        job.start()
        return job.await()
    }

    suspend fun bitmap(attachment: Attachment, maximumEdge: Int = 1000) = withContext(Dispatchers.IO) {
        val file = file(attachment)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maximumEdge || bounds.outHeight / sample > maximumEdge) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    suspend fun removeLocal(attachment: Attachment) = withContext(Dispatchers.IO) { attachment.photos.forEach { target(it).delete() }; Unit }
    suspend fun open(attachment: Attachment, progress: (Float) -> Unit = {}) {
        val file = file(attachment, progress)
        val uri = FileProvider.getUriForFile(context, "com.mukimov.salom.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_TITLE, attachment.name).apply { clipData = android.content.ClipData.newRawUri(attachment.name, uri) }
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) { throw DirectoryException("На телефоне нет приложения для открытия этого файла") }
    }
    private fun optimize(file: File, photo: Attachment): Attachment {
        if (Build.VERSION.SDK_INT < 28 || photo.mime !in setOf("image/jpeg", "image/png") || photo.size < 350_000) return photo
        val encoded = File(file.parentFile, "${file.name}.encode.part")
        try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val ratio = minOf(1.0, 1920.0 / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val compressed = try {
                !bitmap.hasAlpha() && encoded.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            } finally { bitmap.recycle() }
            val bytes = encoded.length()
            if (compressed && bytes in 1L..(photo.size * 9 / 10) && encoded.renameTo(file)) {
                return photo.copy(name = photo.name.substringBeforeLast('.') + ".jpg", mime = "image/jpeg", size = bytes)
            }
            return photo
        } catch (_: java.io.IOException) {
            return photo
        } finally { encoded.delete() }
    }

    companion object {
        const val MAX_FILE = 25L * 1024 * 1024
        private val downloads = Semaphore(3)
        private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val inFlight = mutableMapOf<String, Deferred<Bitmap?>>()
        private val thumbnails = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
    }
}
