package dev.polybridge.app

import dev.polybridge.app.audio.VoiceClips
import dev.polybridge.app.audio.VoicePcm
import dev.polybridge.app.audio.pcm24
import dev.polybridge.core.*
import dev.polybridge.qwen.QwenOptions
import dev.polybridge.qwen.ModelIds
import org.junit.Assert.*
import org.junit.Test

class VoiceClipsTest {
    @Test fun changingRegionInvalidatesIndependentVoiceCache() {
        val clips = VoiceClips()
        val turn = dev.polybridge.core.Turn("one", dev.polybridge.core.Side.FRENCH)
        val options = dev.polybridge.qwen.QwenOptions()
        assertNotEquals(clips.key(turn, options), clips.key(turn, options.copy(region = dev.polybridge.qwen.Region.SINGAPORE)))
    }
    @Test fun echoReferenceFollowsTheActualPlaybackRate() {
        val input = byteArrayOf(0, 0, -24, 3, -48, 7, -72, 11)
        assertSame(input, pcm24(input, 24000))
        assertEquals(12, pcm24(input, 16000).size)
        assertEquals(4, pcm24(input, 48000).size)
    }
    @Test fun realTimeSpeechNeverMasqueradesAsTheSelectedTtsAndSwitchesInvalidateReuse() {
        val cache = VoiceClips()
        val turn = Turn("live", Side.FRENCH, TurnStatus.READY, "Bonjour", "你好", model = ModelTrace(ModelIds.AUDIO31, voice = "longanqian_v3.1"))
        val options = QwenOptions(utteranceModel = ModelIds.AUDIO31)
        val wave = byteArrayOf(1, 2)
        cache.putGenerated(turn, options, wave)
        assertNull(cache[cache.key(turn, options)])
        assertSame(wave, cache.find(turn, options)?.wave)
        assertEquals(ModelIds.AUDIO31, cache.find(turn, options)?.model)
        assertNull(cache.find(turn, options.copy(utteranceModel = ModelIds.LIVE38)))
        assertNull(cache.find(turn, options.copy(voiceModel = ModelIds.TTS_INSTRUCT)))
        assertNull(cache.find(turn.copy(translatedText = "早上好"), options))
    }
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
