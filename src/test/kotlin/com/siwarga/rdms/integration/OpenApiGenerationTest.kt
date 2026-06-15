package com.siwarga.rdms.integration

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class OpenApiGenerationTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("rdms.admin.username") { "admin" }
            registry.add("rdms.admin.password") { "admin123" }
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun exportOpenApiSpec() {
        val outputDir = Path.of("target", "openapi")
        Files.createDirectories(outputDir)

        val json =
            mockMvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("RDMS API"))
                .andReturn()
                .response
                .contentAsString
        Files.writeString(outputDir.resolve("openapi.json"), json)

        val yaml =
            mockMvc
                .perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        Files.writeString(outputDir.resolve("openapi.yaml"), yaml)
    }
}
