package com.siwarga.rdms.web

import com.siwarga.rdms.service.ImportService
import com.siwarga.rdms.service.PaymentService
import com.siwarga.rdms.service.PaymentView
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
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

@RestController
@RequestMapping("/api/v1/payments")
class PaymentController(
    private val service: PaymentService,
    private val importService: ImportService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) houseId: UUID?,
        @RequestParam(required = false) rtId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): Page<PaymentView> = service.list(houseId, rtId, from, to, PageRequest.of(page, size))

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): PaymentView = service.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody req: PaymentRequest,
        auth: Authentication,
    ): PaymentView = service.create(req.houseId, req.paymentDate, req.grossAmount, req.note, auth.name)

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody req: PaymentUpdateRequest,
    ): PaymentView = service.update(id, req.paymentDate, req.grossAmount, req.note)

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        service.delete(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun import(
        @RequestParam("file") file: MultipartFile,
        auth: Authentication,
    ): ImportResult {
        val outcome = importService.importPayments(file.inputStream, auth.name)
        return ImportResult(outcome.totalRows, outcome.successCount, outcome.errorCount, outcome.errors)
    }
}
