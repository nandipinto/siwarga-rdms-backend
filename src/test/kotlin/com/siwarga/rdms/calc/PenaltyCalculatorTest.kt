package com.siwarga.rdms.calc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.YearMonth

class PenaltyCalculatorTest {
    private val active = YearMonth.of(2024, 1)

    private fun penalty(
        refMonth: YearMonth,
        activity: Set<YearMonth> = emptySet(),
    ) = PenaltyCalculator.compute(active, refMonth, activity).total

    @Test
    fun `under 6 months no penalty`() {
        // Jan..May 2024 = 5 months
        assertEquals(0, penalty(YearMonth.of(2024, 5)))
    }

    @Test
    fun `6 month gap yields one 60k penalty`() {
        // Jan..Jun 2024 = 6 months
        assertEquals(60_000, penalty(YearMonth.of(2024, 6)))
    }

    @Test
    fun `12 month gap yields one 120k penalty and supersedes 6 month`() {
        // Jan..Dec 2024 = 12 months -> floor(12/12)=1 *120k, floor(0/6)=0
        assertEquals(120_000, penalty(YearMonth.of(2024, 12)))
    }

    @Test
    fun `15 month gap yields 120k (spec example)`() {
        // floor(15/12)=1*120k + floor(3/6)=0 = 120_000
        assertEquals(120_000, penalty(YearMonth.of(2025, 3)))
    }

    @Test
    fun `18 month gap yields 180k`() {
        // floor(18/12)=1*120k + floor(6/6)=1*60k
        assertEquals(180_000, penalty(YearMonth.of(2025, 6)))
    }

    @Test
    fun `24 month gap yields 240k`() {
        // floor(24/12)=2*120k
        assertEquals(240_000, penalty(YearMonth.of(2025, 12)))
    }

    @Test
    fun `payment activity resets the counter`() {
        // Activity in Jun 2024 splits the span; each side under 6 months -> no penalty.
        val activity = setOf(YearMonth.of(2024, 6))
        assertEquals(0, penalty(YearMonth.of(2024, 10), activity))
    }
}
