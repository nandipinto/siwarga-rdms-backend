package com.siwarga.rdms.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RdmsIntegrationTest {
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
    lateinit var rest: TestRestTemplate

    private fun login(
        username: String,
        password: String,
    ): String {
        val res =
            rest.postForEntity(
                "/api/v1/auth/login",
                mapOf("username" to username, "password" to password),
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, res.statusCode)
        return res.body!!["token"] as String
    }

    private fun headers(token: String): HttpHeaders {
        val h = HttpHeaders()
        h.setBearerAuth(token)
        h.contentType = MediaType.APPLICATION_JSON
        return h
    }

    @Test
    fun `full payment lifecycle and role enforcement`() {
        val adminToken = login("admin", "admin123")

        // Create RT
        val rtRes =
            rest.exchange(
                "/api/v1/rts",
                HttpMethod.POST,
                HttpEntity(mapOf("rtCode" to "RT 01", "description" to "Test RT"), headers(adminToken)),
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, rtRes.statusCode)
        val rtId = rtRes.body!!["id"] as String

        // Create House active 2026-02 (current rate)
        val houseRes =
            rest.exchange(
                "/api/v1/houses",
                HttpMethod.POST,
                HttpEntity(
                    mapOf(
                        "rtId" to rtId,
                        "blockCode" to "E",
                        "houseNumber" to "20",
                        "ownerName" to "Budi",
                        "email" to "budi@example.com",
                        "phone" to "0811",
                        "activeDate" to "2026-02-01",
                    ),
                    headers(adminToken),
                ),
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, houseRes.statusCode)
        val houseId = houseRes.body!!["id"] as String

        // Today is 2026-06, house active 2026-02 -> Feb..Jun = 5 months due at the current rate.
        // Pay 5 * 120k = 600k to fully clear arrears through the current month.
        val pay1 =
            rest.exchange(
                "/api/v1/payments",
                HttpMethod.POST,
                HttpEntity(
                    mapOf("houseId" to houseId, "paymentDate" to "2026-02-01", "grossAmount" to 600000),
                    headers(adminToken),
                ),
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, pay1.statusCode)

        // Arrears report should show zero arrears now.
        val arrears =
            rest.exchange(
                "/api/v1/reports/arrears/$houseId",
                HttpMethod.GET,
                HttpEntity<Void>(headers(adminToken)),
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, arrears.statusCode)
        assertEquals(0, (arrears.body!!["totalArrearsIdr"] as Number).toInt())

        // Reject below-minimum payment.
        val bad =
            rest.exchange(
                "/api/v1/payments",
                HttpMethod.POST,
                HttpEntity(
                    mapOf("houseId" to houseId, "paymentDate" to "2026-03-01", "grossAmount" to 50000),
                    headers(adminToken),
                ),
                Map::class.java,
            )
        assertEquals(HttpStatus.BAD_REQUEST, bad.statusCode)

        // Create a SUPERVISOR and verify it cannot access RT management.
        val userRes =
            rest.exchange(
                "/api/v1/users",
                HttpMethod.POST,
                HttpEntity(
                    mapOf("username" to "spv", "password" to "spvpass1", "role" to "SUPERVISOR"),
                    headers(adminToken),
                ),
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, userRes.statusCode)

        val spvToken = login("spv", "spvpass1")
        val forbidden =
            rest.exchange(
                "/api/v1/rts",
                HttpMethod.GET,
                HttpEntity<Void>(headers(spvToken)),
                String::class.java,
            )
        assertEquals(HttpStatus.FORBIDDEN, forbidden.statusCode)

        // Supervisor CAN access payments.
        val spvPayments =
            rest.exchange(
                "/api/v1/payments?houseId=$houseId",
                HttpMethod.GET,
                HttpEntity<Void>(headers(spvToken)),
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, spvPayments.statusCode)
        assertNotNull(spvPayments.body)
    }

    @Test
    fun `house csv import upserts and reports row errors`() {
        val adminToken = login("admin", "admin123")
        rest.exchange(
            "/api/v1/rts",
            HttpMethod.POST,
            HttpEntity(mapOf("rtCode" to "RT 02"), headers(adminToken)),
            Map::class.java,
        )

        val csv =
            """
            rt_code;block_code;house_number;owner_name;email;phone;active_date
            RT 02;A;1;Siti;siti@example.com;0812;2024-01-01
            RT 99;B;2;Ghost;ghost@example.com;0813;2024-01-01
            """.trimIndent()

        val mpHeaders = HttpHeaders()
        mpHeaders.setBearerAuth(adminToken)
        mpHeaders.contentType = MediaType.MULTIPART_FORM_DATA
        val body = org.springframework.util.LinkedMultiValueMap<String, Any>()
        body.add(
            "file",
            object : org.springframework.core.io.ByteArrayResource(csv.toByteArray()) {
                override fun getFilename() = "houses.csv"
            },
        )

        val res =
            rest.exchange(
                "/api/v1/houses/import",
                HttpMethod.POST,
                HttpEntity(body, mpHeaders),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, res.statusCode)
        assertEquals(2, (res.body!!["totalRows"] as Number).toInt())
        assertEquals(1, (res.body!!["successCount"] as Number).toInt())
        assertEquals(1, (res.body!!["errorCount"] as Number).toInt())
        assertTrue((res.body!!["errors"] as List<*>).isNotEmpty())
    }

    @Test
    fun `ownership change preserves the ledger and houses cannot be deleted`() {
        val adminToken = login("admin", "admin123")
        val rtId =
            (
                rest
                    .exchange(
                        "/api/v1/rts",
                        HttpMethod.POST,
                        HttpEntity(mapOf("rtCode" to "RT 03"), headers(adminToken)),
                        Map::class.java,
                    ).body!!["id"]
            ) as String
        val houseId =
            (
                rest
                    .exchange(
                        "/api/v1/houses",
                        HttpMethod.POST,
                        HttpEntity(
                            mapOf(
                                "rtId" to rtId,
                                "blockCode" to "C",
                                "houseNumber" to "9",
                                "ownerName" to "Old Owner",
                                "email" to "old@x.com",
                                "phone" to "01",
                                "activeDate" to "2026-01-01",
                            ),
                            headers(adminToken),
                        ),
                        Map::class.java,
                    ).body!!["id"]
            ) as String

        // Two months unpaid (Jan, Feb 2026) before any payment.
        rest.exchange(
            "/api/v1/payments",
            HttpMethod.POST,
            HttpEntity(mapOf("houseId" to houseId, "paymentDate" to "2026-01-01", "grossAmount" to 100000), headers(adminToken)),
            Map::class.java,
        )

        val before =
            rest
                .exchange(
                    "/api/v1/reports/arrears/$houseId",
                    HttpMethod.GET,
                    HttpEntity<Void>(headers(adminToken)),
                    Map::class.java,
                ).body!!["totalArrearsIdr"] as Number

        // Change owner via PUT — ledger must be untouched.
        val put =
            rest.exchange(
                "/api/v1/houses/$houseId",
                HttpMethod.PUT,
                HttpEntity(
                    mapOf(
                        "rtId" to rtId,
                        "blockCode" to "C",
                        "houseNumber" to "9",
                        "ownerName" to "New Owner",
                        "email" to "new@x.com",
                        "phone" to "02",
                        "activeDate" to "2026-01-01",
                    ),
                    headers(adminToken),
                ),
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, put.statusCode)
        assertEquals("New Owner", put.body!!["ownerName"])

        val after =
            rest
                .exchange(
                    "/api/v1/reports/arrears/$houseId",
                    HttpMethod.GET,
                    HttpEntity<Void>(headers(adminToken)),
                    Map::class.java,
                ).body!!["totalArrearsIdr"] as Number
        assertEquals(before.toLong(), after.toLong())

        // DELETE /houses/{id} no longer exists.
        val del =
            rest.exchange(
                "/api/v1/houses/$houseId",
                HttpMethod.DELETE,
                HttpEntity<Void>(headers(adminToken)),
                String::class.java,
            )
        assertTrue(del.statusCode.is4xxClientError)
    }
}
