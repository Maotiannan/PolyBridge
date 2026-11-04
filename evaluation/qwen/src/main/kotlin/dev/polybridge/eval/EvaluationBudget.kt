package dev.polybridge.eval

data class BudgetEntry(val id: String, val reservedMicros: Long, val actualMicros: Long? = null, val settled: Boolean = false,
    val revokedEstimateMicros: Long? = null, val estimateIssue: String? = null,
    val previousReservationMicros: Long? = null, val reservationEvidence: String? = null)
data class BudgetState(val limitMicros: Long = 10_000_000, val entries: List<BudgetEntry> = emptyList(), val halted: Boolean = false)
class EvaluationBudget(initial: BudgetState, private val save: (BudgetState) -> Unit) {
    private var state = initial
    init {
        require(initial.limitMicros in 1..10_000_000)
        require(initial.entries.map { it.id }.distinct().size == initial.entries.size)
        require(initial.entries.all { it.id.isNotBlank() && it.reservedMicros > 0 &&
            (it.actualMicros == null || it.actualMicros >= 0) && (it.actualMicros == null || it.settled) })
        require(initial.halted || total(initial) <= initial.limitMicros)
    }
    private fun total(value: BudgetState) = value.entries.fold(0L) { sum, entry ->
        Math.addExact(sum, entry.actualMicros ?: entry.reservedMicros)
    }
    val reservedMicros: Long @Synchronized get() = total(state)
    @Synchronized fun reviseReservation(id: String, amount: Long, evidence: String) {
        require(amount > 0 && evidence.matches(Regex("[A-Z0-9_]{1,80}")))
        val entry = state.entries.single { it.id == id }
        check(entry.settled && entry.actualMicros == null && entry.previousReservationMicros == null)
        val next = state.copy(entries = state.entries.map {
            if (it.id == id) it.copy(reservedMicros = amount, previousReservationMicros = it.reservedMicros,
                reservationEvidence = evidence) else it
        })
        check(total(next) <= next.limitMicros)
        save(next)
        state = next
    }
    @Synchronized fun revokeEstimate(id: String, reason: String) {
        require(reason.matches(Regex("[A-Z_]{1,80}")))
        val entry = state.entries.single { it.id == id }
        if (entry.actualMicros == null) return
        val next = state.copy(entries = state.entries.map {
            if (it.id == id) it.copy(actualMicros = null, revokedEstimateMicros = it.actualMicros, estimateIssue = reason) else it
        })
        val checked = next.copy(halted = next.halted || total(next) > next.limitMicros)
        save(checked)
        state = checked
    }
    @Synchronized fun reserve(id: String, amount: Long) {
        require(id.isNotBlank() && amount > 0)
        check(!state.halted && state.entries.none { it.id == id })
        val next = state.copy(entries = state.entries + BudgetEntry(id, amount))
        check(total(next) <= next.limitMicros) { "Evaluation budget exhausted" }
        save(next)
        state = next
    }
    @Synchronized fun settle(id: String, actual: Long?) {
        require(actual == null || actual >= 0)
        val entry = state.entries.single { it.id == id }
        check(!entry.settled)
        val next = state.copy(entries = state.entries.map { if (it.id == id) it.copy(actualMicros = actual, settled = true) else it },
            halted = state.halted || (actual != null && actual > entry.reservedMicros))
        save(next)
        state = next
    }
}
