package com.siwarga.rdms.service

import com.siwarga.rdms.domain.House
import com.siwarga.rdms.domain.RentalGuaranteePayment
import com.siwarga.rdms.errors.BadRequestException
import com.siwarga.rdms.errors.ConflictException
import com.siwarga.rdms.errors.NotFoundException
import com.siwarga.rdms.repository.HouseRepository
import com.siwarga.rdms.repository.RentalGuaranteePaymentRepository
import com.siwarga.rdms.repository.RentalGuaranteeRefundRepository
import com.siwarga.rdms.service.rental.DocumentNumberService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Service
class RentalGuaranteePaymentService(
    private val paymentRepository: RentalGuaranteePaymentRepository,
    private val refundRepository: RentalGuaranteeRefundRepository,
    private val houseRepository: HouseRepository,
    private val appUserRepository: com.siwarga.rdms.repository.AppUserRepository,
    private val documentNumberService: DocumentNumberService,
    private val refundService: RentalGuaranteeRefundService,
    private val scopeService: ScopeService,
) {
    @Transactional
    fun create(
        houseId: UUID,
        paymentDate: LocalDate,
        amountIdr: Long,
        note: String?,
        username: String,
    ): RentalGuaranteePaymentView {
        val house = lockHouse(houseId)
        scopeService.assertHouseInScope(house)
        val obligationId =
            house.rentalGuaranteeObligationId
                ?: throw BadRequestException("House has no active guarantee obligation")
        val expectedAmount =
            house.rentalGuaranteeAmountIdr
                ?: throw BadRequestException("House has no guarantee amount configured")
        if (amountIdr != expectedAmount) {
            throw BadRequestException("amount_idr must equal rental_guarantee_amount_idr ($expectedAmount)")
        }
        if (paymentRepository.existsByObligationId(obligationId)) {
            throw ConflictException("Guarantee already paid for current obligation")
        }
        val tenantName = house.tenantName ?: throw BadRequestException("House has no tenant")
        val tenantEmail = house.tenantEmail ?: throw BadRequestException("House has no tenant email")
        val tenantPhone = house.tenantPhone ?: throw BadRequestException("House has no tenant phone")
        val user =
            appUserRepository.findByUsername(username)
                ?: throw NotFoundException("User $username not found")

        val payment =
            RentalGuaranteePayment(
                house = house,
                obligationId = obligationId,
                receiptNumber = documentNumberService.nextReceiptNumber(paymentDate),
                paymentDate = paymentDate,
                amountIdr = amountIdr,
                paidByName = tenantName,
                paidByEmail = tenantEmail,
                paidByPhone = tenantPhone,
                note = note,
                createdBy = user,
            )
        val saved = paymentRepository.save(payment)
        return toView(saved, refundRepository.findByPaymentId(saved.id)?.let { refundService.toView(it) })
    }

    @Transactional
    fun update(
        id: UUID,
        paymentDate: LocalDate,
        note: String?,
    ): RentalGuaranteePaymentView {
        val payment = paymentRepository.findById(id).orElseThrow { NotFoundException("Guarantee payment $id not found") }
        scopeService.assertHouseInScope(payment.house)
        payment.paymentDate = paymentDate
        payment.note = note
        val saved = paymentRepository.save(payment)
        return toView(saved, refundRepository.findByPaymentId(saved.id)?.let { refundService.toView(it) })
    }

    @Transactional
    fun delete(id: UUID) {
        val payment = paymentRepository.findById(id).orElseThrow { NotFoundException("Guarantee payment $id not found") }
        scopeService.assertHouseInScope(payment.house)
        if (refundRepository.existsByPaymentId(payment.id)) {
            throw ConflictException("Cannot delete receipt with an associated refund")
        }
        lockHouse(payment.house.id)
        paymentRepository.delete(payment)
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): RentalGuaranteePaymentView {
        val payment = paymentRepository.findById(id).orElseThrow { NotFoundException("Guarantee payment $id not found") }
        scopeService.assertHouseInScope(payment.house)
        return toView(payment, refundRepository.findByPaymentId(payment.id)?.let { refundService.toView(it) })
    }

    @Transactional(readOnly = true)
    fun list(
        houseId: UUID?,
        rtId: UUID?,
        from: LocalDate?,
        to: LocalDate?,
    ): List<RentalGuaranteePaymentView> =
        paymentRepository.search(houseId, scopeService.effectiveRtId(rtId), from, to).map { payment ->
            toView(payment, refundRepository.findByPaymentId(payment.id)?.let { refundService.toView(it) })
        }

    @Transactional(readOnly = true)
    fun buildSummary(house: House): RentalGuaranteeSummary? {
        val obligationId = house.rentalGuaranteeObligationId ?: return null
        val amount = house.rentalGuaranteeAmountIdr ?: return null
        val receipt = paymentRepository.findByObligationId(obligationId)
        return RentalGuaranteeSummary(
            required = true,
            amountIdr = amount,
            obligationId = obligationId,
            status = if (receipt != null) "PAID" else "UNPAID",
            receipt = receipt?.let { toView(it, refundRepository.findByPaymentId(it.id)?.let { r -> refundService.toView(r) }) },
        )
    }

    fun toView(
        payment: RentalGuaranteePayment,
        refund: RentalGuaranteeRefundView?,
    ): RentalGuaranteePaymentView {
        val house = payment.house
        return RentalGuaranteePaymentView(
            id = payment.id,
            receiptNumber = payment.receiptNumber,
            houseId = house.id,
            obligationId = payment.obligationId,
            paymentDate = payment.paymentDate.toString(),
            amountIdr = payment.amountIdr,
            paidByName = payment.paidByName,
            paidByEmail = payment.paidByEmail,
            paidByPhone = payment.paidByPhone,
            note = payment.note,
            house =
                RentalGuaranteeHouseSnippet(
                    blockCode = house.blockCode,
                    houseNumber = house.houseNumber,
                    ownerName = house.ownerName,
                    tenantName = house.tenantName,
                    status = house.status.name,
                ),
            createdBy = payment.createdBy.id,
            createdAt = payment.createdAt.toString(),
            refund = refund,
        )
    }

    private fun lockHouse(houseId: UUID): House =
        houseRepository.findByIdForUpdate(houseId)
            ?: throw NotFoundException("House $houseId not found")
}

data class RentalGuaranteePaymentView(
    val id: UUID,
    val receiptNumber: String,
    val houseId: UUID,
    val obligationId: UUID,
    val paymentDate: String,
    val amountIdr: Long,
    val paidByName: String,
    val paidByEmail: String,
    val paidByPhone: String,
    val note: String?,
    val house: RentalGuaranteeHouseSnippet,
    val createdBy: UUID,
    val createdAt: String,
    val refund: RentalGuaranteeRefundView?,
)

data class RentalGuaranteeHouseSnippet(
    val blockCode: String,
    val houseNumber: String,
    val ownerName: String,
    val tenantName: String?,
    val status: String,
)

data class RentalGuaranteeSummary(
    val required: Boolean,
    val amountIdr: Long,
    val obligationId: UUID,
    val status: String,
    val receipt: RentalGuaranteePaymentView?,
)
