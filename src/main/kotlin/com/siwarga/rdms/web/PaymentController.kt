package com.siwarga.rdms.web

import com.siwarga.rdms.domain.PaymentView
import com.siwarga.rdms.service.ImportService
import com.siwarga.rdms.service.PaymentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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

@Tag(name = "Payments", description = "Monthly dues payment recording and CSV import")
@ApiResponses(
    ApiResponse(responseCode = "401", description = "Not authenticated"),
    ApiResponse(responseCode = "403", description = "Administrator or Supervisor role required"),
)
@RestController
@RequestMapping("/api/v1/payments")
class PaymentController(
    private val service: PaymentService,
    private val importService: ImportService,
) {
    @Operation(summary = "List payments", description = "Paginated list with optional filters.")
    @ApiResponse(responseCode = "200", description = "Payment page")
    @GetMapping
    fun list(
        @Parameter(description = "Filter by house ID")
        @RequestParam(required = false) houseId: UUID?,
        @Parameter(description = "Filter by RT ID")
        @RequestParam(required = false) rtId: UUID?,
        @Parameter(description = "Inclusive start date (ISO-8601)")
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @Parameter(description = "Inclusive end date (ISO-8601)")
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @Parameter(description = "Zero-based page index")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "Page size")
        @RequestParam(defaultValue = "20") size: Int,
    ): Page<PaymentView> = service.list(houseId, rtId, from, to, PageRequest.of(page, size))

    @Operation(summary = "Get payment by ID")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Payment found"),
        ApiResponse(responseCode = "404", description = "Payment not found"),
    )
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): PaymentView = service.get(id)

    @Operation(summary = "Record payment")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Payment recorded"),
        ApiResponse(responseCode = "400", description = "Validation failed or payment rejected"),
        ApiResponse(responseCode = "404", description = "House not found"),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody req: PaymentRequest,
        auth: Authentication,
    ): PaymentView = service.create(req.houseId, req.paymentDate, req.grossAmount, req.note, auth.name)

    @Operation(summary = "Update payment")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Payment updated"),
        ApiResponse(responseCode = "400", description = "Validation failed or payment rejected"),
        ApiResponse(responseCode = "404", description = "Payment not found"),
    )
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody req: PaymentUpdateRequest,
    ): PaymentView = service.update(id, req.paymentDate, req.grossAmount, req.note)

    @Operation(summary = "Delete payment")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Payment deleted"),
        ApiResponse(responseCode = "404", description = "Payment not found"),
    )
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        service.delete(id)
        return ResponseEntity.noContent().build()
    }

    @Operation(
        summary = "Import payments from CSV",
        description = "Administrator-only bulk import of monthly dues payments.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "Import accepted; see result for per-row outcomes"),
        ApiResponse(responseCode = "400", description = "Invalid or empty file"),
        ApiResponse(responseCode = "403", description = "Administrator role required"),
    )
    @PostMapping("/import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun import(
        @Parameter(description = "CSV file with payment data")
        @RequestParam("file") file: MultipartFile,
        auth: Authentication,
    ): ImportResult {
        val outcome = importService.importPayments(file.inputStream, auth.name)
        return ImportResult(outcome.totalRows, outcome.successCount, outcome.errorCount, outcome.errors)
    }
}
