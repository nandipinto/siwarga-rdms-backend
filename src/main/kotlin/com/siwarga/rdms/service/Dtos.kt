package com.siwarga.rdms.service

import com.siwarga.rdms.calc.AllocationType
import java.util.UUID

/** Computed views returned by the service layer (read models). */

data class AllocationView(
    val type: AllocationType,
    val periodYear: Int?,
    val periodMonth: Int?,
    val amount: Long,
    val discountApplied: Long,
)

data class PaymentView(
    val id: UUID,
    val houseId: UUID,
    val paymentDate: String,
    val grossAmount: Long,
    val note: String?,
    val allocations: List<AllocationView>,
    val depositBalance: Long,
    val nextPeriodCovered: String?,
)

data class UnpaidPeriodView(
    val year: Int,
    val month: Int,
    val duesAmount: Long,
    val paid: Boolean = false,
)

data class PenaltyView(
    val trigger: String,
    val assessedAfterPeriod: String,
    val amount: Long,
)

data class ArrearsReport(
    val houseId: UUID,
    val ownerName: String,
    val totalArrearsIdr: Long,
    val totalPenaltiesIdr: Long,
    val depositIdr: Long,
    val unpaidPeriods: List<UnpaidPeriodView>,
    val penalties: List<PenaltyView>,
)

data class MonthlyDuesRow(
    val year: Int,
    val month: Int,
    val expectedIdr: Long,
    val collectedIdr: Long, // actual cash received
    val discountIdr: Long, // dues waived via prepayment discount
    val outstandingIdr: Long, // expected - collected - discount
    val paid: Boolean,
)

data class MonthlyDuesReport(
    val scope: String,
    val houseId: UUID?,
    val rtId: UUID?,
    val rows: List<MonthlyDuesRow>,
    val totalExpectedIdr: Long,
    val totalCollectedIdr: Long,
    val totalDiscountIdr: Long,
)
