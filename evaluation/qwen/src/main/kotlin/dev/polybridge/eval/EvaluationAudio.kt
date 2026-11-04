package dev.polybridge.eval

object EvaluationAudio {
    fun withPause(first: ByteArray, second: ByteArray, pauseMs: Int): ByteArray {
        require(first.isNotEmpty() && second.isNotEmpty() && first.size % 2 == 0 && second.size % 2 == 0)
        require(pauseMs in 0..15000)
        require(first.size.toLong() + second.size + pauseMs * 32L <= 60 * 32000)
        return first + ByteArray(pauseMs * 32) + second
    }
}
