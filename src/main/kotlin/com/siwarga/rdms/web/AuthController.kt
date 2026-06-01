package com.siwarga.rdms.web

import com.siwarga.rdms.repository.AppUserRepository
import com.siwarga.rdms.security.JwtService
import jakarta.validation.Valid
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authenticationManager: AuthenticationManager,
    private val jwtService: JwtService,
    private val userRepository: AppUserRepository,
) {
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
