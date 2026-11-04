package dev.polybridge.qwen

import org.junit.Assert.*
import org.junit.Test

class ModelSelectionTest {
    @Test fun presetsResolveAllFeaturesAndOverridesStayLocal() {
        val base = ModelSelection(ModelPreset.AUDIO31)
        for (feature in listOf(ModelFeature.FACE, ModelFeature.REPLY, ModelFeature.LISTEN, ModelFeature.CAPTIONS))
            assertEquals(ModelIds.AUDIO31, base.model(feature))
        assertEquals(ModelIds.MT, base.model(ModelFeature.TEXT))
        val custom = base.override(ModelFeature.CAPTIONS, ModelIds.LIVE38)
        assertEquals(ModelIds.AUDIO31, custom.model(ModelFeature.LISTEN))
        assertEquals(ModelIds.LIVE38, custom.model(ModelFeature.CAPTIONS))
        assertTrue(custom.selectPreset(ModelPreset.CURRENT).overrides.isEmpty())
        assertEquals(ModelIds.AUDIO31, custom.override(ModelFeature.CAPTIONS, null).model(ModelFeature.CAPTIONS))
    }

    @Test fun roundTripRejectsUnknownModelsAndIncompatibleFeatures() {
        val choice = ModelSelection(ModelPreset.OMNI38).override(ModelFeature.TEXT, ModelIds.MT_PLUS)
        assertEquals(choice, ModelSelection.decode(choice.encode()))
        assertEquals(ModelSelection(), ModelSelection.decode(null))
        assertThrows(IllegalArgumentException::class.java) { choice.override(ModelFeature.TEXT, ModelIds.LIVE38) }
        assertThrows(IllegalArgumentException::class.java) { ModelSelection.decode("""{"version":99}""") }
        assertThrows(IllegalArgumentException::class.java) { ModelSelection.decode("""{"version":1,"preset":"BOGUS","overrides":{}}""") }
    }

    @Test fun optionsAreImmutableAndRespectEachOperation() {
        val original = ModelSelection().override(ModelFeature.REPLY, ModelIds.AUDIO31)
            .override(ModelFeature.VOICE, ModelIds.TTS_INSTRUCT)
        val snapshot = original.options(ModelFeature.REPLY, QwenOptions())
        original.selectPreset(ModelPreset.LIVE38)
        assertEquals(ModelIds.AUDIO31, snapshot.utteranceModel)
        assertEquals(ModelIds.TTS_INSTRUCT, snapshot.voiceModel)
        assertEquals(ModelIds.PIPELINE, original.options(ModelFeature.FACE, QwenOptions()).utteranceModel)
    }

    @Test fun workspaceRequirementIsValidatedBeforeStarting() {
        assertThrows(IllegalArgumentException::class.java) { ModelSelection(ModelPreset.OMNI38).validate(Region.BEIJING, "") }
        ModelSelection(ModelPreset.OMNI38).validate(Region.BEIJING, "example-space")
        ModelSelection().validate(Region.BEIJING, "")
    }
}
