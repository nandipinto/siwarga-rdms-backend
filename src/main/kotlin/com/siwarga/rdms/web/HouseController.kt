package com.siwarga.rdms.web

import com.siwarga.rdms.service.HouseService
import com.siwarga.rdms.service.ImportService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
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

@RestController
@RequestMapping("/api/v1/houses")
class HouseController(
    private val service: HouseService,
    private val importService: ImportService,
) {
    @GetMapping
    fun list(
        @RequestParam(name = "rtId", required = false) rtId: UUID?,
    ): List<HouseResponse> = service.list(rtId).map { service.buildDetail(it).toResponse() }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): HouseResponse = service.buildDetail(service.get(id)).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody req: HouseRequest,
    ): HouseResponse = service.buildDetail(service.create(req)).toResponse()

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody req: HouseRequest,
        authentication: Authentication,
    ): HouseResponse = service.buildDetail(service.update(id, req, authentication.name)).toResponse()

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun import(
        @RequestParam("file") file: MultipartFile,
        authentication: Authentication,
    ): ImportResult {
        val outcome = importService.importHouses(file.inputStream, authentication.name)
        return ImportResult(outcome.totalRows, outcome.successCount, outcome.errorCount, outcome.errors)
    }
}
