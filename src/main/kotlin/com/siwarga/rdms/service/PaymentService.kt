package com.siwarga.rdms.service

import com.siwarga.rdms.calc.DuesRate
import com.siwarga.rdms.domain.Payment
import com.siwarga.rdms.domain.PaymentView
import com.siwarga.rdms.errors.NotFoundException
import com.siwarga.rdms.repository.AppUserRepository
import com.siwarga.rdms.repository.PaymentRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** One row of a bulk payment import for a single house. */
data class PaymentDraft(
    val paymentDate: LocalDate,
    val grossAmount: Long,
    val note: String?,
)

/** Per-draft outcome, index-aligned to the input list; [error] is null on success. */
data class PaymentDraftResult(
    val index: Int,
    val error: String?,
)

@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val appUserRepository: AppUserRepository,
    private val accountService: AccountService,
    private val scopeService: ScopeService,
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
        scopeService.assertHouseInScope(house)
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

    /**
     * Insert a batch of payments for ONE house and recompute the account a single time, instead of
     * once per payment (the per-row [create] path is O(n²) when many rows target the same house).
     *
     * Each draft is validated up front against the same minimum-payment rule the engine enforces, so
     * a below-minimum row is reported individually (its index gets a non-null error) without aborting
     * the others, and the subsequent single recompute cannot be rejected. Returns one result per draft.
     */
    @Transactional
    fun createBatchForHouse(
        houseId: UUID,
        drafts: List<PaymentDraft>,
        username: String,
    ): List<PaymentDraftResult> {
        val house = accountService.loadHouse(houseId)
        scopeService.assertHouseInScope(house)
        val user =
            appUserRepository.findByUsername(username)
                ?: throw NotFoundException("User $username not found")

        var inserted = 0
        val results =
            drafts.mapIndexed { index, draft ->
                val minRate = DuesRate.getRate(YearMonth.from(draft.paymentDate))
                if (draft.grossAmount < minRate) {
                    PaymentDraftResult(
                        index,
                        "Payment ${draft.grossAmount} is below the minimum of $minRate for ${YearMonth.from(draft.paymentDate)}",
                    )
                } else {
                    paymentRepository.save(
                        Payment(
                            house = house,
                            paymentDate = draft.paymentDate,
                            grossAmount = draft.grossAmount,
                            note = draft.note,
                            createdBy = user,
                        ),
                    )
                    inserted++
                    PaymentDraftResult(index, null)
                }
            }
        if (inserted > 0) {
            accountService.recomputeAndPersist(house)
        }
        return results
    }

    @Transactional
    fun update(
        id: UUID,
        paymentDate: LocalDate,
        grossAmount: Long,
        note: String?,
    ): PaymentView {
        val payment = paymentRepository.findById(id).orElseThrow { NotFoundException("Payment $id not found") }
        scopeService.assertHouseInScope(payment.house)
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
        scopeService.assertHouseInScope(payment.house)
        val house = payment.house
        paymentRepository.delete(payment)
        paymentRepository.flush()
        accountService.recomputeAndPersist(house)
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): PaymentView {
        val payment = paymentRepository.findById(id).orElseThrow { NotFoundException("Payment $id not found") }
        scopeService.assertHouseInScope(payment.house)
        return accountService.toPaymentView(payment)
    }

    @Transactional(readOnly = true)
    fun list(
        houseId: UUID?,
        rtId: UUID?,
        from: LocalDate?,
        to: LocalDate?,
        pageable: Pageable,
    ): Page<PaymentView> =
        // Supervisor RT overrides any client-supplied rtId (spec §4.3); admin keeps it.
        paymentRepository
            .search(houseId, scopeService.effectiveRtId(rtId), from, to, pageable)
            .map { accountService.toStoredView(it) }
}
