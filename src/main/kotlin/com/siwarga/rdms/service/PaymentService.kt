package com.siwarga.rdms.service

import com.siwarga.rdms.domain.Payment
import com.siwarga.rdms.repository.AppUserRepository
import com.siwarga.rdms.repository.PaymentRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val appUserRepository: AppUserRepository,
    private val accountService: AccountService,
) {
    @Transactional
    fun create(
        houseId: UUID,
        paymentDate: LocalDate,
        grossAmount: Long,
        note: String?,
        username: String,
    ): PaymentView {
        val house = accountService.loadHouse(houseId)
        val user =
            appUserRepository.findByUsername(username)
                ?: throw NotFoundException("User $username not found")

        val payment =
            Payment(
                house = house,
                paymentDate = paymentDate,
                grossAmount = grossAmount,
                note = note,
                createdBy = user,
            )
        paymentRepository.save(payment)
        // Recompute the whole house account; this also validates the new payment via the engine.
        accountService.recomputeAndPersist(house)
        return accountService.toPaymentView(payment)
    }

    @Transactional
    fun update(
        id: UUID,
        paymentDate: LocalDate,
        grossAmount: Long,
        note: String?,
    ): PaymentView {
        val payment = paymentRepository.findById(id).orElseThrow { NotFoundException("Payment $id not found") }
        payment.paymentDate = paymentDate
        payment.grossAmount = grossAmount
        payment.note = note
        paymentRepository.save(payment)
        accountService.recomputeAndPersist(payment.house)
        return accountService.toPaymentView(payment)
    }

    @Transactional
    fun delete(id: UUID) {
        val payment = paymentRepository.findById(id).orElseThrow { NotFoundException("Payment $id not found") }
        val house = payment.house
        paymentRepository.delete(payment)
        paymentRepository.flush()
        accountService.recomputeAndPersist(house)
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): PaymentView {
        val payment = paymentRepository.findById(id).orElseThrow { NotFoundException("Payment $id not found") }
        return accountService.toPaymentView(payment)
    }

    @Transactional(readOnly = true)
    fun list(
        houseId: UUID?,
        rtId: UUID?,
        from: LocalDate?,
        to: LocalDate?,
        pageable: Pageable,
    ): Page<PaymentView> = paymentRepository.search(houseId, rtId, from, to, pageable).map { accountService.toStoredView(it) }
}
