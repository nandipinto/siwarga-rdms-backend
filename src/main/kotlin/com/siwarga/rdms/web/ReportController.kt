package com.siwarga.rdms.web

import com.siwarga.rdms.domain.ArrearsReport
import com.siwarga.rdms.domain.MonthlyDuesReport
import com.siwarga.rdms.service.ReportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Tag(name = "Reports", description = "Dues and arrears reporting")
@ApiResponses(
    ApiResponse(responseCode = "401", description = "Not authenticated"),
    ApiResponse(responseCode = "403", description = "Administrator or Supervisor role required"),
)
@RestController
@RequestMapping("/api/v1/reports")
class ReportController(
    private val service: ReportService,
) {
    @Operation(summary = "Monthly dues report", description = "Aggregated dues status, optionally filtered by RT or house.")
    @ApiResponse(responseCode = "200", description = "Monthly dues report")
    @GetMapping("/dues/monthly")
    fun monthlyDues(
        @Parameter(description = "Filter by RT ID")
        @RequestParam(required = false) rtId: UUID?,
        @Parameter(description = "Filter by house ID")
        @RequestParam(required = false) houseId: UUID?,
    ): MonthlyDuesReport = service.monthlyDues(rtId, houseId)

    @Operation(summary = "Arrears report for a house")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Arrears report"),
        ApiResponse(responseCode = "404", description = "House not found"),
    )
    @GetMapping("/arrears/{houseId}")
    fun arrears(
        @PathVariable houseId: UUID,
    ): ArrearsReport = service.arrears(houseId)
}
