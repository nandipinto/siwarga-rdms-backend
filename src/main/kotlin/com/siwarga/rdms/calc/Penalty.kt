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
    val trigger: com.siwarga.rdms.calc.PenaltyTrigger,
    val assessedAfterPeriod: YearMonth,
    val amount: Long,
)

data class PenaltyResult(
    val total: Long,
    val items: List<com.siwarga.rdms.calc.PenaltyItem>,
)

/**
 * Deterministic penalty calculator (spec §6.3 — adopted as canonical over the §2.4 wording).
 *
 * A month has "payment activity" if any payment was recorded in that calendar month; such a
 * month resets the consecutive-non-payment counter. For each maximal gap of `G` consecutive
 * months without activity:
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
        activityMonths: Set<YearMonth>,
    ): com.siwarga.rdms.calc.PenaltyResult {
        if (refMonth < activeDate) {
            return PenaltyResult(0, emptyList())
        }

        val items = mutableListOf<com.siwarga.rdms.calc.PenaltyItem>()
        var total = 0L

        var cursor = activeDate
        while (cursor <= refMonth) {
            if (cursor in activityMonths) {
                cursor = cursor.plusMonths(1)
                continue
            }
            // Start of a gap; extend while months have no activity.
            val gapStart = cursor
            var gapLen = 0
            while (cursor <= refMonth && cursor !in activityMonths) {
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
