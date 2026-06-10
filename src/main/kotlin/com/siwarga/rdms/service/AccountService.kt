package com.siwarga.rdms.service

import com.siwarga.rdms.calc.AllocationEngine
import com.siwarga.rdms.calc.AllocationType
import com.siwarga.rdms.calc.PaymentInput
import com.siwarga.rdms.calc.ReplayResult
import com.siwarga.rdms.domain.AllocationView
import com.siwarga.rdms.domain.House
import com.siwarga.rdms.domain.Payment
import com.siwarga.rdms.domain.PaymentAllocation
import com.siwarga.rdms.domain.PaymentView
import com.siwarga.rdms.errors.NotFoundException
import com.siwarga.rdms.repository.HouseRepository
import com.siwarga.rdms.repository.PaymentAllocationRepository
import com.siwarga.rdms.repository.PaymentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth
import java.util.UUID

/**
 * Bridges the DB and the pure calculation engine. Owns the recompute-and-persist cycle that
 * MUST run whenever a payment is created, updated, or deleted (spec §6.2, §8).
 */
@Service
class AccountService(
    private val houseRepository: HouseRepository,
    private val paymentRepository: PaymentRepository,
    private val allocationRepository: PaymentAllocationRepository,
) {
    fun loadHouse(houseId: UUID): House = houseRepository.findById(houseId).orElseThrow { NotFoundException("House $houseId not found") }

    /** Read-only replay at [refMonth] (defaults to current month). Does not persist. */
    fun replay(
        house: House,
        refMonth: YearMonth = YearMonth.now(),
    ): ReplayResult {
        val payments = paymentRepository.findByHouseIdOrderByPaymentDateAscCreatedAtAscIdAsc(house.id)
        val inputs = payments.map { PaymentInput(it.paymentDate, it.grossAmount, it.id, it.createdAt) }
        return AllocationEngine.replay(YearMonth.from(house.activeDate), inputs, refMonth)
    }

    /**
     * Recompute all allocations for a house from scratch and persist them, replacing any existing
     * allocation rows. Returns the fresh replay result. Caller must be inside a transaction.
     *
     * Acquires a pessimistic write-lock on the house first so concurrent payment mutations for the
     * same house serialize (spec §6.4 "single transaction" / §8 atomic recompute — grill Q11).
     */
    @Transactional
    fun recomputeAndPersist(house: House): ReplayResult {
        houseRepository.findByIdForUpdate(house.id)
            ?: throw NotFoundException("House ${house.id} not found")
        val payments = paymentRepository.findByHouseIdOrderByPaymentDateAscCreatedAtAscIdAsc(house.id)
        val byId = payments.associateBy { it.id }
        val refMonth = maxOf(YearMonth.now(), payments.maxOfOrNull { YearMonth.from(it.paymentDate) } ?: YearMonth.now())

        val result =
            AllocationEngine.replay(
                YearMonth.from(house.activeDate),
                payments.map { PaymentInput(it.paymentDate, it.grossAmount, it.id, it.createdAt) },
                refMonth,
            )

        payments.forEach { allocationRepository.deleteByPaymentId(it.id) }
        allocationRepository.flush()

        val rows =
            result.perPayment.flatMap { pr ->
                val payment: Payment = byId[pr.payment.ref as UUID]!!
                pr.lines.map { line ->
                    PaymentAllocation(
                        payment = payment,
                        allocationType = line.type,
                        periodYear = line.period?.year?.toShort(),
                        periodMonth = line.period?.monthValue?.toShort(),
                        amount = line.amount,
                        discountApplied = line.discountApplied,
                    )
                }
            }
        allocationRepository.saveAll(rows)
        return result
    }

    /** Lightweight view from persisted allocation rows (no replay) — for list endpoints. */
    fun toStoredView(payment: Payment): PaymentView {
        val rows = allocationRepository.findByPaymentId(payment.id)
        val deposit = rows.firstOrNull { it.allocationType == AllocationType.DEPOSIT }?.amount ?: 0L
        return PaymentView(
            id = payment.id,
            houseId = payment.house.id,
            paymentDate = payment.paymentDate.toString(),
            grossAmount = payment.grossAmount,
            note = payment.note,
            allocations =
                rows.map {
                    AllocationView(
                        it.allocationType,
                        it.periodYear?.toInt(),
                        it.periodMonth?.toInt(),
                        it.amount,
                        it.discountApplied,
                    )
                },
            depositBalance = deposit,
            nextPeriodCovered = null,
        )
    }

    fun toPaymentView(payment: Payment): PaymentView {
        val house = payment.house
        val result = replay(house)
        val pr = result.perPayment.firstOrNull { it.payment.ref == payment.id }
        val allocations =
            pr?.lines?.map {
                AllocationView(it.type, it.period?.year, it.period?.monthValue, it.amount, it.discountApplied)
            } ?: emptyList()
        return PaymentView(
            id = payment.id,
            houseId = house.id,
            paymentDate = payment.paymentDate.toString(),
            grossAmount = payment.grossAmount,
            note = payment.note,
            allocations = allocations,
            depositBalance = result.account.depositBalance,
            nextPeriodCovered = result.account.coveredThrough?.toString(),
        )
    }
}
