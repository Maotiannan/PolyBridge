package dev.polybridge.app.audio

import android.content.Context
import dev.polybridge.audio.AudioProcessingFailure
import dev.polybridge.audio.SpeechFilter
import dev.polybridge.core.LiveSpeechSession

internal typealias ListeningFilter = dev.polybridge.audio.ListeningFilter

/** Android asset boundary only; the production DSP is also exercised with real models on the JVM. */
internal class SpeechGate(context: Context, link: LiveSpeechSession, enhanced: Boolean = false) :
    ListeningFilter by loadFilter(context, link, enhanced)

private fun loadFilter(context: Context, link: LiveSpeechSession, enhanced: Boolean): ListeningFilter = try {
    val voice = context.assets.open("speech-detection/silero-16k.onnx").use { it.readBytes() }
    val noise = if (enhanced) context.assets.open("noise-reduction/gtcrn.onnx").use { it.readBytes() } else null
    SpeechFilter(voice, link, noise)
} catch (failure: AudioProcessingFailure) { throw failure }
catch (failure: Exception) { throw AudioProcessingFailure(failure) }
catch (failure: LinkageError) { throw AudioProcessingFailure(failure) }
