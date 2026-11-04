package dev.polybridge.eval

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class EvaluationProtocolTest {
    @Test fun candidatesHaveTheirOwnTextOnlyProtocolAndNoSourceLanguageSpoiler() {
        for (model in Candidate.entries.filter { it != Candidate.ASR31 }) {
            val session = JsonParser.parseString(EvaluationProtocol.configure(model, "zh", "task")).asJsonObject["session"].asJsonObject
            val key = if (model == Candidate.LIVE38) "output_modalities" else "modalities"
            assertEquals("[\"text\"]", session[key].toString())
            assertFalse(session.has(if (key == "modalities") "output_modalities" else "modalities"))
            assertFalse(session["input_audio_transcription"]?.asJsonObject?.has("language") == true)
        }
    }
    @Test fun asrUsesTaskProtocolAndOnlyChineseFrenchLanguageHints() {
        val start = JsonParser.parseString(EvaluationProtocol.configure(Candidate.ASR31, "zh", "fixed-id")).asJsonObject
        assertEquals("run-task", start["header"].asJsonObject["action"].asString)
        assertEquals("fixed-id", start["header"].asJsonObject["task_id"].asString)
        assertEquals("[\"zh\",\"fr\"]", start["payload"].asJsonObject["parameters"].asJsonObject["language_hints"].toString())
        assertEquals("finish-task", JsonParser.parseString(EvaluationProtocol.finish(Candidate.ASR31, "fixed-id")!!).asJsonObject["header"].asJsonObject["action"].asString)
    }
    @Test fun priceUsesActualModalityAndRegionInsteadOfCountingCalls() {
        assertEquals(705L, Candidate.AUDIO31.costMicros(Usage(inputAudio = 100, inputText = 10, outputText = 20), false))
        assertEquals(7500L, Candidate.LIVE38.costMicros(Usage(inputAudio = 100, outputText = 35), false))
        assertTrue(Candidate.LIVE38.costMicros(Usage(inputAudio = 100, outputText = 35), true)!! > 7500L)
    }

    @Test fun undocumentedLiveTextInputPriceIsNotInvented() {
        assertNull(Candidate.LIVE35.costMicros(Usage(inputAudio = 28, inputText = 64, outputText = 14), false))
    }
}
