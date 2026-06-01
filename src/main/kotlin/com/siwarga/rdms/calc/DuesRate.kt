package com.siwarga.rdms.calc

import java.time.YearMonth

/**
 * Pure dues-rate function (spec §6.1).
 *
 *   yearMonth < 2026-02 -> 100_000
 *   else                -> 120_000
 *
 * All monetary values are integer IDR (spec §8 — no floating point for money).
 */
object DuesRate {
    const val LEGACY_RATE: Long = 100_000
    const val CURRENT_RATE: Long = 120_000
    val RATE_CHANGE: YearMonth = YearMonth.of(2026, 2)

    fun getRate(yearMonth: YearMonth): Long = if (yearMonth < RATE_CHANGE) LEGACY_RATE else CURRENT_RATE
}
