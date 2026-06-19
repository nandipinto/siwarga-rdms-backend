package com.siwarga.rdms.calc

import java.time.YearMonth

enum class PenaltyTrigger(
    val months: Int,
    val amount: Long,
) {
    SIX_MONTHS(6, 60_000),
    TWELVE_MONTHS(12, 120_000),
}

/** A single assessed penalty, attributed to the period after which it was triggered. */
data class PenaltyItem(
    val trigger: PenaltyTrigger,
    val assessedAfterPeriod: YearMonth,
    val amount: Long,
)

data class PenaltyResult(
    val total: Long,
    val items: List<PenaltyItem>,
)

/**
 * Deterministic penalty calculator (spec §6.3, ADR-0001).
 *
 * A month is in [onTimeCoveredMonths] if its dues were covered *on time* — by a payment dated in
 * that month or earlier (see AllocationEngine). Such a month is not delinquent and breaks the run.
 * A month covered only retroactively by a later catch-up payment is NOT on-time, so it stays in the
 * gap and any penalty it triggered stands. For each maximal gap of `G` consecutive delinquent months:
 *
 *   penalty = floor(G / 12) * 120_000  +  floor((G mod 12) / 6) * 60_000
 *
 * The 12-month block supersedes the 6-month block for the same span (no double counting),
 * because the 6-month term only sees the remainder `G mod 12`.
 */
object PenaltyCalculator {
    fun compute(
        activeDate: YearMonth,
        refMonth: YearMonth,
        onTimeCoveredMonths: Set<YearMonth>,
    ): PenaltyResult {
        if (refMonth < activeDate) {
            return PenaltyResult(0, emptyList())
        }

        val items = mutableListOf<PenaltyItem>()
        var total = 0L

        var cursor = activeDate
        while (cursor <= refMonth) {
            if (cursor in onTimeCoveredMonths) {
                cursor = cursor.plusMonths(1)
                continue
            }
            // Start of a gap; extend while months were not covered on time.
            val gapStart = cursor
            var gapLen = 0
            while (cursor <= refMonth && cursor !in onTimeCoveredMonths) {
                gapLen++
                cursor = cursor.plusMonths(1)
            }

            val full12 = gapLen / 12
            val rem = gapLen % 12
            val six = rem / 6

            for (k in 1..full12) {
                val assessedAfter = gapStart.plusMonths((12L * k) - 1)
                items +=
                    PenaltyItem(
                        PenaltyTrigger.TWELVE_MONTHS,
                        assessedAfter,
                        PenaltyTrigger.TWELVE_MONTHS.amount,
                    )
                total += PenaltyTrigger.TWELVE_MONTHS.amount
            }
            for (j in 1..six) {
                val assessedAfter = gapStart.plusMonths((12L * full12) + (6L * j) - 1)
                items +=
                    PenaltyItem(
                        PenaltyTrigger.SIX_MONTHS,
                        assessedAfter,
                        PenaltyTrigger.SIX_MONTHS.amount,
                    )
                total += PenaltyTrigger.SIX_MONTHS.amount
            }
        }

        return PenaltyResult(total, items)
    }
}
