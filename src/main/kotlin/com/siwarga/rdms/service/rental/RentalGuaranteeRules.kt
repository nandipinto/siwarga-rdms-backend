package com.siwarga.rdms.service.rental

import com.siwarga.rdms.config.RentalGuaranteeProperties
import com.siwarga.rdms.domain.OccupancyStatus
import com.siwarga.rdms.errors.BadRequestException
import java.util.UUID

/** Normalized occupancy fields ready to apply to a House entity. */
data class HouseOccupancyState(
    val status: OccupancyStatus,
    val tenantName: String?,
    val tenantEmail: String?,
    val tenantPhone: String?,
    val leaseDurationMonths: Short?,
    val rentalGuaranteeAmountIdr: Long?,
    val rentalGuaranteeObligationId: UUID?,
)

/** Input before normalization (from API request or CSV). */
data class HouseOccupancyInput(
    val status: OccupancyStatus = OccupancyStatus.OWNED,
    val tenantName: String? = null,
    val tenantEmail: String? = null,
    val tenantPhone: String? = null,
    val leaseDurationMonths: Short? = null,
    val rentalGuaranteeAmountIdr: Long? = null,
)

object RentalGuaranteeRules {
    fun guaranteeApplies(
        status: OccupancyStatus,
        leaseDurationMonths: Short?,
        config: RentalGuaranteeProperties,
    ): Boolean =
        status == OccupancyStatus.RENTED &&
            leaseDurationMonths != null &&
            leaseDurationMonths >= config.minDurationMonths

    fun normalize(
        input: HouseOccupancyInput,
        config: RentalGuaranteeProperties,
        existingObligationId: UUID? = null,
    ): HouseOccupancyState {
        if (input.status == OccupancyStatus.OWNED) {
            return HouseOccupancyState(
                status = OccupancyStatus.OWNED,
                tenantName = null,
                tenantEmail = null,
                tenantPhone = null,
                leaseDurationMonths = null,
                rentalGuaranteeAmountIdr = null,
                rentalGuaranteeObligationId = null,
            )
        }

        val lease =
            input.leaseDurationMonths
                ?: throw BadRequestException("lease_duration_months is required when status is RENTED")
        if (lease <= 0) throw BadRequestException("lease_duration_months must be positive")

        val tenantName =
            input.tenantName?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw BadRequestException("tenant_name is required when status is RENTED")
        val tenantEmail =
            input.tenantEmail?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw BadRequestException("tenant_email is required when status is RENTED")
        val tenantPhone =
            input.tenantPhone?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw BadRequestException("tenant_phone is required when status is RENTED")

        val guaranteeRequired = guaranteeApplies(OccupancyStatus.RENTED, lease, config)
        val amount =
            if (guaranteeRequired) {
                input.rentalGuaranteeAmountIdr ?: config.defaultAmountIdr
            } else {
                null
            }

        if (guaranteeRequired && (amount == null || amount <= 0)) {
            throw BadRequestException("rental_guarantee_amount_idr must be positive when guarantee applies")
        }

        val obligationId = if (guaranteeRequired) UUID.randomUUID() else null

        return HouseOccupancyState(
            status = OccupancyStatus.RENTED,
            tenantName = tenantName,
            tenantEmail = tenantEmail,
            tenantPhone = tenantPhone,
            leaseDurationMonths = lease,
            rentalGuaranteeAmountIdr = amount,
            rentalGuaranteeObligationId = obligationId,
        )
    }

    fun validate(state: HouseOccupancyState) {
        when (state.status) {
            OccupancyStatus.OWNED -> {
                if (state.tenantName != null || state.tenantEmail != null || state.tenantPhone != null ||
                    state.leaseDurationMonths != null || state.rentalGuaranteeAmountIdr != null ||
                    state.rentalGuaranteeObligationId != null
                ) {
                    throw BadRequestException("OWNED houses must not have tenant or guarantee fields")
                }
            }

            OccupancyStatus.RENTED -> {
                if (state.tenantName == null || state.tenantEmail == null || state.tenantPhone == null ||
                    state.leaseDurationMonths == null
                ) {
                    throw BadRequestException("RENTED houses require tenant and lease fields")
                }
                val hasAmount = state.rentalGuaranteeAmountIdr != null
                val hasObligation = state.rentalGuaranteeObligationId != null
                if (hasAmount != hasObligation) {
                    throw BadRequestException("rental_guarantee_amount_idr and obligation_id must both be set or both null")
                }
            }
        }
    }

    fun isLeaseTermination(
        old: HouseOccupancyState,
        newInput: HouseOccupancyInput,
    ): Boolean {
        if (old.status != OccupancyStatus.RENTED) return false
        if (newInput.status == OccupancyStatus.OWNED) return true
        if (newInput.status != OccupancyStatus.RENTED) return false
        return old.tenantName != newInput.tenantName?.trim() ||
            old.tenantEmail != newInput.tenantEmail?.trim() ||
            old.tenantPhone != newInput.tenantPhone?.trim() ||
            old.leaseDurationMonths != newInput.leaseDurationMonths ||
            old.rentalGuaranteeAmountIdr != newInput.rentalGuaranteeAmountIdr
    }

    fun shouldRegenerateObligation(
        old: HouseOccupancyState?,
        newNormalized: HouseOccupancyState,
    ): Boolean {
        if (newNormalized.rentalGuaranteeObligationId == null) return false
        if (old == null) return true
        if (old.status != OccupancyStatus.RENTED && newNormalized.status == OccupancyStatus.RENTED) return true
        if (old.status == OccupancyStatus.RENTED && newNormalized.status == OccupancyStatus.RENTED) {
            return old.tenantName != newNormalized.tenantName ||
                old.tenantEmail != newNormalized.tenantEmail ||
                old.tenantPhone != newNormalized.tenantPhone ||
                old.leaseDurationMonths != newNormalized.leaseDurationMonths ||
                old.rentalGuaranteeAmountIdr != newNormalized.rentalGuaranteeAmountIdr
        }
        return false
    }

    fun applyObligationId(
        normalized: HouseOccupancyState,
        old: HouseOccupancyState?,
        preserveObligationId: UUID?,
    ): HouseOccupancyState {
        if (normalized.rentalGuaranteeObligationId == null) return normalized
        val newId =
            when {
                shouldRegenerateObligation(old, normalized) -> normalized.rentalGuaranteeObligationId
                preserveObligationId != null -> preserveObligationId
                else -> normalized.rentalGuaranteeObligationId
            }
        return normalized.copy(rentalGuaranteeObligationId = newId)
    }

    fun toOccupancyState(house: com.siwarga.rdms.domain.House): HouseOccupancyState =
        HouseOccupancyState(
            status = house.status,
            tenantName = house.tenantName,
            tenantEmail = house.tenantEmail,
            tenantPhone = house.tenantPhone,
            leaseDurationMonths = house.leaseDurationMonths,
            rentalGuaranteeAmountIdr = house.rentalGuaranteeAmountIdr,
            rentalGuaranteeObligationId = house.rentalGuaranteeObligationId,
        )
}
