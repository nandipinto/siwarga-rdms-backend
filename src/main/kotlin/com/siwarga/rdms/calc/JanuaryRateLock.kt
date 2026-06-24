package com.siwarga.rdms.calc

import com.siwarga.rdms.calc.DuesRate.getRate
import java.time.YearMonth

/**
 * January 2026 promotional payment profiles (ADR-0002).
 *
 * Lump-sum payments dated January 2026 on a clean account (`nextUnpaid == Jan 2026`) use locked
 * cash tariffs while `discount_applied` closes each period to [getRate] for report reconciliation.
 */
object JanuaryRateLock {
    val PAYMENT_MONTH: YearMonth = YearMonth.of(2026, 1)
    val COVERAGE_START: YearMonth = YearMonth.of(2026, 1)
    val COVERAGE_END: YearMonth = YearMonth.of(2026, 12)

    const val LOCKED_FULL_MONTH_CASH: Long = 100_000
    const val LOCKED_HALF_MONTH_CASH: Long = 50_000

    const val SIX_MONTH_GROSS: Long = 550_000
    const val STAFF_YEAR_GROSS: Long = 1_000_000
    const val YEAR_PACKAGE_GROSS: Long = 1_100_000

    private enum class Profile {
        SIX_MONTH,
        STAFF_YEAR,
        YEAR_PACKAGE,
        YEAR_WITH_DEPOSIT,
    }

    data class Result(
        val lines: List<AllocationLine>,
        val covered: List<YearMonth>,
        val nextUnpaid: YearMonth,
        val deposit: Long,
    )

    fun tryAllocate(
        payment: PaymentInput,
        nextUnpaid: YearMonth,
        outstandingPenaltyBefore: Long,
        deposit: Long,
        isStaffHouse: Boolean,
    ): Result? {
        if (YearMonth.from(payment.paymentDate) != PAYMENT_MONTH) return null
        if (nextUnpaid != COVERAGE_START) return null
        if (outstandingPenaltyBefore > 0) return null
        if (deposit > 0) return null

        val profile = resolveProfile(payment.grossAmount, isStaffHouse) ?: return null
        return when (profile) {
            Profile.SIX_MONTH -> allocateSixMonth()
            Profile.STAFF_YEAR -> allocateStaffYear()
            Profile.YEAR_PACKAGE -> allocateYearPackage(depositAfter = 0)
            Profile.YEAR_WITH_DEPOSIT -> allocateYearPackage(depositAfter = payment.grossAmount - YEAR_PACKAGE_GROSS)
        }
    }

    private fun resolveProfile(
        gross: Long,
        isStaffHouse: Boolean,
    ): Profile? =
        when {
            gross == SIX_MONTH_GROSS -> Profile.SIX_MONTH
            gross == STAFF_YEAR_GROSS && isStaffHouse -> Profile.STAFF_YEAR
            gross == YEAR_PACKAGE_GROSS -> Profile.YEAR_PACKAGE
            gross > YEAR_PACKAGE_GROSS -> Profile.YEAR_WITH_DEPOSIT
            else -> null
        }

    private fun allocateSixMonth(): Result {
        val lines = ArrayList<AllocationLine>(6)
        val covered = ArrayList<YearMonth>(6)
        for (month in 1..5) {
            val period = YearMonth.of(2026, month)
            lines += duesLine(period, LOCKED_FULL_MONTH_CASH)
            covered += period
        }
        val june = YearMonth.of(2026, 6)
        lines += duesLine(june, LOCKED_HALF_MONTH_CASH)
        covered += june
        return Result(lines, covered, YearMonth.of(2026, 7), deposit = 0)
    }

    private fun allocateStaffYear(): Result {
        val waived = setOf(6, 12)
        val lines = ArrayList<AllocationLine>(12)
        val covered = ArrayList<YearMonth>(12)
        for (month in 1..12) {
            val period = YearMonth.of(2026, month)
            val cash = if (month in waived) 0L else LOCKED_FULL_MONTH_CASH
            lines += duesLine(period, cash)
            covered += period
        }
        return Result(lines, covered, YearMonth.of(2027, 1), deposit = 0)
    }

    private fun allocateYearPackage(depositAfter: Long): Result {
        val lines = ArrayList<AllocationLine>(12)
        val covered = ArrayList<YearMonth>(12)
        for (month in 1..11) {
            val period = YearMonth.of(2026, month)
            lines += duesLine(period, LOCKED_FULL_MONTH_CASH)
            covered += period
        }
        val december = YearMonth.of(2026, 12)
        lines += duesLine(december, amount = 0)
        covered += december
        if (depositAfter > 0) {
            lines +=
                AllocationLine(
                    AllocationType.DEPOSIT,
                    null,
                    depositAfter,
                )
        }
        return Result(lines, covered, YearMonth.of(2027, 1), deposit = depositAfter)
    }

    private fun duesLine(
        period: YearMonth,
        amount: Long,
    ): AllocationLine {
        val scheduled = getRate(period)
        return AllocationLine(
            AllocationType.DUES,
            period,
            amount,
            discountApplied = scheduled - amount,
        )
    }
}
