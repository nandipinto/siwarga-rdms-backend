package com.siwarga.rdms.domain

import com.fasterxml.jackson.annotation.JsonInclude
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

data class DashboardAlerts(
    val pendingRefunds: Long,
    val unpaidGuarantees: Int,
)

data class DashboardCurrentMonth(
    val year: Int,
    val month: Int,
    val expectedIdr: Long,
    val collectedIdr: Long,
    val percent: Int,
)

data class DashboardHouseStats(
    val total: Int,
    val paidUp: Int,
    val inArrears: Int,
)

data class DashboardMonthlyTrendPoint(
    val year: Int,
    val month: Int,
    val collectedIdr: Long,
)

data class DashboardPeriod(
    val year: Int,
    val month: Int,
)

data class DashboardRecentPayment(
    val paymentId: UUID,
    val houseId: UUID,
    val ownerName: String,
    val rtCode: String,
    val grossAmountIdr: Long,
    val createdAt: String,
    val primaryPeriod: DashboardPeriod?,
    val housePaidUp: Boolean,
)

data class DashboardTopArrearsEntry(
    val rank: Int,
    val houseId: UUID,
    val ownerName: String,
    val rtCode: String,
    val totalOutstandingIdr: Long,
    val phone: String,
    val email: String?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DashboardResponse(
    val role: String,
    // RT scope for supervisors; null for administrators (cluster-wide). Spec §4.3, §5.5.
    val rtId: UUID? = null,
    val rtCode: String? = null,
    val alerts: DashboardAlerts,
    val totalCollectedIdr: Long? = null,
    val currentMonth: DashboardCurrentMonth? = null,
    val houseStats: DashboardHouseStats? = null,
    val monthlyTrend: List<DashboardMonthlyTrendPoint>? = null,
    val recentPayments: List<DashboardRecentPayment>? = null,
    val topArrears: List<DashboardTopArrearsEntry>? = null,
)
