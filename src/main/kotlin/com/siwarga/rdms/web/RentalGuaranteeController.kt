package com.siwarga.rdms.web

import com.siwarga.rdms.domain.RefundStatus
import com.siwarga.rdms.repository.AppUserRepository
import com.siwarga.rdms.service.ImportService
import com.siwarga.rdms.service.NotFoundException
import com.siwarga.rdms.service.RentalGuaranteePaymentService
import com.siwarga.rdms.service.RentalGuaranteeRefundService
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/rental-guarantee")
class RentalGuaranteeController(
    private val paymentService: RentalGuaranteePaymentService,
    private val refundService: RentalGuaranteeRefundService,
    private val importService: ImportService,
    private val appUserRepository: AppUserRepository,
) {
    @GetMapping("/payments")
    fun listPayments(
        @RequestParam(name = "houseId", required = false) houseId: UUID?,
        @RequestParam(name = "rtId", required = false) rtId: UUID?,
        @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): List<RentalGuaranteePaymentResponse> = paymentService.list(houseId, rtId, from, to).map { it.toResponse() }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPayment(
        @Valid @RequestBody req: RentalGuaranteePaymentRequest,
        authentication: Authentication,
    ): RentalGuaranteePaymentResponse =
        paymentService
            .create(req.houseId, req.paymentDate, req.amountIdr, req.note, authentication.name)
            .toResponse()

    @GetMapping("/payments/{id}")
    fun getPayment(
        @PathVariable id: UUID,
    ): RentalGuaranteePaymentResponse = paymentService.get(id).toResponse()

    @PutMapping("/payments/{id}")
    fun updatePayment(
        @PathVariable id: UUID,
        @Valid @RequestBody req: RentalGuaranteePaymentUpdateRequest,
    ): RentalGuaranteePaymentResponse = paymentService.update(id, req.paymentDate, req.note).toResponse()

    @DeleteMapping("/payments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePayment(
        @PathVariable id: UUID,
    ) {
        paymentService.delete(id)
    }

    @PostMapping("/payments/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun importPayments(
        @RequestParam("file") file: MultipartFile,
        authentication: Authentication,
    ): ImportResult {
        val outcome = importService.importRentalGuaranteePayments(file.inputStream, authentication.name)
        return ImportResult(outcome.totalRows, outcome.successCount, outcome.errorCount, outcome.errors)
    }

    @GetMapping("/refunds")
    fun listRefunds(
        @RequestParam(name = "houseId", required = false) houseId: UUID?,
        @RequestParam(name = "rtId", required = false) rtId: UUID?,
        @RequestParam(name = "status", required = false) status: RefundStatus?,
        @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): List<RentalGuaranteeRefundResponse> = refundService.list(houseId, rtId, status, from, to).map { it.toResponse() }

    @GetMapping("/refunds/{id}")
    fun getRefund(
        @PathVariable id: UUID,
    ): RentalGuaranteeRefundResponse = refundService.getView(id).toResponse()

    @PostMapping("/refunds/{id}/complete")
    fun completeRefund(
        @PathVariable id: UUID,
        @Valid @RequestBody req: RentalGuaranteeRefundCompleteRequest,
        authentication: Authentication,
    ): RentalGuaranteeRefundResponse {
        val user =
            appUserRepository.findByUsername(authentication.name)
                ?: throw NotFoundException("User ${authentication.name} not found")
        return refundService.getView(refundService.complete(id, req.refundDate, req.note, user).id).toResponse()
    }

    @DeleteMapping("/refunds/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelRefund(
        @PathVariable id: UUID,
    ) {
        refundService.cancel(id)
    }
}
