package com.siwarga.rdms.web

import com.siwarga.rdms.service.RwService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
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

@Tag(name = "RW", description = "RW (neighbourhood association) management")
@ApiResponses(
    ApiResponse(responseCode = "401", description = "Not authenticated"),
    ApiResponse(responseCode = "403", description = "Administrator role required"),
)
@RestController
@RequestMapping("/api/v1/rws")
class RwController(
    private val service: RwService,
) {
    @Operation(summary = "List RWs")
    @ApiResponse(responseCode = "200", description = "RW list")
    @GetMapping
    fun list(): List<RwResponse> = service.list().map { it.toResponse() }

    @Operation(summary = "Get RW by ID")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "RW found"),
        ApiResponse(responseCode = "404", description = "RW not found"),
    )
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): RwResponse = service.get(id).toResponse()

    @Operation(summary = "Create RW")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "RW created"),
        ApiResponse(responseCode = "400", description = "Validation failed"),
        ApiResponse(responseCode = "409", description = "RW code already exists"),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody req: RwRequest,
    ): RwResponse = service.create(req.rwCode, req.description).toResponse()

    @Operation(summary = "Update RW")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "RW updated"),
        ApiResponse(responseCode = "404", description = "RW not found"),
        ApiResponse(responseCode = "409", description = "RW code already exists"),
    )
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody req: RwRequest,
    ): RwResponse = service.update(id, req.rwCode, req.description).toResponse()

    @Operation(summary = "Delete RW")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "RW deleted"),
        ApiResponse(responseCode = "404", description = "RW not found"),
        ApiResponse(responseCode = "409", description = "RW has dependent RTs"),
    )
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        service.delete(id)
        return ResponseEntity.noContent().build()
    }
}
