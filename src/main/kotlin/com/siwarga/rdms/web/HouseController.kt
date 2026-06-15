package com.siwarga.rdms.web

import com.siwarga.rdms.service.HouseService
import com.siwarga.rdms.service.ImportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
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
import java.util.UUID

@Tag(name = "Houses", description = "House registration and CSV import")
@ApiResponses(
    ApiResponse(responseCode = "401", description = "Not authenticated"),
    ApiResponse(responseCode = "403", description = "Administrator role required"),
)
@RestController
@RequestMapping("/api/v1/houses")
class HouseController(
    private val service: HouseService,
    private val importService: ImportService,
) {
    @Operation(summary = "List houses", description = "Optionally filter by RT.")
    @ApiResponse(responseCode = "200", description = "House list")
    @GetMapping
    fun list(
        @Parameter(description = "Filter by RT ID")
        @RequestParam(name = "rtId", required = false) rtId: UUID?,
    ): List<HouseResponse> = service.list(rtId).map { service.buildDetail(it).toResponse() }

    @Operation(summary = "Get house by ID")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "House found"),
        ApiResponse(responseCode = "404", description = "House not found"),
    )
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): HouseResponse = service.buildDetail(service.get(id)).toResponse()

    @Operation(summary = "Create house")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "House created"),
        ApiResponse(responseCode = "400", description = "Validation failed"),
        ApiResponse(responseCode = "404", description = "RT not found"),
        ApiResponse(responseCode = "409", description = "House already exists in RT"),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody req: HouseRequest,
    ): HouseResponse = service.buildDetail(service.create(req)).toResponse()

    @Operation(summary = "Update house")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "House updated"),
        ApiResponse(responseCode = "404", description = "House or RT not found"),
        ApiResponse(responseCode = "409", description = "House already exists in RT"),
    )
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody req: HouseRequest,
        authentication: Authentication,
    ): HouseResponse = service.buildDetail(service.update(id, req, authentication.name)).toResponse()

    @Operation(
        summary = "Import houses from CSV",
        description = "Upload a CSV file to bulk-create or update houses. Returns row-level errors without rolling back successful rows.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "Import accepted; see result for per-row outcomes"),
        ApiResponse(responseCode = "400", description = "Invalid or empty file"),
    )
    @PostMapping("/import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun import(
        @Parameter(description = "CSV file with house data")
        @RequestParam("file") file: MultipartFile,
        authentication: Authentication,
    ): ImportResult {
        val outcome = importService.importHouses(file.inputStream, authentication.name)
        return ImportResult(outcome.totalRows, outcome.successCount, outcome.errorCount, outcome.errors)
    }
}
