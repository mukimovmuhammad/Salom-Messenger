package com.mukimov.salom
import org.junit.Assert.*
import org.junit.Test
class VoicePolicyTest {
    @Test fun holdingContinuesAndReleaseSends() {
        assertEquals(VoiceHoldAction.CONTINUE, VoicePolicy.holdAction(12f, 64f, true, false))
        assertEquals(VoiceHoldAction.RELEASE, VoicePolicy.holdAction(12f, 64f, false, false))
    }

    @Test fun cancellationWinsEvenWhenSwipeAndReleaseArriveTogether() {
        assertEquals(VoiceHoldAction.CANCEL, VoicePolicy.holdAction(64f, 64f, true, false))
        assertEquals(VoiceHoldAction.CANCEL, VoicePolicy.holdAction(64f, 64f, false, false))
        assertEquals(VoiceHoldAction.RELEASE, VoicePolicy.holdAction(-80f, 64f, false, false))
    }

    @Test fun cancelledGestureNeverSends() {
        assertEquals(VoiceHoldAction.CANCEL, VoicePolicy.holdAction(0f, 64f, false, true))
    }

    @Test fun waveformIsBoundedAndSilenceIsNotInvented() {
        assertEquals(List(32){0}, VoicePolicy.waveform(emptyList()))
        assertEquals(List(32){0}, VoicePolicy.waveform(List(20){0}))
        val values=VoicePolicy.waveform(listOf(-1,1,30,1000,32767,90000))
        assertEquals(32,values.size)
        assertTrue(values.all { it in 0..100 })
        assertTrue(values.contains(100))
    }
    @Test fun briefAndLongRecordingsHaveUsableWaveforms() {
        assertEquals(List(32){100}, VoicePolicy.waveform(listOf(32767)))
        val quiet=VoicePolicy.waveform(List(1200){100})
        val loud=VoicePolicy.waveform(List(1200){10000})
        assertTrue(loud.average()>quiet.average())
    }
    @Test fun emojiPresentationNeverMistakesWordsForStickers() {
        assertTrue(VoicePolicy.emojiOnly("👍"))
        assertTrue(VoicePolicy.emojiOnly("😊 💛"))
        assertFalse(VoicePolicy.emojiOnly("Салом 😊"))
        assertFalse(VoicePolicy.emojiOnly("123"))
        assertFalse(VoicePolicy.emojiOnly(""))
        assertFalse(VoicePolicy.emojiOnly("👍👍👍👍"))
    }
}
