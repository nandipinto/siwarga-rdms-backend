package com.siwarga.rdms.web

import com.siwarga.rdms.domain.DashboardResponse
import com.siwarga.rdms.domain.UserRole
import com.siwarga.rdms.service.DashboardService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Dashboard", description = "Role-aware dashboard summary")
@ApiResponses(
    ApiResponse(responseCode = "401", description = "Not authenticated"),
    ApiResponse(responseCode = "403", description = "Administrator or Supervisor role required"),
)
@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController(
    private val service: DashboardService,
) {
    @Operation(
        summary = "Dashboard summary",
        description =
            "Administrator: full KPIs, trend, recent payments, top arrears, and alerts. " +
                "Supervisor: alerts only.",
    )
    @ApiResponse(responseCode = "200", description = "Dashboard summary")
    @GetMapping
    fun get(auth: Authentication): DashboardResponse {
        val role =
            if (auth.authorities.any { it.authority == "ROLE_${UserRole.ADMINISTRATOR.name}" }) {
                UserRole.ADMINISTRATOR
            } else {
                UserRole.SUPERVISOR
            }
        return service.dashboard(role)
    }
}
