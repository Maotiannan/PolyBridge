package dev.polybridge.app

import android.content.Context
import dev.polybridge.qwen.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelSettingsTest {
    @Test fun draftIsNotSavedUntilSaveAndInvalidWorkspaceChangesNothing() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = ctx.getSharedPreferences("translator", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("credential", "fixture-not-a-real-key").commit()
        val settings = TranslationSettings(ctx)
        val draft = settings.state.value.models.selectPreset(ModelPreset.AUDIO31)
        assertEquals(ModelPreset.CURRENT, TranslationSettings(ctx).state.value.models.preset)
        settings.save(Region.BEIJING, "", "", draft)
        assertEquals(draft, TranslationSettings(ctx).state.value.models)
        val before = prefs.all
        assertThrows(IllegalArgumentException::class.java) { settings.save(Region.BEIJING, "", "", ModelSelection(ModelPreset.LIVE38)) }
        assertEquals(before, prefs.all)
        assertEquals(draft, settings.state.value.models)
    }

    @Test fun unknownSavedModelCannotSilentlyCallDefault() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("translator", Context.MODE_PRIVATE).edit().putString("models", "{\"version\":999}").commit()
        val settings = TranslationSettings(ctx)
        assertNotNull(settings.state.value.modelError)
        assertThrows(IllegalArgumentException::class.java) { settings.state.value.options(ModelFeature.CAPTIONS) }
    }
    @Test fun savedModelsActuallyReachProviderOptionsAfterRestart() {
        val ctx = RuntimeEnvironment.getApplication()
        val selection = ModelSelection(ModelPreset.AUDIO31).override(ModelFeature.VOICE, ModelIds.TTS_INSTRUCT)
        ctx.getSharedPreferences("translator", Context.MODE_PRIVATE).edit().putString("models", selection.encode()).commit()
        assertEquals(ModelIds.TTS_INSTRUCT, TranslationSettings(ctx).state.value.cloud.voiceModel)
        assertEquals(ModelIds.AUDIO31, TranslationSettings(ctx).state.value.cloud.utteranceModel)
    }
}
