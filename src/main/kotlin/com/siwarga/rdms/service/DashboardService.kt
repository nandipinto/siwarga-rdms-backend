package com.siwarga.rdms.service

import com.siwarga.rdms.calc.AllocationEngine
import com.siwarga.rdms.calc.AllocationType
import com.siwarga.rdms.calc.DuesRate
import com.siwarga.rdms.calc.PaymentInput
import com.siwarga.rdms.domain.DashboardAlerts
import com.siwarga.rdms.domain.DashboardCurrentMonth
import com.siwarga.rdms.domain.DashboardHouseStats
import com.siwarga.rdms.domain.DashboardMonthlyTrendPoint
import com.siwarga.rdms.domain.DashboardPeriod
import com.siwarga.rdms.domain.DashboardRecentPayment
import com.siwarga.rdms.domain.DashboardResponse
import com.siwarga.rdms.domain.DashboardTopArrearsEntry
import com.siwarga.rdms.domain.House
import com.siwarga.rdms.domain.Payment
import com.siwarga.rdms.domain.RefundStatus
import com.siwarga.rdms.domain.UserRole
import com.siwarga.rdms.repository.PaymentAllocationRepository
import com.siwarga.rdms.repository.PaymentRepository
import com.siwarga.rdms.repository.RentalGuaranteeRefundRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth

@Service
class DashboardService(
    private val accountService: AccountService,
    private val houseService: HouseService,
    private val paymentRepository: PaymentRepository,
    private val allocationRepository: PaymentAllocationRepository,
    private val guaranteePaymentService: RentalGuaranteePaymentService,
    private val refundRepository: RentalGuaranteeRefundRepository,
) {
    @Transactional(readOnly = true)
    fun dashboard(role: UserRole): DashboardResponse {
        val alerts = buildAlerts()
        if (role == UserRole.SUPERVISOR) {
            return DashboardResponse(role = role.name, alerts = alerts)
        }
        return administratorDashboard(role, alerts)
    }

    private fun buildAlerts(): DashboardAlerts {
        val unpaidGuarantees =
            houseService.list(null).count { house ->
                guaranteePaymentService.buildSummary(house)?.status == "UNPAID"
            }
        return DashboardAlerts(
            pendingRefunds = refundRepository.countByStatus(RefundStatus.PENDING),
            unpaidGuarantees = unpaidGuarantees,
        )
    }

    private fun administratorDashboard(
        role: UserRole,
        alerts: DashboardAlerts,
    ): DashboardResponse {
        val refMonth = YearMonth.now()
        val refYear = refMonth.year
        val houses = houseService.list(null)

        var paidUp = 0
        var inArrears = 0
        val arrearsCandidates = mutableListOf<DashboardTopArrearsEntry>()
        val collectedByMonth = linkedMapOf<YearMonth, Long>()
        val expectedByMonth = linkedMapOf<YearMonth, Long>()

        for (house in houses) {
            val result = accountService.replay(house, refMonth)
            val account = result.account
            val outstanding = account.arrearsTotal + account.penaltyTotal
            if (outstanding == 0L) {
                paidUp++
            } else {
                inArrears++
                arrearsCandidates +=
                    DashboardTopArrearsEntry(
                        rank = 0,
                        houseId = house.id,
                        ownerName = house.ownerName,
                        rtCode = house.rt.rtCode,
                        totalOutstandingIdr = outstanding,
                        phone = house.phone,
                        email = house.email,
                    )
            }

            var ym = YearMonth.from(house.activeDate)
            while (ym <= refMonth) {
                if (ym.year == refYear) {
                    expectedByMonth.merge(ym, DuesRate.getRate(ym), Long::plus)
                }
                ym = ym.plusMonths(1)
            }
            result.perPayment.forEach { paymentResult ->
                paymentResult.lines
                    .filter { it.type == AllocationType.DUES && it.period != null }
                    .forEach { line ->
                        if (line.period!!.year == refYear) {
                            collectedByMonth.merge(line.period, line.amount, Long::plus)
                        }
                    }
            }
        }

        val currentMonthExpected = expectedByMonth[refMonth] ?: 0L
        val currentMonthCollected = collectedByMonth[refMonth] ?: 0L
        val percent =
            if (currentMonthExpected == 0L) {
                0
            } else {
                ((currentMonthCollected * 100) / currentMonthExpected).toInt()
            }

        val monthlyTrend =
            (1..12).map { month ->
                val ym = YearMonth.of(refYear, month)
                DashboardMonthlyTrendPoint(
                    year = ym.year,
                    month = ym.monthValue,
                    collectedIdr = collectedByMonth[ym] ?: 0L,
                )
            }

        val topArrears =
            arrearsCandidates
                .sortedWith(
                    compareByDescending<DashboardTopArrearsEntry> { it.totalOutstandingIdr }
                        .thenBy { it.ownerName },
                ).take(10)
                .mapIndexed { index, entry -> entry.copy(rank = index + 1) }

        return DashboardResponse(
            role = role.name,
            alerts = alerts,
            totalCollectedIdr = paymentRepository.sumGrossAmount(),
            currentMonth =
                DashboardCurrentMonth(
                    year = refMonth.year,
                    month = refMonth.monthValue,
                    expectedIdr = currentMonthExpected,
                    collectedIdr = currentMonthCollected,
                    percent = percent,
                ),
            houseStats =
                DashboardHouseStats(
                    total = houses.size,
                    paidUp = paidUp,
                    inArrears = inArrears,
                ),
            monthlyTrend = monthlyTrend,
            recentPayments = buildRecentPayments(refMonth),
            topArrears = topArrears,
        )
    }

    private fun buildRecentPayments(refMonth: YearMonth): List<DashboardRecentPayment> {
        val payments = paymentRepository.findRecent(PageRequest.of(0, 5))
        return payments.map { payment ->
            val house = payment.house
            DashboardRecentPayment(
                paymentId = payment.id,
                houseId = house.id,
                ownerName = house.ownerName,
                rtCode = house.rt.rtCode,
                grossAmountIdr = payment.grossAmount,
                createdAt = payment.createdAt.toString(),
                primaryPeriod = primaryPeriodForPayment(payment.id),
                housePaidUp = housePaidUpAfterPayment(house, payment, refMonth),
            )
        }
    }

    private fun primaryPeriodForPayment(paymentId: java.util.UUID): DashboardPeriod? {
        val rows = allocationRepository.findByPaymentId(paymentId)
        return rows
            .filter { it.allocationType == AllocationType.DUES && it.periodYear != null && it.periodMonth != null }
            .maxByOrNull { YearMonth.of(it.periodYear!!.toInt(), it.periodMonth!!.toInt()) }
            ?.let { DashboardPeriod(it.periodYear!!.toInt(), it.periodMonth!!.toInt()) }
    }

    private fun housePaidUpAfterPayment(
        house: House,
        target: Payment,
        refMonth: YearMonth,
    ): Boolean {
        val payments = paymentRepository.findByHouseIdOrderByPaymentDateAscCreatedAtAscIdAsc(house.id)
        val index = payments.indexOfFirst { it.id == target.id }
        if (index < 0) return false
        val slice = payments.subList(0, index + 1)
        val inputs = slice.map { PaymentInput(it.paymentDate, it.grossAmount, it.id, it.createdAt) }
        val result =
            AllocationEngine.replay(
                YearMonth.from(house.activeDate),
                inputs,
                refMonth,
            )
        return result.account.arrearsTotal == 0L && result.account.penaltyTotal == 0L
    }
}
