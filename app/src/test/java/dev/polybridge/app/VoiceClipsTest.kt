package dev.polybridge.app

import dev.polybridge.app.audio.VoiceClips
import dev.polybridge.app.audio.VoicePcm
import dev.polybridge.core.*
import dev.polybridge.qwen.QwenOptions
import org.junit.Assert.*
import org.junit.Test

class VoiceClipsTest {
    @Test fun thePcmPlayerReadsExactlyTheDeclaredVoiceFrames() {
        val wave = waveFromPcm(24000, ByteArray(4800) { it.toByte() })
        val pcm = requireNotNull(VoicePcm.wave(wave))
        assertEquals(44, pcm.offset); assertEquals(4800, pcm.length); assertEquals(24000, pcm.rate)
        assertNull(VoicePcm.wave(wave.copyOf(200)))
        val stereo = wave.clone().apply { this[22] = 2 }
        assertEquals(2, VoicePcm.wave(stereo)?.channels)
    }
    @Test fun aRevisionOrVoiceChangeCannotReplayStaleSpeechAndTheCacheIsBounded() {
        val cache = VoiceClips(8)
        val turn = Turn("a", Side.FRENCH, TurnStatus.READY, "Deux.", "两个。")
        val options = QwenOptions()
        val first = cache.key(turn, options)
        val changed = cache.key(turn.copy(translatedText = "三份。"), options)
        val otherVoice = cache.key(turn, options.copy(voice = "Ethan"))
        val audio = ByteArray(6) { 1 }
        cache.put(first, audio)
        assertSame(audio, cache[first])
        assertNull(cache[changed]); assertNull(cache[otherVoice])
        cache.put(changed, ByteArray(4))
        assertNull(cache[first]); assertNotNull(cache[changed])
        cache.clear()
        assertNull(cache[changed])
    }
}
