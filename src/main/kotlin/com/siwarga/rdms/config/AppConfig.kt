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
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.nio.charset.StandardCharsets

@Configuration
class AppConfig {
    private val log = LoggerFactory.getLogger(AppConfig::class.java)

    companion object {
        /** Built-in dev fallbacks declared in application.properties; never acceptable in production. */
        const val DEFAULT_DEV_JWT_SECRET = "change-me-in-prod-this-is-a-dev-only-secret-key-min-256-bits!!"
        const val DEFAULT_DEV_ADMIN_PASSWORD = "admin123"
        private const val MIN_JWT_SECRET_BYTES = 32 // 256 bits, required for HS256
    }

    /**
     * Fail fast on insecure configuration. The JWT secret length is always enforced. In the 'prod'
     * profile the built-in dev secret/admin password are rejected outright; otherwise a loud warning
     * is logged so local/dev runs still work but the risk is visible.
     */
    @Bean
    fun securityConfigValidator(
        env: Environment,
        jwtProps: JwtProperties,
        adminProps: AdminProperties,
    ) = InitializingBean {
        if (jwtProps.secret.toByteArray(StandardCharsets.UTF_8).size < MIN_JWT_SECRET_BYTES) {
            throw IllegalStateException(
                "rdms.jwt.secret must be at least $MIN_JWT_SECRET_BYTES bytes (256 bits) for HS256; set JWT_SECRET.",
            )
        }
        val usingDefaultSecret = jwtProps.secret == DEFAULT_DEV_JWT_SECRET
        val usingDefaultAdminPassword = adminProps.password == DEFAULT_DEV_ADMIN_PASSWORD
        val isProd = env.activeProfiles.contains("prod")

        if (isProd) {
            check(!usingDefaultSecret) { "Refusing to start in the 'prod' profile with the default JWT secret; set JWT_SECRET." }
            check(!usingDefaultAdminPassword) {
                "Refusing to start in the 'prod' profile with the default admin password; set ADMIN_PASSWORD."
            }
        } else {
            if (usingDefaultSecret) log.warn("Using the built-in DEV JWT secret. Set JWT_SECRET before deploying to production.")
            if (usingDefaultAdminPassword) {
                log.warn("Using the built-in DEV admin password. Set ADMIN_PASSWORD before deploying to production.")
            }
        }
    }

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
