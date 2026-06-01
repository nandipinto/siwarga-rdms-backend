package com.siwarga.rdms.config

import com.siwarga.rdms.domain.UserRole
import com.siwarga.rdms.repository.AppUserRepository
import com.siwarga.rdms.service.UserService
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AppConfig {
    private val log = LoggerFactory.getLogger(AppConfig::class.java)

    /** Seed a default Administrator on first startup if no users exist (spec memory / NFR config). */
    @Bean
    fun adminSeeder(
        userRepository: AppUserRepository,
        userService: UserService,
        adminProps: AdminProperties,
    ) = CommandLineRunner {
        if (userRepository.count() == 0L) {
            userService.create(adminProps.username, adminProps.password, UserRole.ADMINISTRATOR)
            log.info("Seeded default administrator '{}'", adminProps.username)
        }
    }

    @Bean
    fun openApi(): OpenAPI {
        val scheme = "bearer-jwt"
        return OpenAPI()
            .info(Info().title("RDMS API").version("1.0.0").description("Resident Dues Management System"))
            .addSecurityItem(SecurityRequirement().addList(scheme))
            .components(
                Components().addSecuritySchemes(
                    scheme,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"),
                ),
            )
    }
}
