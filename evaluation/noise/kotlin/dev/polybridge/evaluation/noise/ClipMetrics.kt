package dev.polybridge.evaluation.noise

import kotlin.math.*

data class VoiceSpan(val startSample: Int, val endSample: Int, val language: String)
data class TurnScore(val language: String, val retained: Double, val openingRetained: Double,
    val endingRetained: Double, val gainDb: Double, val siSdrDb: Double)

/** Acoustic diagnostics, not recognition or translation accuracy. Never used by the shipped app. */
fun scoreTurns(reference: ShortArray, output: ShortArray, spans: List<VoiceSpan>): List<TurnScore> {
    require(reference.size == output.size)
    return spans.map { span ->
        require(span.startSample >= 0 && span.startSample < span.endSample && span.endSample <= reference.size)
        val indices = span.startSample until span.endSample
        val rms = sqrt(indices.sumOf { reference[it].toDouble().pow(2) } / indices.count())
        val active = indices.filter { abs(reference[it].toInt()) > max(10.0, rms * .2) }
        require(active.isNotEmpty()) { "speech reference has no measurable voice" }
        fun retention(at: List<Int>) = at.count { output[it] != 0.toShort() }.toDouble() / at.size
        val opening = active.filter { it < active.first() + 4000 }
        val ending = active.filter { it > active.last() - 4000 }
        val inputPower = indices.sumOf { reference[it].toDouble().pow(2) }
        val outputPower = indices.sumOf { output[it].toDouble().pow(2) }
        TurnScore(span.language, retention(active), retention(opening), retention(ending),
            10 * log10((outputPower + .001) / (inputPower + .001)),
            waveformSimilarity(reference.copyOfRange(span.startSample, span.endSample),
                output.copyOfRange(span.startSample, span.endSample)))
    }
}

fun waveformSimilarity(reference: ShortArray, output: ShortArray): Double {
    require(reference.size == output.size && reference.isNotEmpty())
    val meanReference = reference.average()
    val meanOutput = output.average()
    val a = reference.map { it.toDouble() - meanReference }
    val b = output.map { it.toDouble() - meanOutput }
    val gain = a.indices.sumOf { a[it] * b[it] } / max(.001, a.sumOf { it * it })
    return 10 * log10((a.sumOf { (it * gain).pow(2) } + .001) /
        (a.indices.sumOf { (b[it] - a[it] * gain).pow(2) } + .001))
}
