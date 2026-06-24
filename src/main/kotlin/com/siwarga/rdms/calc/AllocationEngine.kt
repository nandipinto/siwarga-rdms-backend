package com.siwarga.rdms.calc

import com.siwarga.rdms.calc.DuesRate.getRate
import java.time.YearMonth

/**
 * Pure payment allocation engine (spec §6.4). No Spring, no DB — fully unit-testable.
 *
 * Replays a house's whole payment history chronologically to derive both the per-payment
 * allocation breakdown and the final account state. The service layer is responsible for
 * loading payments from the DB and persisting the resulting allocations atomically.
 */
object AllocationEngine {
    private const val MAX_FORWARD_MONTHS = 1200 // safety bound for prepayment search

    fun replay(
        activeDate: YearMonth,
        payments: List<PaymentInput>,
        refMonth: YearMonth,
        discountContext: HouseDiscountContext = HouseDiscountContext(),
    ): ReplayResult {
        // Stable chronological order; same-date payments break ties by createdAt then ref (Q8).
        val ordered =
            payments.sortedWith(
                compareBy({ it.paymentDate }, { it.createdAt }, { it.ref?.toString() ?: "" }),
            )

        var nextUnpaid = activeDate // earliest period not yet fully covered
        var deposit = 0L // partial credit (< rate of nextUnpaid)
        var penaltiesPaid = 0L // total penalty cash settled so far
        // Months covered ON TIME — by a payment dated in that month or earlier. Drives penalties
        // (ADR-0001). Retroactively-cleared back-dues are never added here, so their penalty sticks.
        val onTimeCovered = sortedSetOf<YearMonth>()

        val results = ArrayList<PaymentResult>(ordered.size)

        for (p in ordered) {
            val paymentPeriod = YearMonth.from(p.paymentDate)
            val minRate = getRate(paymentPeriod)
            if (p.grossAmount < minRate) {
                throw PaymentRejectedException(
                    "Payment ${p.grossAmount} is below the minimum of $minRate for $paymentPeriod",
                )
            }

            // Penalty owed for delinquency strictly before this payment, using on-time coverage
            // established by EARLIER payments only (this payment's coverage is marked below).
            val penaltyOwed =
                PenaltyCalculator
                    .compute(activeDate, paymentPeriod, onTimeCovered)
                    .total
            val outstandingPenaltyBefore = penaltyOwed - penaltiesPaid

            val lines = mutableListOf<AllocationLine>()
            val covered = mutableListOf<YearMonth>()

            val promo =
                JanuaryRateLock.tryAllocate(
                    payment = p,
                    nextUnpaid = nextUnpaid,
                    outstandingPenaltyBefore = outstandingPenaltyBefore,
                    deposit = deposit,
                    isStaffHouse = discountContext.isStaffHouseOn(p.paymentDate),
                )
            if (promo != null) {
                lines += promo.lines
                covered += promo.covered
                nextUnpaid = promo.nextUnpaid
                deposit = promo.deposit

                var onTime = paymentPeriod
                while (onTime < nextUnpaid) {
                    onTimeCovered.add(onTime)
                    onTime = onTime.plusMonths(1)
                }

                results += PaymentResult(p, lines, deposit, covered)
                continue
            }

            var remaining = p.grossAmount + deposit
            deposit = 0L

            // 1) Clear arrears FIFO up to and including the payment month.
            var period = nextUnpaid
            while (period <= paymentPeriod) {
                val rate = getRate(period)
                if (remaining >= rate) {
                    lines +=
                        AllocationLine(
                            AllocationType.DUES,
                            period,
                            rate,
                        )
                    covered += period
                    remaining -= rate
                    period = period.plusMonths(1)
                } else {
                    break
                }
            }
            nextUnpaid = period

            if (nextUnpaid <= paymentPeriod) {
                // Ran out mid-arrears: leftover held as deposit.
                deposit = remaining
            } else {
                // 2) Arrears up to payment month cleared — settle outstanding penalties.
                if (outstandingPenaltyBefore > 0 && remaining > 0) {
                    val pay = minOf(remaining, outstandingPenaltyBefore)
                    lines +=
                        AllocationLine(
                            AllocationType.PENALTY,
                            null,
                            pay,
                        )
                    remaining -= pay
                    penaltiesPaid += pay
                }
                val penaltyStillOwed = (penaltyOwed - penaltiesPaid) > 0

                if (penaltyStillOwed) {
                    deposit = remaining
                } else if (remaining > 0) {
                    // 3) Allocate forward months. Reaching here means arrears and penalties are
                    //    fully cleared by THIS payment, so the account is now clean — the forward
                    //    prepayment block is eligible for the discount (spec §2.3, grill Q3a).
                    val (fwdLines, used) = allocateForward(nextUnpaid, remaining, discountEligible = true)
                    lines += fwdLines
                    covered +=
                        fwdLines.filter { it.type == AllocationType.DUES }.mapNotNull { it.period }
                    remaining -= used
                    nextUnpaid =
                        nextUnpaid.plusMonths(
                            fwdLines.count { it.type == AllocationType.DUES }.toLong(),
                        )
                    deposit = remaining
                }
            }

            if (deposit > 0) {
                lines +=
                    AllocationLine(
                        AllocationType.DEPOSIT,
                        null,
                        deposit,
                    )
            }

            // Mark months this payment covered ON TIME: from the payment month up to the new
            // frontier. Months below paymentPeriod that it cleared retroactively are excluded —
            // they were delinquent as they elapsed, so their penalty stands (ADR-0001). If the
            // payment didn't even reach its own month, the range is empty (nothing marked).
            var onTime = paymentPeriod
            while (onTime < nextUnpaid) {
                onTimeCovered.add(onTime)
                onTime = onTime.plusMonths(1)
            }

            results +=
                PaymentResult(p, lines, deposit, covered)
        }

        return ReplayResult(
            results,
            buildAccountState(activeDate, refMonth, nextUnpaid, deposit, penaltiesPaid, onTimeCovered),
        )
    }

    /**
     * Cover future periods starting at [startPeriod]. When [discountEligible], find the maximum
     * number of months `M` affordable under the discounted requirement:
     *
     *   requiredCash(M) = Σ getRate(period_i)  −  discount(M)
     *   discount(M)     = Σ_{k=1..floor(M/12)} getRate(free_k)
     *                   + (if (M mod 12) >= 6) getRate(half_period)/2
     *
     * Discounts do not stack across tiers (spec §2.3): the 6-month half only applies to the
     * remainder after whole 12-blocks. requiredCash is monotonic in M, so a greedy scan works.
     *
     * @return forward DUES lines and the total cash consumed.
     */
    private fun allocateForward(
        startPeriod: YearMonth,
        remaining: Long,
        discountEligible: Boolean,
    ): Pair<List<AllocationLine>, Long> {
        var bestM = 0
        var m = 0
        while (m < MAX_FORWARD_MONTHS) {
            val cand = m + 1
            val required = requiredCash(startPeriod, cand, discountEligible)
            if (required <= remaining) {
                bestM = cand
                m = cand
            } else {
                break
            }
        }
        if (bestM == 0) return emptyList<AllocationLine>() to 0L

        val full = bestM / 12
        val rem = bestM % 12
        val freePeriods = (1..full).map { startPeriod.plusMonths((12L * it) - 1) }.toSet()
        val halfPeriod = if (rem >= 6) startPeriod.plusMonths((12L * full) + 6 - 1) else null

        val lines = ArrayList<AllocationLine>(bestM)
        var used = 0L
        for (i in 1..bestM) {
            val period = startPeriod.plusMonths((i - 1).toLong())
            val rate = getRate(period)
            val (cash, discount) =
                when {
                    discountEligible && period in freePeriods -> 0L to rate
                    discountEligible && period == halfPeriod -> (rate - rate / 2) to (rate / 2)
                    else -> rate to 0L
                }
            lines +=
                AllocationLine(
                    AllocationType.DUES,
                    period,
                    cash,
                    discount,
                )
            used += cash
        }
        return lines to used
    }

    private fun requiredCash(
        startPeriod: YearMonth,
        m: Int,
        discountEligible: Boolean,
    ): Long {
        var sum = 0L
        for (i in 1..m) sum += getRate(startPeriod.plusMonths((i - 1).toLong()))
        if (!discountEligible) return sum

        val full = m / 12
        val rem = m % 12
        var discount = 0L
        for (k in 1..full) discount += getRate(startPeriod.plusMonths((12L * k) - 1))
        if (rem >= 6) discount += getRate(startPeriod.plusMonths((12L * full) + 6 - 1)) / 2
        return sum - discount
    }

    private fun buildAccountState(
        activeDate: YearMonth,
        refMonth: YearMonth,
        nextUnpaid: YearMonth,
        deposit: Long,
        penaltiesPaid: Long,
        onTimeCovered: Set<YearMonth>,
    ): AccountState {
        val coveredThrough = if (nextUnpaid > activeDate) nextUnpaid.minusMonths(1) else null

        val paidPeriods = mutableListOf<YearMonth>()
        var c = activeDate
        while (c < nextUnpaid) {
            paidPeriods += c
            c = c.plusMonths(1)
        }

        val unpaidPeriods = mutableListOf<YearMonth>()
        var u = nextUnpaid
        while (u <= refMonth) {
            unpaidPeriods += u
            u = u.plusMonths(1)
        }
        val arrearsTotal = unpaidPeriods.sumOf { getRate(it) }

        val penaltyResult =
            PenaltyCalculator
                .compute(activeDate, refMonth, onTimeCovered)
        val outstandingPenalty = (penaltyResult.total - penaltiesPaid).coerceAtLeast(0)

        return AccountState(
            activeDate = activeDate,
            refMonth = refMonth,
            paidPeriods = paidPeriods,
            unpaidPeriods = unpaidPeriods,
            arrearsTotal = arrearsTotal,
            penaltyTotal = outstandingPenalty,
            depositBalance = deposit,
            penalties = penaltyResult.items,
            coveredThrough = coveredThrough,
        )
    }
}
