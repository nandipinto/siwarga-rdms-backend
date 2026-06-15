package com.siwarga.rdms.web

import com.siwarga.rdms.repository.AppUserRepository
import com.siwarga.rdms.security.JwtService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Auth", description = "Login and JWT issuance")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authenticationManager: AuthenticationManager,
    private val jwtService: JwtService,
    private val userRepository: AppUserRepository,
) {
    @Operation(summary = "Login", description = "Authenticate with username and password; returns a JWT for subsequent API calls.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Login successful"),
        ApiResponse(responseCode = "401", description = "Invalid credentials"),
        ApiResponse(responseCode = "400", description = "Validation failed"),
    )
    @SecurityRequirements
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody req: LoginRequest,
    ): LoginResponse {
        authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken(req.username, req.password),
        )
        val user = userRepository.findByUsername(req.username)!!
        val token = jwtService.generate(user.username, user.role.name)
        return LoginResponse(token, user.role.name, user.username)
    }
}
