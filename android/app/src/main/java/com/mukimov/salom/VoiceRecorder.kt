package com.mukimov.salom

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Owns the microphone only between explicit start and stop/cancel. Never survives the chat lifecycle. */
internal class VoiceRecorder(private val context: Context, private val media: MediaFiles) {
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private var recorder: MediaRecorder? = null
    private var output: Pair<Attachment, File>? = null
    private var startedAt = 0L
    private val peaks = mutableListOf<Int>()
    @Volatile var reachedLimit = false
        private set
    @Suppress("DEPRECATION")
    suspend fun start() = withContext(worker) {
        synchronized(this@VoiceRecorder) {
            cancel()
            val target = media.newVoiceFile()
            val instance = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            try {
                instance.setAudioSource(MediaRecorder.AudioSource.MIC)
                instance.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                instance.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                instance.setAudioChannels(1); instance.setAudioSamplingRate(44100); instance.setAudioEncodingBitRate(96000)
                instance.setOutputFile(target.second.path)
                instance.setMaxDuration(VoicePolicy.MAX_MS.toInt())
                instance.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) synchronized(this@VoiceRecorder) { if (recorder === instance) reachedLimit = true } }
                instance.prepare(); instance.start()
                recorder = instance; output = target; startedAt = SystemClock.elapsedRealtime(); reachedLimit = false; peaks.clear()
            } catch (failure: Exception) {
                android.util.Log.w("SalomVoice", "MediaRecorder could not start", failure)
                runCatching { instance.release() }; target.second.delete()
                throw DirectoryException("Не удалось включить микрофон. Проверь разрешение и повтори")
            }
        }
    }
    @Synchronized fun elapsed() = if (recorder == null) 0L else (SystemClock.elapsedRealtime() - startedAt).coerceAtMost(VoicePolicy.MAX_MS)
    @Synchronized fun sample(): Int {
        val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        if (recorder != null && peaks.size < 3000) peaks.add(amplitude)
        return VoicePolicy.waveform(listOf(amplitude)).first()
    }
    suspend fun finish(): Attachment = withContext(worker) {
        synchronized(this@VoiceRecorder) {
            val target = output ?: throw DirectoryException("Запись уже остановлена")
            val samples = peaks.toList()
            val instance = recorder
            recorder = null; output = null
            try {
                runCatching { instance?.stop() }
            } finally { runCatching { instance?.release() } }
            val reader = MediaMetadataRetriever()
            val duration = try { reader.setDataSource(target.second.path); reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
                catch (_: Exception) { 0L } finally { reader.release() }
            if (duration < VoicePolicy.MIN_MS || target.second.length() == 0L) { target.second.delete(); throw DirectoryException("Запиши хотя бы одну секунду") }
            target.first.copy(size = target.second.length(), durationMs = duration.coerceAtMost(VoicePolicy.MAX_MS), waveform = VoicePolicy.waveform(samples))
        }
    }
    fun cancelAsync() { cleanup.launch(worker) { cancel() } }

    private companion object { val cleanup = CoroutineScope(SupervisorJob()) }

    @Synchronized private fun cancel() {
        val previous = recorder; recorder = null
        runCatching { previous?.stop() }; runCatching { previous?.release() }
        output?.second?.delete(); output = null; peaks.clear(); reachedLimit = false
    }
}
