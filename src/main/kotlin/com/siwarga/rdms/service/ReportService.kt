package com.siwarga.rdms.service

import com.siwarga.rdms.calc.AllocationType
import com.siwarga.rdms.calc.DuesRate
import com.siwarga.rdms.calc.PenaltyTrigger
import com.siwarga.rdms.domain.House
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth
import java.util.UUID

@Service
class ReportService(
    private val accountService: AccountService,
    private val houseService: HouseService,
) {
    @Transactional(readOnly = true)
    fun arrears(
        houseId: UUID,
        refMonth: YearMonth = YearMonth.now(),
    ): ArrearsReport {
        val house = accountService.loadHouse(houseId)
        val account = accountService.replay(house, refMonth).account
        return ArrearsReport(
            houseId = house.id,
            ownerName = house.ownerName,
            totalArrearsIdr = account.arrearsTotal,
            totalPenaltiesIdr = account.penaltyTotal,
            depositIdr = account.depositBalance,
            unpaidPeriods =
                account.unpaidPeriods.map {
                    UnpaidPeriodView(it.year, it.monthValue, DuesRate.getRate(it), paid = false)
                },
            penalties =
                account.penalties.map {
                    PenaltyView(triggerLabel(it.trigger), it.assessedAfterPeriod.toString(), it.amount)
                },
        )
    }

    @Transactional(readOnly = true)
    fun monthlyDues(
        rtId: UUID?,
        houseId: UUID?,
        refMonth: YearMonth = YearMonth.now(),
    ): MonthlyDuesReport {
        val scope: String
        val houses: List<House>
        when {
            houseId != null -> {
                scope = "HOUSE"
                houses = listOf(accountService.loadHouse(houseId))
            }

            rtId != null -> {
                scope = "RT"
                houses = houseService.list(rtId)
            }

            else -> {
                scope = "CLUSTER"
                houses = houseService.list(null)
            }
        }

        val expected = linkedMapOf<YearMonth, Long>()
        val collected = linkedMapOf<YearMonth, Long>()
        val discount = linkedMapOf<YearMonth, Long>()
        val paidCount = linkedMapOf<YearMonth, Int>()

        for (house in houses) {
            val result = accountService.replay(house, refMonth)
            var ym = YearMonth.from(house.activeDate)
            while (ym <= refMonth) {
                expected.merge(ym, DuesRate.getRate(ym), Long::plus)
                ym = ym.plusMonths(1)
            }
            result.perPayment.forEach { pr ->
                pr.lines.filter { it.type == AllocationType.DUES && it.period != null }.forEach { line ->
                    collected.merge(line.period!!, line.amount, Long::plus)
                    if (line.discountApplied > 0) discount.merge(line.period, line.discountApplied, Long::plus)
                }
            }
            result.account.paidPeriods
                .filter { it <= refMonth }
                .forEach { paidCount.merge(it, 1, Int::plus) }
        }

        val rows =
            expected.keys.sorted().map { ym ->
                val exp = expected[ym] ?: 0
                val cash = collected[ym] ?: 0
                val disc = discount[ym] ?: 0
                MonthlyDuesRow(
                    year = ym.year,
                    month = ym.monthValue,
                    expectedIdr = exp,
                    collectedIdr = cash,
                    discountIdr = disc,
                    outstandingIdr = exp - cash - disc, // identity: expected = cash + discount + outstanding
                    paid = (paidCount[ym] ?: 0) == houses.size && houses.isNotEmpty(),
                )
            }

        return MonthlyDuesReport(
            scope = scope,
            houseId = houseId,
            rtId = rtId,
            rows = rows,
            totalExpectedIdr = rows.sumOf { it.expectedIdr },
            totalCollectedIdr = rows.sumOf { it.collectedIdr },
            totalDiscountIdr = rows.sumOf { it.discountIdr },
        )
    }

    private fun triggerLabel(t: PenaltyTrigger): String =
        when (t) {
            PenaltyTrigger.SIX_MONTHS -> "6_MONTHS"
            PenaltyTrigger.TWELVE_MONTHS -> "12_MONTHS"
        }
}
