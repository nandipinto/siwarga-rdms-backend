package com.siwarga.rdms.web

import com.siwarga.rdms.service.RtService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Tag(name = "RT", description = "RT (community unit) management")
@ApiResponses(
    ApiResponse(responseCode = "401", description = "Not authenticated"),
    ApiResponse(responseCode = "403", description = "Administrator role required"),
)
@RestController
@RequestMapping("/api/v1/rts")
class RtController(
    private val service: RtService,
) {
    @Operation(summary = "List RTs", description = "Optionally filter by parent RW.")
    @ApiResponse(responseCode = "200", description = "RT list")
    @GetMapping
    fun list(
        @Parameter(description = "Filter by parent RW ID")
        @RequestParam(required = false) rwId: UUID?,
    ): List<RtResponse> = service.list(rwId).map { it.toResponse() }

    @Operation(summary = "Get RT by ID")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "RT found"),
        ApiResponse(responseCode = "404", description = "RT not found"),
    )
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): RtResponse = service.get(id).toResponse()

    @Operation(summary = "Create RT")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "RT created"),
        ApiResponse(responseCode = "400", description = "Validation failed"),
        ApiResponse(responseCode = "404", description = "Parent RW not found"),
        ApiResponse(responseCode = "409", description = "RT code already exists in RW"),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody req: RtRequest,
    ): RtResponse = service.create(req.rwId, req.rtCode, req.description).toResponse()

    @Operation(summary = "Update RT")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "RT updated"),
        ApiResponse(responseCode = "404", description = "RT or RW not found"),
        ApiResponse(responseCode = "409", description = "RT code already exists in RW"),
    )
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody req: RtRequest,
    ): RtResponse = service.update(id, req.rwId, req.rtCode, req.description).toResponse()

    @Operation(summary = "Delete RT")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "RT deleted"),
        ApiResponse(responseCode = "404", description = "RT not found"),
        ApiResponse(responseCode = "409", description = "RT has dependent houses"),
    )
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        service.delete(id)
        return ResponseEntity.noContent().build()
    }
}
