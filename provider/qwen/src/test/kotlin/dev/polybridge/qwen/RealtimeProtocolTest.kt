package dev.polybridge.qwen

import com.google.gson.JsonParser
import dev.polybridge.core.Side
import org.junit.Assert.*
import org.junit.Test

class RealtimeProtocolTest {
    @Test fun eachProtocolUsesItsOwnFieldsAndCaptionsNeverRequestAudio() {
        for (model in ModelCatalog.realtime) {
            val session = JsonParser.parseString(RealtimeProtocol.configure(QwenOptions(liveModel = model.id), true, Side.FRENCH)).asJsonObject["session"].asJsonObject
            val key = if (model.id == ModelIds.LIVE38) "output_modalities" else "modalities"
            assertEquals(listOf("text"), session[key].asJsonArray.map { it.asString })
            assertFalse(session.has(if (key == "modalities") "output_modalities" else "modalities"))
            if (model.id == ModelIds.AUDIO31 || model.id == ModelIds.OMNI38) assertTrue(session["instructions"].asString.contains("不回答"))
            else assertEquals("zh", session["translation"].asJsonObject["language"].asString)
        }
    }
    @Test fun conversationalModelsNeedOneConnectionAndHeldDirectionNeedsOne() {
        assertEquals(1, RealtimeProtocol.routes(ModelIds.AUDIO31, null).size)
        assertEquals(2, RealtimeProtocol.routes(ModelIds.LIVE38, null).size)
        assertEquals(listOf(Side.CHINESE), RealtimeProtocol.routes(ModelIds.LIVE35, Side.CHINESE))
    }
}
