package com.siwarga.rdms.service

import com.siwarga.rdms.domain.AppUser
import com.siwarga.rdms.domain.RefundStatus
import com.siwarga.rdms.domain.RentalGuaranteePayment
import com.siwarga.rdms.domain.RentalGuaranteeRefund
import com.siwarga.rdms.errors.BadRequestException
import com.siwarga.rdms.errors.ConflictException
import com.siwarga.rdms.errors.NotFoundException
import com.siwarga.rdms.repository.RentalGuaranteeRefundRepository
import com.siwarga.rdms.service.rental.DocumentNumberService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class RentalGuaranteeRefundService(
    private val refundRepository: RentalGuaranteeRefundRepository,
    private val documentNumberService: DocumentNumberService,
    private val scopeService: ScopeService,
) {
    @Transactional
    fun createPendingForLeaseEnd(
        payment: RentalGuaranteePayment,
        createdBy: AppUser,
    ): RentalGuaranteeRefund {
        if (refundRepository.existsByPaymentId(payment.id)) {
            throw ConflictException("Refund already exists for payment ${payment.id}")
        }
        val refund =
            RentalGuaranteeRefund(
                payment = payment,
                house = payment.house,
                obligationId = payment.obligationId,
                status = RefundStatus.PENDING,
                amountIdr = payment.amountIdr,
                refundedToName = payment.paidByName,
                refundedToEmail = payment.paidByEmail,
                refundedToPhone = payment.paidByPhone,
                createdBy = createdBy,
            )
        return refundRepository.save(refund)
    }

    @Transactional
    fun complete(
        id: UUID,
        refundDate: LocalDate,
        note: String?,
        completedBy: AppUser,
    ): RentalGuaranteeRefund {
        // Write-lock the row first: two concurrent completions then serialize, and the second
        // re-reads status = COMPLETED and is rejected below (no double-completion / double refund number).
        val refund = refundRepository.findByIdForUpdate(id) ?: throw NotFoundException("Refund $id not found")
        scopeService.assertHouseInScope(refund.house)
        if (refund.status != RefundStatus.PENDING) {
            throw BadRequestException("Only PENDING refunds can be completed")
        }
        val refundNumber = documentNumberService.nextRefundNumber(refundDate)
        val completedAt = Instant.now()
        refund.refundNumber = refundNumber
        refund.refundDate = refundDate
        refund.note = note
        refund.completedBy = completedBy
        refund.completedAt = completedAt
        refund.status = RefundStatus.COMPLETED
        return refundRepository.save(refund)
    }

    @Transactional
    fun cancel(id: UUID) {
        val refund = refundRepository.findByIdForUpdate(id) ?: throw NotFoundException("Refund $id not found")
        scopeService.assertHouseInScope(refund.house)
        if (refund.status != RefundStatus.PENDING) {
            throw BadRequestException("Only PENDING refunds can be cancelled")
        }
        refundRepository.delete(refund)
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): RentalGuaranteeRefund = refundRepository.findById(id).orElseThrow { NotFoundException("Refund $id not found") }

    @Transactional(readOnly = true)
    fun list(
        houseId: UUID?,
        rtId: UUID?,
        status: RefundStatus?,
        from: LocalDate?,
        to: LocalDate?,
    ): List<RentalGuaranteeRefundView> {
        val fromInstant = from?.atStartOfDay(java.time.ZoneOffset.UTC)?.toInstant()
        val toInstant =
            to
                ?.plusDays(1)
                ?.atStartOfDay(java.time.ZoneOffset.UTC)
                ?.toInstant()
                ?.minusNanos(1)
        return refundRepository
            .search(
                houseId,
                scopeService.effectiveRtId(rtId),
                status,
                filterFrom = fromInstant != null,
                fromInstant = fromInstant ?: java.time.Instant.EPOCH,
                filterTo = toInstant != null,
                toInstant = toInstant ?: java.time.Instant.parse("9999-12-31T23:59:59Z"),
            ).map { toView(it) }
    }

    @Transactional(readOnly = true)
    fun getView(id: UUID): RentalGuaranteeRefundView {
        val refund = refundRepository.findById(id).orElseThrow { NotFoundException("Refund $id not found") }
        scopeService.assertHouseInScope(refund.house)
        return toView(refund)
    }

    fun toView(refund: RentalGuaranteeRefund): RentalGuaranteeRefundView =
        RentalGuaranteeRefundView(
            id = refund.id,
            refundNumber = refund.refundNumber,
            status = refund.status.name,
            paymentId = refund.payment.id,
            receiptNumber = refund.payment.receiptNumber,
            houseId = refund.house.id,
            obligationId = refund.obligationId,
            amountIdr = refund.amountIdr,
            refundedToName = refund.refundedToName,
            refundedToEmail = refund.refundedToEmail,
            refundedToPhone = refund.refundedToPhone,
            refundDate = refund.refundDate?.toString(),
            note = refund.note,
            createdBy = refund.createdBy.id,
            completedBy = refund.completedBy?.id,
            createdAt = refund.createdAt.toString(),
            completedAt = refund.completedAt?.toString(),
        )
}

data class RentalGuaranteeRefundView(
    val id: UUID,
    val refundNumber: String?,
    val status: String,
    val paymentId: UUID,
    val receiptNumber: String,
    val houseId: UUID,
    val obligationId: UUID,
    val amountIdr: Long,
    val refundedToName: String,
    val refundedToEmail: String,
    val refundedToPhone: String,
    val refundDate: String?,
    val note: String?,
    val createdBy: UUID,
    val completedBy: UUID?,
    val createdAt: String,
    val completedAt: String?,
)
