package dev.polybridge.audio

/** Local model/DSP failure, never a cloud connection error. Does not include captured content. */
class AudioProcessingFailure(cause: Throwable) : Exception("Local audio processing failed", cause)

internal inline fun <T> audioOperation(action: () -> T): T = try { action() }
    catch (failure: AudioProcessingFailure) { throw failure }
    catch (failure: Exception) { throw AudioProcessingFailure(failure) }
    catch (failure: LinkageError) { throw AudioProcessingFailure(failure) }
