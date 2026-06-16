package com.siwarga.rdms.config

import com.siwarga.rdms.domain.UserRole
import com.siwarga.rdms.security.JwtAuthFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
class SecurityConfig {
    private val admin = UserRole.ADMINISTRATOR.name
    private val supervisor = UserRole.SUPERVISOR.name

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(12) // cost >= 12 (spec §8)

    @Bean
    fun authenticationManager(
        userDetailsService: UserDetailsService,
        passwordEncoder: PasswordEncoder,
    ): AuthenticationManager {
        val provider = DaoAuthenticationProvider()
        provider.setUserDetailsService(userDetailsService)
        provider.setPasswordEncoder(passwordEncoder)
        return AuthenticationManager { provider.authenticate(it) }
    }

    @Bean
    fun filterChain(
        http: HttpSecurity,
        jwtAuthFilter: JwtAuthFilter,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/api/v1/auth/**",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml",
                    ).permitAll()
                    // Payment CSV import is Administrator-only (spec §4.2).
                    .requestMatchers(HttpMethod.POST, "/api/v1/payments/import")
                    .hasRole(admin)
                    // Rental guarantee CSV import is Administrator-only (spec §4.2).
                    .requestMatchers(HttpMethod.POST, "/api/v1/rental-guarantee/payments/import")
                    .hasRole(admin)
                    // Rental guarantee receipts/refunds: Administrator + Supervisor.
                    .requestMatchers("/api/v1/rental-guarantee/**")
                    .hasAnyRole(admin, supervisor)
                    // Payment CRUD: Administrator + Supervisor.
                    .requestMatchers("/api/v1/payments/**")
                    .hasAnyRole(admin, supervisor)
                    // Reports: Administrator + Supervisor.
                    .requestMatchers("/api/v1/reports/**")
                    .hasAnyRole(admin, supervisor)
                    // Dashboard: Administrator + Supervisor (role-aware payload).
                    .requestMatchers("/api/v1/dashboard")
                    .hasAnyRole(admin, supervisor)
                    // House READ: Administrator + Supervisor (RT-scoped, spec §4.3). Writes/import
                    // stay Administrator-only via the catch-all below (this matcher is GET-only).
                    .requestMatchers(HttpMethod.GET, "/api/v1/houses", "/api/v1/houses/**")
                    .hasAnyRole(admin, supervisor)
                    // Everything else (RW, RT, house write/import, users): Administrator only.
                    .requestMatchers("/api/v1/**")
                    .hasRole(admin)
                    .anyRequest()
                    .authenticated()
            }.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
        return http.build()
    }
}
