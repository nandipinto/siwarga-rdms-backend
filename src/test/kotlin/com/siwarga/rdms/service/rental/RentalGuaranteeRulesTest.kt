package com.siwarga.rdms.service.rental

import com.siwarga.rdms.config.RentalGuaranteeProperties
import com.siwarga.rdms.domain.OccupancyStatus
import com.siwarga.rdms.service.BadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RentalGuaranteeRulesTest {
    private val config = RentalGuaranteeProperties(minDurationMonths = 6, defaultAmountIdr = 300_000)

    @Test
    fun `normalize OWNED clears all tenant and guarantee fields`() {
        val state = RentalGuaranteeRules.normalize(HouseOccupancyInput(status = OccupancyStatus.OWNED), config)
        assertEquals(OccupancyStatus.OWNED, state.status)
        assertNull(state.tenantName)
        assertNull(state.rentalGuaranteeObligationId)
    }

    @Test
    fun `normalize RENTED with 12 month lease applies default guarantee`() {
        val state =
            RentalGuaranteeRules.normalize(
                HouseOccupancyInput(
                    status = OccupancyStatus.RENTED,
                    tenantName = "Andi",
                    tenantEmail = "andi@test.com",
                    tenantPhone = "0811",
                    leaseDurationMonths = 12,
                ),
                config,
            )
        assertEquals(300_000L, state.rentalGuaranteeAmountIdr)
        assertNotNull(state.rentalGuaranteeObligationId)
    }

    @Test
    fun `normalize RENTED short lease has no guarantee`() {
        val state =
            RentalGuaranteeRules.normalize(
                HouseOccupancyInput(
                    status = OccupancyStatus.RENTED,
                    tenantName = "Andi",
                    tenantEmail = "andi@test.com",
                    tenantPhone = "0811",
                    leaseDurationMonths = 3,
                ),
                config,
            )
        assertNull(state.rentalGuaranteeAmountIdr)
        assertNull(state.rentalGuaranteeObligationId)
    }

    @Test
    fun `normalize RENTED without tenant throws`() {
        assertThrows(BadRequestException::class.java) {
            RentalGuaranteeRules.normalize(
                HouseOccupancyInput(status = OccupancyStatus.RENTED, leaseDurationMonths = 12),
                config,
            )
        }
    }

    @Test
    fun `isLeaseTermination on RENTED to OWNED`() {
        val old =
            HouseOccupancyState(
                status = OccupancyStatus.RENTED,
                tenantName = "Andi",
                tenantEmail = "a@b.com",
                tenantPhone = "0811",
                leaseDurationMonths = 12,
                rentalGuaranteeAmountIdr = 300_000,
                rentalGuaranteeObligationId = java.util.UUID.randomUUID(),
            )
        assertTrue(
            RentalGuaranteeRules.isLeaseTermination(
                old,
                HouseOccupancyInput(status = OccupancyStatus.OWNED),
            ),
        )
    }

    @Test
    fun `isLeaseTermination on tenant change`() {
        val old =
            HouseOccupancyState(
                status = OccupancyStatus.RENTED,
                tenantName = "Andi",
                tenantEmail = "a@b.com",
                tenantPhone = "0811",
                leaseDurationMonths = 12,
                rentalGuaranteeAmountIdr = 300_000,
                rentalGuaranteeObligationId = java.util.UUID.randomUUID(),
            )
        val newInput =
            HouseOccupancyInput(
                status = OccupancyStatus.RENTED,
                tenantName = "Budi",
                tenantEmail = "b@b.com",
                tenantPhone = "0822",
                leaseDurationMonths = 12,
            )
        assertTrue(RentalGuaranteeRules.isLeaseTermination(old, newInput))
    }

    @Test
    fun `shouldRegenerateObligation on new RENTED house`() {
        val normalized =
            RentalGuaranteeRules.normalize(
                HouseOccupancyInput(
                    status = OccupancyStatus.RENTED,
                    tenantName = "Andi",
                    tenantEmail = "a@b.com",
                    tenantPhone = "0811",
                    leaseDurationMonths = 12,
                ),
                config,
            )
        assertTrue(RentalGuaranteeRules.shouldRegenerateObligation(null, normalized))
    }

    @Test
    fun `shouldRegenerateObligation false when unchanged`() {
        val input =
            HouseOccupancyInput(
                status = OccupancyStatus.RENTED,
                tenantName = "Andi",
                tenantEmail = "a@b.com",
                tenantPhone = "0811",
                leaseDurationMonths = 12,
            )
        val state = RentalGuaranteeRules.normalize(input, config)
        assertFalse(RentalGuaranteeRules.shouldRegenerateObligation(state, state))
    }
}
