package com.siwarga.rdms.integration

import com.siwarga.rdms.web.HouseRequest
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
import java.time.LocalDate
import java.util.UUID

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

    private fun houseRequest(
        rtId: String,
        blockCode: String,
        houseNumber: String,
        ownerName: String,
        email: String,
        phone: String = "0811",
        activeDate: LocalDate = LocalDate.of(2026, 1, 1),
    ) = HouseRequest(
        rtId = UUID.fromString(rtId),
        blockCode = blockCode,
        houseNumber = houseNumber,
        ownerName = ownerName,
        email = email,
        phone = phone,
        activeDate = activeDate,
    )

    private fun createRw(
        token: String,
        rwCode: String,
        description: String? = null,
    ): String {
        val body = mutableMapOf<String, Any>("rwCode" to rwCode)
        if (description != null) body["description"] = description
        val res =
            rest.exchange(
                "/api/v1/rws",
                HttpMethod.POST,
                HttpEntity(body, headers(token)),
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, res.statusCode)
        return res.body!!["id"] as String
    }

    private fun createRt(
        token: String,
        rwId: String,
        rtCode: String,
        description: String? = null,
    ): String {
        val body = mutableMapOf<String, Any>("rwId" to rwId, "rtCode" to rtCode)
        if (description != null) body["description"] = description
        val res =
            rest.exchange(
                "/api/v1/rts",
                HttpMethod.POST,
                HttpEntity(body, headers(token)),
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, res.statusCode)
        return res.body!!["id"] as String
    }

    /** Create a SUPERVISOR confined to [rtId] and return its id. */
    private fun createSupervisor(
        adminToken: String,
        username: String,
        password: String,
        rtId: String,
    ): String {
        val res =
            rest.exchange(
                "/api/v1/users",
                HttpMethod.POST,
                HttpEntity(
                    mapOf("username" to username, "password" to password, "role" to "SUPERVISOR", "rtId" to rtId),
                    headers(adminToken),
                ),
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, res.statusCode)
        return res.body!!["id"] as String
    }

    @Test
    fun `full payment lifecycle and role enforcement`() {
        val adminToken = login("admin", "admin123")

        val rwId = createRw(adminToken, "RW 01")
        val rtId = createRt(adminToken, rwId, "RT 01", "Test RT")

        // Create House active 2026-02 (current rate)
        val houseRes =
            rest.exchange(
                "/api/v1/houses",
                HttpMethod.POST,
                HttpEntity(
                    houseRequest(
                        rtId,
                        "E",
                        "20",
                        "Budi",
                        "budi@example.com",
                        activeDate = LocalDate.of(2026, 2, 1),
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

        // Create a SUPERVISOR confined to this RT and verify it cannot access RT management.
        createSupervisor(adminToken, "spv", "spvpass1", rtId)

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
    fun `list houses filters by rtId`() {
        val adminToken = login("admin", "admin123")
        val rwId = createRw(adminToken, "RW A")
        val rt1 = createRt(adminToken, rwId, "RT A")
        val rt2 = createRt(adminToken, rwId, "RT B")

        fun createHouse(
            rtId: String,
            block: String,
            num: String,
        ) {
            rest.exchange(
                "/api/v1/houses",
                HttpMethod.POST,
                HttpEntity(
                    houseRequest(
                        rtId,
                        block,
                        num,
                        "Owner $block$num",
                        "$block$num@test.com",
                    ),
                    headers(adminToken),
                ),
                Map::class.java,
            )
        }

        val before =
            rest
                .exchange(
                    "/api/v1/houses?rtId=$rt1",
                    HttpMethod.GET,
                    HttpEntity<Void>(headers(adminToken)),
                    List::class.java,
                ).body!!
                .size

        createHouse(rt1, "A", "1")
        createHouse(rt1, "A", "2")
        createHouse(rt2, "B", "1")

        val filtered =
            rest.exchange(
                "/api/v1/houses?rtId=$rt1",
                HttpMethod.GET,
                HttpEntity<Void>(headers(adminToken)),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, filtered.statusCode)
        assertEquals(before + 2, filtered.body!!.size)
        filtered.body!!.forEach { house ->
            assertEquals(rt1, (house as Map<*, *>)["rtId"].toString())
        }

        val otherRt =
            rest
                .exchange(
                    "/api/v1/houses?rtId=$rt2",
                    HttpMethod.GET,
                    HttpEntity<Void>(headers(adminToken)),
                    List::class.java,
                ).body!!
        assertEquals(1, otherRt.size)
        assertEquals(rt2, (otherRt.single() as Map<*, *>)["rtId"].toString())
    }

    @Test
    fun `house csv import upserts and reports row errors`() {
        val adminToken = login("admin", "admin123")
        val rwId = createRw(adminToken, "RW 02")
        createRt(adminToken, rwId, "RT 02")

        val csv =
            """
            rt_code;block_code;house_number;owner_name;email;phone;active_date;status;tenant_name;tenant_email;tenant_phone;lease_duration_months;rental_guarantee_amount_idr
            RT 02;A;1;Siti;siti@example.com;0812;2024-01-01;;;;;;
            RT 99;B;2;Ghost;ghost@example.com;0813;2024-01-01;;;;;;
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
        val rwId = createRw(adminToken, "RW 03")
        val rtId = createRt(adminToken, rwId, "RT 03")
        val houseId =
            (
                rest
                    .exchange(
                        "/api/v1/houses",
                        HttpMethod.POST,
                        HttpEntity(
                            houseRequest(rtId, "C", "9", "Old Owner", "old@x.com", "01"),
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
                    houseRequest(rtId, "C", "9", "New Owner", "new@x.com", "02"),
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

    @Test
    fun `rental guarantee collect lease end refund and new tenant while pending`() {
        val adminToken = login("admin", "admin123")
        val rwId = createRw(adminToken, "RW RG")
        val rtId = createRt(adminToken, rwId, "RT RG")

        val rentedHouse =
            mapOf(
                "rtId" to rtId,
                "blockCode" to "R",
                "houseNumber" to "1",
                "ownerName" to "Budi Owner",
                "email" to "budi@example.com",
                "phone" to "081111",
                "activeDate" to "2024-01-01",
                "status" to "RENTED",
                "tenantName" to "Andi Tenant",
                "tenantEmail" to "andi@example.com",
                "tenantPhone" to "082222",
                "leaseDurationMonths" to 12,
                "rentalGuaranteeAmountIdr" to 300000,
            )
        val createRes =
            rest.exchange(
                "/api/v1/houses",
                HttpMethod.POST,
                HttpEntity(rentedHouse, headers(adminToken)),
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, createRes.statusCode)
        val houseId = createRes.body!!["id"] as String
        val rg = createRes.body!!["rentalGuarantee"] as Map<*, *>
        assertEquals("UNPAID", rg["status"])
        assertEquals(300000, (rg["amountIdr"] as Number).toInt())

        val payRes =
            rest.exchange(
                "/api/v1/rental-guarantee/payments",
                HttpMethod.POST,
                HttpEntity(
                    mapOf("houseId" to houseId, "paymentDate" to "2026-06-01", "amountIdr" to 300000),
                    headers(adminToken),
                ),
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, payRes.statusCode)
        assertTrue((payRes.body!!["receiptNumber"] as String).startsWith("RG-"))

        val paidHouse =
            rest
                .exchange(
                    "/api/v1/houses/$houseId",
                    HttpMethod.GET,
                    HttpEntity<Void>(headers(adminToken)),
                    Map::class.java,
                ).body!!
        assertEquals("PAID", (paidHouse["rentalGuarantee"] as Map<*, *>)["status"])

        // End lease -> auto PENDING refund
        val endLease =
            rest.exchange(
                "/api/v1/houses/$houseId",
                HttpMethod.PUT,
                HttpEntity(
                    rentedHouse + mapOf("status" to "OWNED"),
                    headers(adminToken),
                ),
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, endLease.statusCode)
        assertEquals("OWNED", endLease.body!!["status"])
        assertEquals(null, endLease.body!!["tenantName"])

        val refunds =
            rest.exchange(
                "/api/v1/rental-guarantee/refunds?houseId=$houseId&status=PENDING",
                HttpMethod.GET,
                HttpEntity<Void>(headers(adminToken)),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, refunds.statusCode)
        assertEquals(1, refunds.body!!.size)
        val refundId = (refunds.body!!.first() as Map<*, *>)["id"] as String
        assertEquals("Andi Tenant", (refunds.body!!.first() as Map<*, *>)["refundedToName"])

        // New tenant while refund still pending — must succeed
        val newTenant =
            rest.exchange(
                "/api/v1/houses/$houseId",
                HttpMethod.PUT,
                HttpEntity(
                    mapOf(
                        "rtId" to rtId,
                        "blockCode" to "R",
                        "houseNumber" to "1",
                        "ownerName" to "Budi Owner",
                        "email" to "budi@example.com",
                        "phone" to "081111",
                        "status" to "RENTED",
                        "tenantName" to "Cici Tenant",
                        "tenantEmail" to "cici@example.com",
                        "tenantPhone" to "083333",
                        "leaseDurationMonths" to 12,
                    ),
                    headers(adminToken),
                ),
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, newTenant.statusCode)
        assertEquals("Cici Tenant", newTenant.body!!["tenantName"])
        assertEquals("UNPAID", (newTenant.body!!["rentalGuarantee"] as Map<*, *>)["status"])

        // Complete original refund
        val complete =
            rest.exchange(
                "/api/v1/rental-guarantee/refunds/$refundId/complete",
                HttpMethod.POST,
                HttpEntity(mapOf("refundDate" to "2026-12-01"), headers(adminToken)),
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, complete.statusCode)
        assertEquals("COMPLETED", complete.body!!["status"])
        assertTrue((complete.body!!["refundNumber"] as String).startsWith("RF-"))

        // Supervisor (confined to this RT) can record guarantee payment for new tenant
        createSupervisor(adminToken, "spv2", "spvpass2", rtId)
        val spvToken = login("spv2", "spvpass2")
        val spvPay =
            rest.exchange(
                "/api/v1/rental-guarantee/payments",
                HttpMethod.POST,
                HttpEntity(
                    mapOf("houseId" to houseId, "paymentDate" to "2026-12-02", "amountIdr" to 300000),
                    headers(spvToken),
                ),
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, spvPay.statusCode)

        // Wrong amount rejected
        val badAmount =
            rest.exchange(
                "/api/v1/rental-guarantee/payments",
                HttpMethod.POST,
                HttpEntity(
                    mapOf("houseId" to houseId, "paymentDate" to "2026-12-03", "amountIdr" to 100000),
                    headers(adminToken),
                ),
                Map::class.java,
            )
        assertEquals(HttpStatus.BAD_REQUEST, badAmount.statusCode)
    }

    @Test
    fun `dashboard returns role-aware summary`() {
        val adminToken = login("admin", "admin123")
        val rwId = createRw(adminToken, "RW DSH")
        val rtId = createRt(adminToken, rwId, "RT DSH")

        fun createHouse(
            block: String,
            num: String,
            owner: String,
            email: String,
        ): String {
            val res =
                rest.exchange(
                    "/api/v1/houses",
                    HttpMethod.POST,
                    HttpEntity(
                        houseRequest(
                            rtId,
                            block,
                            num,
                            owner,
                            email,
                            activeDate = LocalDate.of(2026, 2, 1),
                        ),
                        headers(adminToken),
                    ),
                    Map::class.java,
                )
            assertEquals(HttpStatus.CREATED, res.statusCode)
            return res.body!!["id"] as String
        }

        fun fetchDashboard(): Map<*, *> =
            rest
                .exchange(
                    "/api/v1/dashboard",
                    HttpMethod.GET,
                    HttpEntity<Void>(headers(adminToken)),
                    Map::class.java,
                ).body!!

        val baseline = fetchDashboard()
        val baselineStats = baseline["houseStats"] as Map<*, *>
        val baselineTotalHouses = (baselineStats["total"] as Number).toInt()
        val baselinePaidUp = (baselineStats["paidUp"] as Number).toInt()
        val baselineInArrears = (baselineStats["inArrears"] as Number).toInt()
        val baselineCollected = (baseline["totalCollectedIdr"] as Number).toLong()

        val paidHouseId = createHouse("D", "1", "Paid Owner", "paid@example.com")
        val unpaidHouseId = createHouse("D", "2", "Unpaid Owner", "unpaid@example.com")

        val afterCreate = fetchDashboard()
        val afterCreateStats = afterCreate["houseStats"] as Map<*, *>
        assertEquals(baselineTotalHouses + 2, (afterCreateStats["total"] as Number).toInt())
        assertEquals(baselineInArrears + 2, (afterCreateStats["inArrears"] as Number).toInt())

        val pay =
            rest.exchange(
                "/api/v1/payments",
                HttpMethod.POST,
                HttpEntity(
                    mapOf("houseId" to paidHouseId, "paymentDate" to "2026-06-01", "grossAmount" to 600000),
                    headers(adminToken),
                ),
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, pay.statusCode)

        val adminBody = fetchDashboard()
        assertEquals("ADMINISTRATOR", adminBody["role"])
        assertEquals(baselineCollected + 600000, (adminBody["totalCollectedIdr"] as Number).toLong())

        val houseStats = adminBody["houseStats"] as Map<*, *>
        assertEquals(baselineTotalHouses + 2, (houseStats["total"] as Number).toInt())
        assertEquals(baselinePaidUp + 1, (houseStats["paidUp"] as Number).toInt())
        assertEquals(baselineInArrears + 1, (houseStats["inArrears"] as Number).toInt())

        val currentMonth = adminBody["currentMonth"] as Map<*, *>
        assertNotNull(currentMonth["percent"])

        val monthlyTrend = adminBody["monthlyTrend"] as List<*>
        assertEquals(12, monthlyTrend.size)

        val recentPayments = adminBody["recentPayments"] as List<*>
        assertTrue(recentPayments.isNotEmpty())
        val recent =
            recentPayments
                .map { it as Map<*, *> }
                .first { it["ownerName"] == "Paid Owner" }
        assertEquals(true, recent["housePaidUp"])

        val topArrears = adminBody["topArrears"] as List<*>
        assertTrue(
            topArrears.any { (it as Map<*, *>)["houseId"].toString() == unpaidHouseId },
        )
        val top = topArrears.map { it as Map<*, *> }.first { it["houseId"].toString() == unpaidHouseId }
        assertTrue((top["totalOutstandingIdr"] as Number).toLong() > 0)

        val alerts = adminBody["alerts"] as Map<*, *>
        assertNotNull(alerts["pendingRefunds"])
        assertNotNull(alerts["unpaidGuarantees"])

        createSupervisor(adminToken, "spvdsh", "spvpass9", rtId)
        val spvToken = login("spvdsh", "spvpass9")

        val spvDashboard =
            rest.exchange(
                "/api/v1/dashboard",
                HttpMethod.GET,
                HttpEntity<Void>(headers(spvToken)),
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, spvDashboard.statusCode)
        val spvBody = spvDashboard.body!!
        assertEquals("SUPERVISOR", spvBody["role"])
        assertNotNull(spvBody["alerts"])
        // Supervisor now receives the SAME full payload as the administrator, RT-scoped (§4.3, §5.5).
        assertEquals(rtId, spvBody["rtId"])
        assertEquals("RT DSH", spvBody["rtCode"])
        assertTrue(spvBody.containsKey("totalCollectedIdr"))
        assertTrue(spvBody.containsKey("topArrears"))
        assertTrue(spvBody.containsKey("monthlyTrend"))
    }

    private fun createHouse(
        token: String,
        rtId: String,
        block: String,
        number: String,
        owner: String,
    ): String {
        val res =
            rest.exchange(
                "/api/v1/houses",
                HttpMethod.POST,
                HttpEntity(houseRequest(rtId, block, number, owner, "$owner@example.com"), headers(token)),
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, res.statusCode)
        return res.body!!["id"] as String
    }

    private fun recordPayment(
        token: String,
        houseId: String,
        date: String,
        gross: Int,
    ): HttpStatus {
        val res =
            rest.exchange(
                "/api/v1/payments",
                HttpMethod.POST,
                HttpEntity(mapOf("houseId" to houseId, "paymentDate" to date, "grossAmount" to gross), headers(token)),
                String::class.java,
            )
        return res.statusCode as HttpStatus
    }

    @Test
    fun `supervisor is confined to their RT`() {
        val adminToken = login("admin", "admin123")
        val rwId = createRw(adminToken, "RW SCOPE")
        val rtA = createRt(adminToken, rwId, "RT SC-A")
        val rtB = createRt(adminToken, rwId, "RT SC-B")

        val houseA = createHouse(adminToken, rtA, "A", "1", "Andi")
        val houseB = createHouse(adminToken, rtB, "B", "1", "Bayu")

        // Admin records one payment in each RT.
        assertEquals(HttpStatus.CREATED, recordPayment(adminToken, houseA, "2026-02-01", 120000))
        assertEquals(HttpStatus.CREATED, recordPayment(adminToken, houseB, "2026-02-01", 120000))

        // Login as a supervisor confined to RT A; login response exposes the RT identity.
        createSupervisor(adminToken, "scopeA", "scopepass1", rtA)
        val loginRes =
            rest.postForEntity(
                "/api/v1/auth/login",
                mapOf("username" to "scopeA", "password" to "scopepass1"),
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, loginRes.statusCode)
        assertEquals(rtA, loginRes.body!!["rtId"])
        assertEquals("RT SC-A", loginRes.body!!["rtCode"])
        val spvA = loginRes.body!!["token"] as String

        // House list is restricted to RT A.
        val houseList =
            rest.exchange(
                "/api/v1/houses",
                HttpMethod.GET,
                HttpEntity<Void>(headers(spvA)),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, houseList.statusCode)
        assertEquals(1, houseList.body!!.size)
        assertEquals(houseA, (houseList.body!![0] as Map<*, *>)["id"])

        // Single-resource access outside RT A → 403; inside → 200.
        fun getHouse(id: String) =
            rest.exchange("/api/v1/houses/$id", HttpMethod.GET, HttpEntity<Void>(headers(spvA)), String::class.java).statusCode
        assertEquals(HttpStatus.FORBIDDEN, getHouse(houseB))
        assertEquals(HttpStatus.OK, getHouse(houseA))

        // Mutations / reports outside RT A → 403.
        assertEquals(HttpStatus.FORBIDDEN, recordPayment(spvA, houseB, "2026-03-01", 120000))
        val arrearsB =
            rest.exchange("/api/v1/reports/arrears/$houseB", HttpMethod.GET, HttpEntity<Void>(headers(spvA)), String::class.java)
        assertEquals(HttpStatus.FORBIDDEN, arrearsB.statusCode)

        // Payment list ignores a client rtId pointing at RT B — only RT A's payment is visible.
        val payList =
            rest.exchange(
                "/api/v1/payments?rtId=$rtB",
                HttpMethod.GET,
                HttpEntity<Void>(headers(spvA)),
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, payList.statusCode)
        val payContent = payList.body!!["content"] as List<*>
        assertEquals(1, payContent.size)
        assertEquals(houseA, (payContent[0] as Map<*, *>)["houseId"])

        // 1:1 — a second supervisor cannot take RT A.
        val dup =
            rest.exchange(
                "/api/v1/users",
                HttpMethod.POST,
                HttpEntity(
                    mapOf("username" to "scopeA2", "password" to "scopepass2", "role" to "SUPERVISOR", "rtId" to rtA),
                    headers(adminToken),
                ),
                String::class.java,
            )
        assertEquals(HttpStatus.CONFLICT, dup.statusCode)

        // Handoff — deactivating the current supervisor releases RT A for a new one.
        val spvAId = rest
            .exchange("/api/v1/users", HttpMethod.GET, HttpEntity<Void>(headers(adminToken)), List::class.java)
            .body!!
            .map { it as Map<*, *> }
            .first { it["username"] == "scopeA" }["id"] as String
        val deactivate =
            rest.exchange(
                "/api/v1/users/$spvAId",
                HttpMethod.PUT,
                HttpEntity(mapOf("isActive" to false), headers(adminToken)),
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, deactivate.statusCode)

        val handoff =
            rest.exchange(
                "/api/v1/users",
                HttpMethod.POST,
                HttpEntity(
                    mapOf("username" to "scopeA3", "password" to "scopepass3", "role" to "SUPERVISOR", "rtId" to rtA),
                    headers(adminToken),
                ),
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, handoff.statusCode)
        assertEquals(rtA, handoff.body!!["rtId"])
    }
}
