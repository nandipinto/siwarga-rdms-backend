package com.siwarga.rdms.calc

import java.time.YearMonth

/**
 * January 2026 early-bird prepay: residents who paid the full year in January 2026,
 * before the February rate increase, lock in Rp 100,000/month for Jan–Nov and receive
 * December covered via a Rp 100,000 reward (cash = 0, discountApplied = 100_000).
 */
object EarlyBirdJan2026 {
    val PAYMENT_MONTH: YearMonth = YearMonth.of(2026, 1)
    val FIRST_COVERED: YearMonth = YearMonth.of(2026, 1)
    val NEXT_UNPAID_AFTER: YearMonth = YearMonth.of(2027, 1)

    const val GROSS_AMOUNT: Long = 1_100_000
    const val MONTHLY_CASH: Long = 100_000
    const val DECEMBER_REWARD: Long = 100_000

    data class Result(
        val lines: List<AllocationLine>,
        val covered: List<YearMonth>,
        val nextUnpaid: YearMonth,
    )

    fun tryAllocate(
        payment: PaymentInput,
        nextUnpaid: YearMonth,
        outstandingPenaltyBefore: Long,
        deposit: Long,
    ): Result? {
        if (YearMonth.from(payment.paymentDate) != PAYMENT_MONTH) return null
        if (payment.grossAmount != GROSS_AMOUNT) return null
        if (nextUnpaid != FIRST_COVERED) return null
        if (outstandingPenaltyBefore > 0) return null
        if (deposit > 0) return null

        val lines = ArrayList<AllocationLine>(12)
        for (month in 1..11) {
            lines +=
                AllocationLine(
                    AllocationType.DUES,
                    YearMonth.of(2026, month),
                    MONTHLY_CASH,
                )
        }
        lines +=
            AllocationLine(
                AllocationType.DUES,
                YearMonth.of(2026, 12),
                amount = 0,
                discountApplied = DECEMBER_REWARD,
            )

        val covered = (1..12).map { YearMonth.of(2026, it) }
        return Result(lines, covered, NEXT_UNPAID_AFTER)
    }
}
