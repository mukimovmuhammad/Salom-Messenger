package com.mukimov.salom

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SalomPhotos(private val context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("salom_gallery", Context.MODE_PRIVATE)
    private val cache = File(context.cacheDir, "avatars")

    var autoSave: Boolean
        get() = prefs.getBoolean("auto_save_own_chat_photos", false)
        set(value) { prefs.edit().putBoolean("auto_save_own_chat_photos", value).apply() }

    private fun key(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun avatarSource(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val scale = 512.0 / maxOf(info.size.width, info.size.height, 512)
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            require(options.outWidth > 0 && options.outHeight > 0)
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 768) options.inSampleSize *= 2
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: error("Не удалось открыть фотографию")
        }
    }

    suspend fun avatarJpeg(bitmap: Bitmap, crop: AvatarCrop): ByteArray = withContext(Dispatchers.IO) {
        val region = crop.region(bitmap)
        val square = Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height())
        java.io.ByteArrayOutputStream().use { output ->
            check(square.compress(Bitmap.CompressFormat.JPEG, 85, output))
            output.toByteArray().also { require(it.size <= 512 * 1024) }
        }
    }

    suspend fun rememberAvatar(url: String, jpeg: ByteArray) = withContext(Dispatchers.IO) {
        mutex.withLock { cache.mkdirs(); File(cache, key(url)).writeBytes(jpeg) }
    }

    private fun allowedAvatar(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return false
        return when (uri.host) {
            "lh3.googleusercontent.com", "googleusercontent.com" -> true
            "mukimov.site" -> uri.rawPath.matches(Regex("/salom/api/avatars/[a-f0-9-]{36}/[a-f0-9]{64}\\.jpg"))
            else -> false
        }
    }

    private fun galleryBytes(id: String): ByteArray? {
        val source = prefs.getString("uri:$id", null)?.let(Uri::parse) ?: return null
        return try { resolver.openInputStream(source)?.use { it.readBytes() } }
        catch (_: IOException) { null }
        catch (_: SecurityException) { null }
    }

    suspend fun avatar(url: String): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = avatarBytes(url) ?: return@withContext null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private suspend fun avatarBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!allowedAvatar(url)) return@withContext null
        mutex.withLock {
            cache.mkdirs()
            val id = key(url)
            val target = File(cache, id)
            if (target.isFile) return@withLock target.readBytes()
            val saved = galleryBytes(id)
            val data = saved ?: run {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    instanceFollowRedirects = false
                }
                try {
                    if (conn.responseCode != 200) return@withLock null
                    conn.inputStream.use { input ->
                        val result = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (result.size() + count > 2 * 1024 * 1024) return@withLock null
                            result.write(buffer, 0, count)
                        }
                        result.toByteArray()
                    }
                } finally { conn.disconnect() }
            }
            target.writeBytes(data)
            cache.listFiles()?.sortedByDescending(File::lastModified)?.drop(160)?.forEach { it.delete() }
            data
        }
    }

    suspend fun saveAvatar(url: String) {
        val bitmap = avatar(url) ?: error("Фото профиля недоступно")
        withContext(Dispatchers.IO) {
            save(key(url), "Avatars", "image/jpeg", automatic = true) {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
            }
        }
    }

    suspend fun restoreChat(attachment: Attachment, destination: File): Boolean = withContext(Dispatchers.IO) {
        if (!galleryMime(attachment.mime)) return@withContext false
        mutex.withLock {
            val data = galleryBytes(key(attachment.id)) ?: return@withLock false
            if (data.size.toLong() != attachment.size) return@withLock false
            destination.writeBytes(data)
            true
        }
    }

    suspend fun saveChat(attachment: Attachment, file: File, automatic: Boolean) = withContext(Dispatchers.IO) {
        require(galleryMime(attachment.mime))
        save(key(attachment.id), "Chat Images", attachment.mime, automatic) { output ->
            file.inputStream().use { it.copyTo(output) }
        }
    }

    private suspend fun save(id: String, album: String, mime: String, automatic: Boolean, write: (java.io.OutputStream) -> Unit) {
        check(Build.VERSION.SDK_INT >= 29) { "Папки SALOM в галерее доступны с Android 10" }
        mutex.withLock {
            val existing = prefs.getString("uri:$id", null)?.let(Uri::parse)
            if (existing != null) {
                // Leave a record when a saved photo is deleted. Only an explicit save can recreate it.
                if (automatic) return@withLock
                try {
                    if (resolver.openFileDescriptor(existing, "r")?.use { true } == true) return@withLock
                } catch (_: IOException) { }
                catch (_: SecurityException) { }
            }
            val extension = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "SALOM-$id.$extension")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SALOM/$album/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать фото в галерее")
            try {
                resolver.openOutputStream(uri)?.use(write) ?: error("Не удалось записать фото")
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
                check(prefs.edit().putString("uri:$id", uri.toString()).commit())
            } catch (failure: Exception) {
                resolver.delete(uri, null, null)
                throw failure
            }
        }
    }

    companion object {
        private val mutex = Mutex()
        fun galleryMime(mime: String) = mime in setOf("image/jpeg", "image/png", "image/webp")
    }
}
