package com.siwarga.rdms.web

import com.siwarga.rdms.domain.RefundStatus
import com.siwarga.rdms.errors.NotFoundException
import com.siwarga.rdms.repository.AppUserRepository
import com.siwarga.rdms.service.ImportService
import com.siwarga.rdms.service.RentalGuaranteePaymentService
import com.siwarga.rdms.service.RentalGuaranteeRefundService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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

@Tag(name = "Rental Guarantee", description = "Rental guarantee receipts, refunds, and CSV import")
@ApiResponses(
    ApiResponse(responseCode = "401", description = "Not authenticated"),
    ApiResponse(responseCode = "403", description = "Administrator or Supervisor role required"),
)
@RestController
@RequestMapping("/api/v1/rental-guarantee")
class RentalGuaranteeController(
    private val paymentService: RentalGuaranteePaymentService,
    private val refundService: RentalGuaranteeRefundService,
    private val importService: ImportService,
    private val appUserRepository: AppUserRepository,
) {
    @Operation(summary = "List rental guarantee payments")
    @ApiResponse(responseCode = "200", description = "Payment list")
    @GetMapping("/payments")
    fun listPayments(
        @Parameter(description = "Filter by house ID")
        @RequestParam(name = "houseId", required = false) houseId: UUID?,
        @Parameter(description = "Filter by RT ID")
        @RequestParam(name = "rtId", required = false) rtId: UUID?,
        @Parameter(description = "Inclusive start date (ISO-8601)")
        @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @Parameter(description = "Inclusive end date (ISO-8601)")
        @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): List<RentalGuaranteePaymentResponse> = paymentService.list(houseId, rtId, from, to).map { it.toResponse() }

    @Operation(summary = "Record rental guarantee payment")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Payment recorded"),
        ApiResponse(responseCode = "400", description = "Validation failed"),
        ApiResponse(responseCode = "404", description = "House not found"),
    )
    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPayment(
        @Valid @RequestBody req: RentalGuaranteePaymentRequest,
        authentication: Authentication,
    ): RentalGuaranteePaymentResponse =
        paymentService
            .create(req.houseId, req.paymentDate, req.amountIdr, req.note, authentication.name)
            .toResponse()

    @Operation(summary = "Get rental guarantee payment by ID")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Payment found"),
        ApiResponse(responseCode = "404", description = "Payment not found"),
    )
    @GetMapping("/payments/{id}")
    fun getPayment(
        @PathVariable id: UUID,
    ): RentalGuaranteePaymentResponse = paymentService.get(id).toResponse()

    @Operation(summary = "Update rental guarantee payment")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Payment updated"),
        ApiResponse(responseCode = "404", description = "Payment not found"),
    )
    @PutMapping("/payments/{id}")
    fun updatePayment(
        @PathVariable id: UUID,
        @Valid @RequestBody req: RentalGuaranteePaymentUpdateRequest,
    ): RentalGuaranteePaymentResponse = paymentService.update(id, req.paymentDate, req.note).toResponse()

    @Operation(summary = "Delete rental guarantee payment")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Payment deleted"),
        ApiResponse(responseCode = "404", description = "Payment not found"),
    )
    @DeleteMapping("/payments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePayment(
        @PathVariable id: UUID,
    ) {
        paymentService.delete(id)
    }

    @Operation(
        summary = "Import rental guarantee payments from CSV",
        description = "Administrator-only bulk import of rental guarantee receipts.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "Import accepted; see result for per-row outcomes"),
        ApiResponse(responseCode = "400", description = "Invalid or empty file"),
        ApiResponse(responseCode = "403", description = "Administrator role required"),
    )
    @PostMapping("/payments/import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun importPayments(
        @Parameter(description = "CSV file with rental guarantee payment data")
        @RequestParam("file") file: MultipartFile,
        authentication: Authentication,
    ): ImportResult {
        val outcome = importService.importRentalGuaranteePayments(file.inputStream, authentication.name)
        return ImportResult(outcome.totalRows, outcome.successCount, outcome.errorCount, outcome.errors)
    }

    @Operation(summary = "List rental guarantee refunds")
    @ApiResponse(responseCode = "200", description = "Refund list")
    @GetMapping("/refunds")
    fun listRefunds(
        @Parameter(description = "Filter by house ID")
        @RequestParam(name = "houseId", required = false) houseId: UUID?,
        @Parameter(description = "Filter by RT ID")
        @RequestParam(name = "rtId", required = false) rtId: UUID?,
        @Parameter(description = "Filter by refund status")
        @RequestParam(name = "status", required = false) status: RefundStatus?,
        @Parameter(description = "Inclusive start date (ISO-8601)")
        @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @Parameter(description = "Inclusive end date (ISO-8601)")
        @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): List<RentalGuaranteeRefundResponse> = refundService.list(houseId, rtId, status, from, to).map { it.toResponse() }

    @Operation(summary = "Get rental guarantee refund by ID")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Refund found"),
        ApiResponse(responseCode = "404", description = "Refund not found"),
    )
    @GetMapping("/refunds/{id}")
    fun getRefund(
        @PathVariable id: UUID,
    ): RentalGuaranteeRefundResponse = refundService.getView(id).toResponse()

    @Operation(summary = "Complete rental guarantee refund")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Refund completed"),
        ApiResponse(responseCode = "400", description = "Refund cannot be completed in current state"),
        ApiResponse(responseCode = "404", description = "Refund not found"),
    )
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

    @Operation(summary = "Cancel rental guarantee refund")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Refund cancelled"),
        ApiResponse(responseCode = "404", description = "Refund not found"),
    )
    @DeleteMapping("/refunds/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelRefund(
        @PathVariable id: UUID,
    ) {
        refundService.cancel(id)
    }
}
