package dev.polybridge.core

import kotlin.math.log10

/** Normalized RMS for one 20 ms capture frame. Fast attack, smooth decay and an exact silent floor. */
class InputLevel {
    private var level = 0f
    fun read(samples: ShortArray, length: Int): Float {
        var energy = 0.0
        for (i in 0 until length) energy += samples[i].toDouble() * samples[i]
        val db = if (energy > 0 && length > 0) 10 * log10(energy / length / (32768.0 * 32768)) else -90.0
        val value = ((db + 65) / 58).coerceIn(0.0, 1.0).toFloat()
        level += (value - level) * if (value > level) .75f else .17f
        if (level < .012f) level = 0f
        return level
    }
}

/** Coordinates are relative to the top of the button, in the speaker's own orientation. */
class HoldSlide {
    private var armed = false
    fun move(yDp: Float): Boolean {
        armed = if (armed) yDp < 0f else yDp <= -16f
        return armed
    }
}
