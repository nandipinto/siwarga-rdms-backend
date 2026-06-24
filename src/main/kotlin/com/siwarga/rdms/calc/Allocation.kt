package com.siwarga.rdms.calc

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

enum class AllocationType { DUES, PENALTY, DEPOSIT }

/** One line of how a payment was distributed (maps to the PaymentAllocation entity). */
data class AllocationLine(
    val type: AllocationType,
    val period: YearMonth?, // null for PENALTY / DEPOSIT
    val amount: Long, // cash allocated to this line
    val discountApplied: Long = 0, // discount deducted from this period's required dues
)

/**
 * A payment as seen by the pure engine. `ref` lets the caller correlate results back to a DB row.
 * `createdAt` is the stable tiebreaker for same-date payments (grill Q8): replay order is
 * (paymentDate, createdAt, ref).
 */
data class PaymentInput(
    val paymentDate: LocalDate,
    val grossAmount: Long,
    val ref: Any? = null,
    val createdAt: Instant = Instant.EPOCH,
)

data class PaymentResult(
    val payment: PaymentInput,
    val lines: List<AllocationLine>,
    val depositAfter: Long,
    val coveredPeriods: List<YearMonth>,
)

/** A single effective-dated staff-eligibility period for a house. `effectiveTo` is exclusive. */
data class StaffStatusPeriod(
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val staffHouse: Boolean,
) {
    fun appliesOn(date: LocalDate): Boolean =
        !date.isBefore(effectiveFrom) &&
            (effectiveTo == null || date.isBefore(effectiveTo))
}

/** House-level discount inputs for the engine, derived from effective-dated history. */
data class HouseDiscountContext(
    val staffStatusPeriods: List<StaffStatusPeriod> = emptyList(),
) {
    fun isStaffHouseOn(date: LocalDate): Boolean =
        staffStatusPeriods.any { it.staffHouse && it.appliesOn(date) }
}

/** Final derived account state for a house at a reference month (spec §6.2). */
data class AccountState(
    val activeDate: YearMonth,
    val refMonth: YearMonth,
    val paidPeriods: List<YearMonth>,
    val unpaidPeriods: List<YearMonth>,
    val arrearsTotal: Long,
    val penaltyTotal: Long, // outstanding (assessed minus settled)
    val depositBalance: Long,
    val penalties: List<PenaltyItem>,
    val coveredThrough: YearMonth?,
)

data class ReplayResult(
    val perPayment: List<PaymentResult>,
    val account: AccountState,
)

class PaymentRejectedException(
    message: String,
) : RuntimeException(message)
