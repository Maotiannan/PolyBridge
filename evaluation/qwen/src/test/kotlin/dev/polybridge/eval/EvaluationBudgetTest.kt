package dev.polybridge.eval

import org.junit.Assert.*
import org.junit.Test

class EvaluationBudgetTest {
    @Test fun externalUsageObservationCanReviseOnlyUnknownReservationsWithAudit() {
        var saved = BudgetState()
        val budget = EvaluationBudget(saved) { saved = it }
        budget.reserve("failed", 3_000_000)
        budget.settle("failed", null)
        budget.reviseReservation("failed", 300_000, "USER_REPORTED_NEGLIGIBLE")
        assertEquals(300_000L, budget.reservedMicros)
        assertEquals(3_000_000L, saved.entries.single().previousReservationMicros)
        assertEquals("USER_REPORTED_NEGLIGIBLE", saved.entries.single().reservationEvidence)
        assertNull(saved.entries.single().actualMicros)
        assertThrows(IllegalStateException::class.java) { budget.reviseReservation("failed", 1, "SECOND_REVISION") }
    }

    @Test fun unverifiedEstimateCanBeRevokedWithoutErasingItsAuditTrail() {
        var saved = BudgetState()
        val budget = EvaluationBudget(saved) { saved = it }
        budget.reserve("first", 3_000_000)
        budget.settle("first", 5080)
        budget.revokeEstimate("first", "UNPRICED_INPUT_TEXT")
        assertEquals(3_000_000L, budget.reservedMicros)
        assertEquals(5080L, saved.entries.single().revokedEstimateMicros)
        assertEquals("UNPRICED_INPUT_TEXT", saved.entries.single().estimateIssue)
        val restored = EvaluationBudget(saved) {}
        assertEquals(3_000_000L, restored.reservedMicros)
        restored.revokeEstimate("first", "UNPRICED_INPUT_TEXT")
        assertEquals(3_000_000L, restored.reservedMicros)
    }

    @Test fun reservesBeforeSpendingAndSurvivesRestart() {
        var saved = BudgetState()
        val budget = EvaluationBudget(saved) { saved = it }
        budget.reserve("first", 6_000_000)
        assertEquals(6_000_000L, saved.entries.single().reservedMicros)
        val restored = EvaluationBudget(saved) { saved = it }
        assertEquals(6_000_000L, restored.reservedMicros)
        assertThrows(IllegalStateException::class.java) { restored.reserve("second", 5_000_000) }
        assertThrows(IllegalStateException::class.java) { restored.reserve("first", 1) }
    }

    @Test fun missingUsageRetainsFullReservationAndSettlementIsOneTime() {
        val budget = EvaluationBudget(BudgetState()) {}
        budget.reserve("first", 3_000_000)
        budget.settle("first", null)
        assertEquals(3_000_000L, budget.reservedMicros)
        budget.reserve("second", 3_000_000)
        budget.settle("second", 1200)
        assertEquals(3_001_200L, budget.reservedMicros)
        assertThrows(IllegalStateException::class.java) { budget.settle("second", 0) }
    }

    @Test fun failedPersistenceNeverAuthorizesNetworkCall() {
        val budget = EvaluationBudget(BudgetState()) { error("disk full") }
        assertThrows(IllegalStateException::class.java) { budget.reserve("first", 1_000_000) }
        assertEquals(0L, budget.reservedMicros)
    }

    @Test fun invalidOrOverBudgetStateFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) { EvaluationBudget(BudgetState(limitMicros = 10_000_001)) {} }
        assertThrows(IllegalArgumentException::class.java) { EvaluationBudget(BudgetState(entries = listOf(BudgetEntry("x", -1)))) {} }
        assertThrows(IllegalArgumentException::class.java) { EvaluationBudget(BudgetState(entries = listOf(BudgetEntry("x", 6_000_000), BudgetEntry("y", 6_000_000)))) {} }
    }

    @Test fun realCostBeyondReservationBlocksFurtherCallsRatherThanDiscardingCost() {
        var saved = BudgetState()
        val budget = EvaluationBudget(saved) { saved = it }
        budget.reserve("first", 1_000_000)
        budget.settle("first", 2_000_000)
        assertTrue(saved.halted)
        assertEquals(2_000_000L, budget.reservedMicros)
        assertThrows(IllegalStateException::class.java) { budget.reserve("second", 1) }
    }
}
