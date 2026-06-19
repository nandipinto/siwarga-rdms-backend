package com.siwarga.rdms.service

import com.siwarga.rdms.config.RentalGuaranteeProperties
import com.siwarga.rdms.domain.House
import com.siwarga.rdms.domain.OccupancyStatus
import com.siwarga.rdms.domain.Rt
import com.siwarga.rdms.errors.NotFoundException
import com.siwarga.rdms.repository.AppUserRepository
import com.siwarga.rdms.repository.HouseRepository
import com.siwarga.rdms.repository.RentalGuaranteePaymentRepository
import com.siwarga.rdms.repository.RentalGuaranteeRefundRepository
import com.siwarga.rdms.repository.RtRepository
import com.siwarga.rdms.service.rental.HouseOccupancyInput
import com.siwarga.rdms.service.rental.HouseOccupancyState
import com.siwarga.rdms.service.rental.RentalGuaranteeRules
import com.siwarga.rdms.web.HouseRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Service
class HouseService(
    private val houseRepository: HouseRepository,
    private val rtRepository: RtRepository,
    private val rentalGuaranteeProperties: RentalGuaranteeProperties,
    private val guaranteePaymentRepository: RentalGuaranteePaymentRepository,
    private val guaranteeRefundRepository: RentalGuaranteeRefundRepository,
    private val guaranteeRefundService: RentalGuaranteeRefundService,
    private val guaranteePaymentService: RentalGuaranteePaymentService,
    private val appUserRepository: AppUserRepository,
) {
    fun list(rtId: UUID?): List<House> =
        if (rtId != null) {
            houseRepository.findAllByRtId(rtId)
        } else {
            houseRepository.findAllByOrderByRtRtCodeAsc()
        }

    fun get(id: UUID): House = houseRepository.findById(id).orElseThrow { NotFoundException("House $id not found") }

    fun buildDetail(house: House): HouseDetail =
        HouseDetail(
            house = house,
            rentalGuarantee = guaranteePaymentService.buildSummary(house),
        )

    @Transactional
    fun create(req: HouseRequest): House {
        val rt = rtRepository.findById(req.rtId).orElseThrow { NotFoundException("RT ${req.rtId} not found") }
        val occupancy = resolveOccupancy(null, req.toOccupancyInput(null))
        val house =
            House(
                rt = rt,
                blockCode = req.blockCode,
                houseNumber = req.houseNumber,
                ownerName = req.ownerName,
                email = req.email,
                phone = req.phone,
                activeDate = req.activeDate ?: LocalDate.of(2024, 1, 1),
            )
        applyOccupancy(house, occupancy)
        return houseRepository.save(house)
    }

    @Transactional
    fun update(
        id: UUID,
        req: HouseRequest,
        actorUsername: String,
    ): House {
        val house =
            houseRepository.findByIdForUpdate(id)
                ?: throw NotFoundException("House $id not found")
        val oldOccupancy = RentalGuaranteeRules.toOccupancyState(house)
        val newInput = req.toOccupancyInput(house)

        if (RentalGuaranteeRules.isLeaseTermination(oldOccupancy, newInput)) {
            handleLeaseTermination(house, actorUsername)
        }

        val rt = rtRepository.findById(req.rtId).orElseThrow { NotFoundException("RT ${req.rtId} not found") }
        house.rt = rt
        house.blockCode = req.blockCode
        house.houseNumber = req.houseNumber
        house.ownerName = req.ownerName
        house.email = req.email
        house.phone = req.phone
        if (req.activeDate != null) house.activeDate = req.activeDate

        val occupancy = resolveOccupancy(oldOccupancy, newInput)
        applyOccupancy(house, occupancy)
        return houseRepository.save(house)
    }

    @Transactional
    fun upsertFromImport(input: HouseImportInput): House {
        val rt = resolveImportRt(input.rwCode, input.rtCode)
        // Key the existing-house lookup on the resolved rt.id (uq_house_rt_block_number), not rt_code.
        val existing =
            houseRepository.findByRtIdAndBlockCodeAndHouseNumber(
                rt.id,
                input.blockCode,
                input.houseNumber,
            )
        val req =
            HouseRequest(
                rtId = rt.id,
                blockCode = input.blockCode,
                houseNumber = input.houseNumber,
                ownerName = input.ownerName,
                email = input.email,
                phone = input.phone,
                activeDate = input.activeDate,
                status = input.occupancyInput.status,
                tenantName = input.occupancyInput.tenantName,
                tenantEmail = input.occupancyInput.tenantEmail,
                tenantPhone = input.occupancyInput.tenantPhone,
                leaseDurationMonths = input.occupancyInput.leaseDurationMonths,
                rentalGuaranteeAmountIdr = input.occupancyInput.rentalGuaranteeAmountIdr,
            )
        return if (existing != null) {
            update(existing.id, req, input.actorUsername)
        } else {
            create(req)
        }
    }

    /**
     * Resolves the target RT for an import row. rt_code is unique only within an RW (V7), so:
     * an explicit rw_code resolves the pair directly; otherwise a lone match is used and a code
     * shared across RWs is rejected as ambiguous (mirrors the payment-import block/number rule).
     */
    private fun resolveImportRt(
        rwCode: String?,
        rtCode: String,
    ): Rt {
        if (rwCode != null) {
            return rtRepository.findByRwRwCodeAndRtCode(rwCode, rtCode)
                ?: throw IllegalArgumentException("Unknown RT code '$rtCode' in RW '$rwCode'")
        }
        val matches = rtRepository.findAllByRtCode(rtCode)
        return when {
            matches.isEmpty() -> throw IllegalArgumentException("Unknown RT code '$rtCode'")
            matches.size > 1 ->
                throw IllegalArgumentException(
                    "Ambiguous RT code '$rtCode' across multiple RWs; add an rw_code column to disambiguate",
                )
            else -> matches.first()
        }
    }

    private fun resolveOccupancy(
        old: HouseOccupancyState?,
        input: HouseOccupancyInput,
    ): HouseOccupancyState {
        val normalized = RentalGuaranteeRules.normalize(input, rentalGuaranteeProperties)
        val withObligation =
            RentalGuaranteeRules.applyObligationId(
                normalized,
                old,
                old?.rentalGuaranteeObligationId,
            )
        RentalGuaranteeRules.validate(withObligation)
        return withObligation
    }

    private fun applyOccupancy(
        house: House,
        state: HouseOccupancyState,
    ) {
        house.status = state.status
        house.tenantName = state.tenantName
        house.tenantEmail = state.tenantEmail
        house.tenantPhone = state.tenantPhone
        house.leaseDurationMonths = state.leaseDurationMonths
        house.rentalGuaranteeAmountIdr = state.rentalGuaranteeAmountIdr
        house.rentalGuaranteeObligationId = state.rentalGuaranteeObligationId
    }

    private fun handleLeaseTermination(
        house: House,
        actorUsername: String,
    ) {
        val obligationId = house.rentalGuaranteeObligationId ?: return
        val payment = guaranteePaymentRepository.findByObligationId(obligationId) ?: return
        if (guaranteeRefundRepository.existsByPaymentId(payment.id)) return
        val actor =
            appUserRepository.findByUsername(actorUsername)
                ?: throw NotFoundException("User $actorUsername not found")
        guaranteeRefundService.createPendingForLeaseEnd(payment, actor)
    }
}

data class HouseDetail(
    val house: House,
    val rentalGuarantee: RentalGuaranteeSummary?,
)

data class HouseImportInput(
    val rtCode: String,
    // Optional: disambiguates rt_code when it is reused across RWs (null for single-RW imports).
    val rwCode: String?,
    val blockCode: String,
    val houseNumber: String,
    val ownerName: String,
    val email: String,
    val phone: String,
    val activeDate: LocalDate,
    val occupancyInput: HouseOccupancyInput,
    val actorUsername: String,
)

fun HouseRequest.toOccupancyInput(existing: House?) =
    HouseOccupancyInput(
        status = status ?: existing?.status ?: OccupancyStatus.OWNED,
        tenantName = tenantName ?: existing?.tenantName,
        tenantEmail = tenantEmail ?: existing?.tenantEmail,
        tenantPhone = tenantPhone ?: existing?.tenantPhone,
        leaseDurationMonths = leaseDurationMonths ?: existing?.leaseDurationMonths,
        rentalGuaranteeAmountIdr = rentalGuaranteeAmountIdr ?: existing?.rentalGuaranteeAmountIdr,
    )
