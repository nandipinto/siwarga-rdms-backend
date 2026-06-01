package com.siwarga.rdms.calc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.YearMonth

class DuesRateTest {
    @Test
    fun `legacy rate before Feb 2026`() {
        assertEquals(100_000, DuesRate.getRate(YearMonth.of(2024, 1)))
        assertEquals(100_000, DuesRate.getRate(YearMonth.of(2026, 1)))
    }

    @Test
    fun `current rate from Feb 2026 onward`() {
        assertEquals(120_000, DuesRate.getRate(YearMonth.of(2026, 2)))
        assertEquals(120_000, DuesRate.getRate(YearMonth.of(2027, 12)))
    }
}
