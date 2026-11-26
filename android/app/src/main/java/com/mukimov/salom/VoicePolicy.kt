package com.mukimov.salom

import kotlin.math.log10
import kotlin.math.max

internal enum class VoiceHoldAction { CONTINUE, RELEASE, CANCEL }

internal object VoicePolicy {
    const val MIN_MS = 1000L
    const val MAX_MS = 300_000L
    fun holdAction(leftDistance: Float, threshold: Float, pressed: Boolean, interrupted: Boolean): VoiceHoldAction = when {
        interrupted || leftDistance >= threshold -> VoiceHoldAction.CANCEL
        !pressed -> VoiceHoldAction.RELEASE
        else -> VoiceHoldAction.CONTINUE
    }
    fun waveform(peaks: List<Int>): List<Int> = List(32) { bucket ->
        if (peaks.isEmpty()) 0 else {
            val from = bucket * peaks.size / 32
            val end = max(from + 1, (bucket + 1) * peaks.size / 32).coerceAtMost(peaks.size)
            val peak = peaks.subList(from.coerceAtMost(peaks.lastIndex), end).maxOrNull()?.coerceIn(0, 32767) ?: 0
            if (peak == 0) 0 else (((20 * log10(peak / 32767.0) + 60) / 60) * 100).toInt().coerceIn(0, 100)
        }
    }
    fun emojiOnly(text: String): Boolean {
        val codePoints = text.trim().codePoints().toArray()
        var count = 0
        if (codePoints.isEmpty()) return false
        for (code in codePoints) when {
            code == 0xFE0F || code == 0x200D || code in 0x1F3FB..0x1F3FF || Character.isWhitespace(code) -> Unit
            code in 0x1F000..0x1FAFF || code in 0x2600..0x27BF -> count++
            else -> return false
        }
        return count in 1..3
    }
}
internal fun audioTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
