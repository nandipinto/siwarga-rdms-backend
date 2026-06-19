package com.siwarga.rdms.calc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

class AllocationEngineTest {
    private fun ym(
        y: Int,
        m: Int,
    ) = YearMonth.of(y, m)

    private fun pay(
        date: String,
        amount: Long,
    ) = PaymentInput(LocalDate.parse(date), amount)

    private fun duesLines(r: PaymentResult) = r.lines.filter { it.type == AllocationType.DUES }

    @Test
    fun `rejects payment below one month dues`() {
        assertThrows(PaymentRejectedException::class.java) {
            AllocationEngine.replay(ym(2026, 2), listOf(pay("2026-02-01", 119_999)), ym(2026, 2))
        }
    }

    @Test
    fun `single exact month payment covers the period with no deposit`() {
        val res = AllocationEngine.replay(ym(2026, 2), listOf(pay("2026-02-01", 120_000)), ym(2026, 2))
        val r = res.perPayment.single()
        assertEquals(listOf(ym(2026, 2)), r.coveredPeriods)
        assertEquals(0, r.depositAfter)
        assertEquals(0, res.account.arrearsTotal)
        assertTrue(res.account.unpaidPeriods.isEmpty())
    }

    @Test
    fun `FIFO clears arrears across the Feb 2026 rate boundary with correct per-period rate`() {
        // Active Dec 2025, unpaid through Mar 2026: 100k+100k+120k+120k = 440k
        val res =
            AllocationEngine.replay(
                ym(2025, 12),
                listOf(pay("2026-03-01", 440_000)),
                ym(2026, 3),
            )
        val lines = duesLines(res.perPayment.single())
        assertEquals(4, lines.size)
        assertEquals(100_000, lines.first { it.period == ym(2025, 12) }.amount)
        assertEquals(100_000, lines.first { it.period == ym(2026, 1) }.amount)
        assertEquals(120_000, lines.first { it.period == ym(2026, 2) }.amount)
        assertEquals(120_000, lines.first { it.period == ym(2026, 3) }.amount)
        assertEquals(0, res.account.arrearsTotal)
    }

    @Test
    fun `deposit carries forward and is folded into the next payment`() {
        // P1: 150k -> covers Feb (120k), 30k deposit.
        // P2: 120k (meets minimum) + 30k deposit = 150k -> covers Mar (120k), 30k deposit again.
        val res =
            AllocationEngine.replay(
                ym(2026, 2),
                listOf(pay("2026-02-01", 150_000), pay("2026-03-01", 120_000)),
                ym(2026, 3),
            )
        assertEquals(30_000, res.perPayment[0].depositAfter)
        assertEquals(listOf(ym(2026, 3)), res.perPayment[1].coveredPeriods)
        assertEquals(30_000, res.perPayment[1].depositAfter)
        assertEquals(30_000, res.account.depositBalance)
        assertEquals(0, res.account.arrearsTotal)
    }

    @Test
    fun `prepay 6 months ahead applies one 60k discount (spec example)`() {
        // First payment makes the house current; second is a pure 6-month prepayment.
        val res =
            AllocationEngine.replay(
                ym(2026, 2),
                listOf(pay("2026-02-01", 120_000), pay("2026-02-10", 660_000)),
                ym(2026, 2),
            )
        val prepay = res.perPayment[1]
        val lines = duesLines(prepay)
        assertEquals(6, lines.size)
        assertEquals(60_000, lines.sumOf { it.discountApplied })
        assertEquals(660_000, lines.sumOf { it.amount })
        assertEquals(0, prepay.depositAfter)
    }

    @Test
    fun `prepay 12 months ahead applies one free month (spec example)`() {
        val res =
            AllocationEngine.replay(
                ym(2026, 2),
                listOf(pay("2026-02-01", 120_000), pay("2026-02-10", 1_320_000)),
                ym(2026, 2),
            )
        val lines = duesLines(res.perPayment[1])
        assertEquals(12, lines.size)
        assertEquals(120_000, lines.sumOf { it.discountApplied })
        assertEquals(1_320_000, lines.sumOf { it.amount })
    }

    @Test
    fun `prepay 18 months ahead applies 12 plus 6 discount`() {
        val res =
            AllocationEngine.replay(
                ym(2026, 2),
                listOf(pay("2026-02-01", 120_000), pay("2026-02-10", 1_980_000)),
                ym(2026, 2),
            )
        val lines = duesLines(res.perPayment[1])
        assertEquals(18, lines.size)
        assertEquals(180_000, lines.sumOf { it.discountApplied })
    }

    @Test
    fun `prepay 24 months ahead applies two free months`() {
        val res =
            AllocationEngine.replay(
                ym(2026, 2),
                listOf(pay("2026-02-01", 120_000), pay("2026-02-10", 2_640_000)),
                ym(2026, 2),
            )
        val lines = duesLines(res.perPayment[1])
        assertEquals(24, lines.size)
        assertEquals(240_000, lines.sumOf { it.discountApplied })
    }

    @Test
    fun `payment settles arrears then penalty`() {
        // Active Jan 2024, no payment until Jul 2024. 6-month gap (Jan..Jun) -> 60k penalty.
        // Pay 7 months arrears (700k) + 60k penalty = 760k.
        val res =
            AllocationEngine.replay(
                ym(2024, 1),
                listOf(pay("2024-07-01", 760_000)),
                ym(2024, 7),
            )
        val r = res.perPayment.single()
        assertEquals(7, duesLines(r).size)
        val penaltyLine = r.lines.single { it.type == AllocationType.PENALTY }
        assertEquals(60_000, penaltyLine.amount)
        assertEquals(0, res.account.penaltyTotal)
        assertEquals(0, res.account.arrearsTotal)
    }

    @Test
    fun `prepaying many months in one payment is never penalized (ADR-0001)`() {
        // Active Feb 2026. A SINGLE payment in Feb 2026 prepays through Dec 2026 (11 covered months
        // beyond the payment month). Only Feb has a payment row, yet every month is covered on time,
        // so there is NO penalty. The old payment-activity rule would have seen Mar..Dec as a
        // 10-month non-payment gap and wrongly assessed 60k.
        val res =
            AllocationEngine.replay(
                ym(2026, 2),
                listOf(pay("2026-02-01", 1_320_000)),
                ym(2026, 12),
            )
        assertEquals(0, res.account.penaltyTotal)
        assertTrue(res.account.penalties.isEmpty())
        assertEquals(0, res.account.arrearsTotal)
        assertEquals(ym(2026, 12), res.account.coveredThrough)
    }

    @Test
    fun `a penalty triggered before catch-up still stands after the lump payment (ADR-0001)`() {
        // Active Jan 2024, silent until a single lump in Jan 2025 clears all 13 months of arrears.
        // Jan..Dec 2024 were uncovered as they elapsed (a 12-month gap), so the 120k penalty is
        // assessed and settled even though the account ends fully caught up — penalties do not
        // vanish on retroactive coverage (decision B: coverage as-of-date).
        val res =
            AllocationEngine.replay(
                ym(2024, 1),
                listOf(pay("2025-01-01", 1_420_000)), // 13 * 100k arrears + 120k penalty
                ym(2025, 1),
            )
        val r = res.perPayment.single()
        assertEquals(13, duesLines(r).size)
        assertEquals(120_000, r.lines.single { it.type == AllocationType.PENALTY }.amount)
        assertEquals(120_000, res.account.penalties.sumOf { it.amount }) // assessed, stuck
        assertEquals(0, res.account.penaltyTotal) // settled by the lump
        assertEquals(0, res.account.arrearsTotal)
    }

    @Test
    fun `unpaid account accrues arrears and penalty (15 months)`() {
        val res = AllocationEngine.replay(ym(2024, 1), emptyList(), ym(2025, 3))
        // 15 months all at legacy rate
        assertEquals(15 * 100_000L, res.account.arrearsTotal)
        assertEquals(120_000, res.account.penaltyTotal)
        assertEquals(15, res.account.unpaidPeriods.size)
    }

    @Test
    fun `single payment clears arrears at full rate then discounts the forward block (Q3a)`() {
        // Active Feb 2026, pay on Apr 2026 -> Feb,Mar,Apr arrears (3 * 120k = 360k) at full rate,
        // remainder prepays 6 months (May..Oct) with one 60k discount: 720k - 60k = 660k.
        // Total 360k + 660k = 1,020,000.
        val res =
            AllocationEngine.replay(
                ym(2026, 2),
                listOf(pay("2026-04-01", 1_020_000)),
                ym(2026, 10),
            )
        val r = res.perPayment.single()
        val dues = duesLines(r)
        assertEquals(9, dues.size)
        // Arrears (Feb,Mar,Apr) at full rate, no discount.
        val arrears = dues.filter { it.period!! <= ym(2026, 4) }
        assertEquals(3, arrears.size)
        assertEquals(0, arrears.sumOf { it.discountApplied })
        assertEquals(360_000, arrears.sumOf { it.amount })
        // Forward block (May..Oct) gets exactly one 60k discount.
        val forward = dues.filter { it.period!! > ym(2026, 4) }
        assertEquals(6, forward.size)
        assertEquals(60_000, forward.sumOf { it.discountApplied })
        assertEquals(660_000, forward.sumOf { it.amount })
        assertEquals(0, r.depositAfter)
    }

    @Test
    fun `same-date payments replay deterministically by createdAt regardless of input order (Q8)`() {
        // Same date; catch-up (120k) recorded before prepayment (660k). The earlier-createdAt
        // catch-up must process first so the prepayment lands on a clean account and gets the
        // 6-month discount — no matter what order the list is passed in.
        val catchUp = PaymentInput(LocalDate.parse("2026-02-01"), 120_000, "A", Instant.parse("2026-02-01T09:00:00Z"))
        val prepay = PaymentInput(LocalDate.parse("2026-02-01"), 660_000, "B", Instant.parse("2026-02-01T10:00:00Z"))

        fun discountFor(payments: List<PaymentInput>): Long {
            val res = AllocationEngine.replay(ym(2026, 2), payments, ym(2026, 8))
            val prepayResult = res.perPayment.single { it.payment.ref == "B" }
            return duesLines(prepayResult).sumOf { it.discountApplied }
        }

        assertEquals(60_000, discountFor(listOf(catchUp, prepay)))
        assertEquals(60_000, discountFor(listOf(prepay, catchUp))) // reversed input, same result
    }
}
