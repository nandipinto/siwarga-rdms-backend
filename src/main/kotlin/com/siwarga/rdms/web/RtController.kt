package com.siwarga.rdms.web

import com.siwarga.rdms.service.RtService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/rts")
class RtController(
    private val service: RtService,
) {
    @GetMapping
    fun list(): List<RtResponse> = service.list().map { it.toResponse() }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): RtResponse = service.get(id).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody req: RtRequest,
    ): RtResponse = service.create(req.rtCode, req.description).toResponse()

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody req: RtRequest,
    ): RtResponse = service.update(id, req.rtCode, req.description).toResponse()

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        service.delete(id)
        return ResponseEntity.noContent().build()
    }
}
