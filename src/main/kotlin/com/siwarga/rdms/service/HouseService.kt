package com.siwarga.rdms.service

import com.siwarga.rdms.config.RentalGuaranteeProperties
import com.siwarga.rdms.domain.House
import com.siwarga.rdms.domain.OccupancyStatus
import com.siwarga.rdms.repository.AppUserRepository
import com.siwarga.rdms.repository.HouseRepository
import com.siwarga.rdms.repository.RentalGuaranteePaymentRepository
import com.siwarga.rdms.repository.RentalGuaranteeRefundRepository
import com.siwarga.rdms.repository.RtRepository
import com.siwarga.rdms.service.rental.HouseOccupancyInput
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
            houseRepository.findAll()
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
    fun upsertFromImport(
        rtCode: String,
        blockCode: String,
        houseNumber: String,
        ownerName: String,
        email: String,
        phone: String,
        activeDate: LocalDate,
        occupancyInput: HouseOccupancyInput,
        actorUsername: String,
    ): House {
        val rt =
            rtRepository.findByRtCode(rtCode)
                ?: throw IllegalArgumentException("Unknown RT code '$rtCode'")
        val existing = houseRepository.findByRtRtCodeAndBlockCodeAndHouseNumber(rtCode, blockCode, houseNumber)
        return if (existing != null) {
            val req =
                HouseRequest(
                    rtId = rt.id,
                    blockCode = blockCode,
                    houseNumber = houseNumber,
                    ownerName = ownerName,
                    email = email,
                    phone = phone,
                    activeDate = activeDate,
                    status = occupancyInput.status,
                    tenantName = occupancyInput.tenantName,
                    tenantEmail = occupancyInput.tenantEmail,
                    tenantPhone = occupancyInput.tenantPhone,
                    leaseDurationMonths = occupancyInput.leaseDurationMonths,
                    rentalGuaranteeAmountIdr = occupancyInput.rentalGuaranteeAmountIdr,
                )
            update(existing.id, req, actorUsername)
        } else {
            val req =
                HouseRequest(
                    rtId = rt.id,
                    blockCode = blockCode,
                    houseNumber = houseNumber,
                    ownerName = ownerName,
                    email = email,
                    phone = phone,
                    activeDate = activeDate,
                    status = occupancyInput.status,
                    tenantName = occupancyInput.tenantName,
                    tenantEmail = occupancyInput.tenantEmail,
                    tenantPhone = occupancyInput.tenantPhone,
                    leaseDurationMonths = occupancyInput.leaseDurationMonths,
                    rentalGuaranteeAmountIdr = occupancyInput.rentalGuaranteeAmountIdr,
                )
            create(req)
        }
    }

    private fun resolveOccupancy(
        old: com.siwarga.rdms.service.rental.HouseOccupancyState?,
        input: HouseOccupancyInput,
    ): com.siwarga.rdms.service.rental.HouseOccupancyState {
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
        state: com.siwarga.rdms.service.rental.HouseOccupancyState,
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

fun HouseRequest.toOccupancyInput(existing: House?) =
    HouseOccupancyInput(
        status = status ?: existing?.status ?: OccupancyStatus.OWNED,
        tenantName = tenantName ?: existing?.tenantName,
        tenantEmail = tenantEmail ?: existing?.tenantEmail,
        tenantPhone = tenantPhone ?: existing?.tenantPhone,
        leaseDurationMonths = leaseDurationMonths ?: existing?.leaseDurationMonths,
        rentalGuaranteeAmountIdr = rentalGuaranteeAmountIdr ?: existing?.rentalGuaranteeAmountIdr,
    )
