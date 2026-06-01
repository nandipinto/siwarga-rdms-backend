package com.siwarga.rdms.service

import com.siwarga.rdms.domain.House
import com.siwarga.rdms.repository.HouseRepository
import com.siwarga.rdms.repository.RtRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Service
class HouseService(
    private val houseRepository: HouseRepository,
    private val rtRepository: RtRepository,
) {
    fun list(rtId: UUID?): List<House> =
        if (rtId != null) {
            houseRepository.findByRtId(rtId)
        } else {
            houseRepository.findAll()
        }

    fun get(id: UUID): House = houseRepository.findById(id).orElseThrow { NotFoundException("House $id not found") }

    @Transactional
    fun create(
        rtId: UUID,
        blockCode: String,
        houseNumber: String,
        ownerName: String,
        email: String,
        phone: String,
        activeDate: LocalDate?,
    ): House {
        val rt = rtRepository.findById(rtId).orElseThrow { NotFoundException("RT $rtId not found") }
        val house =
            House(
                rt = rt,
                blockCode = blockCode,
                houseNumber = houseNumber,
                ownerName = ownerName,
                email = email,
                phone = phone,
                activeDate = activeDate ?: LocalDate.of(2024, 1, 1),
            )
        return houseRepository.save(house)
    }

    @Transactional
    fun update(
        id: UUID,
        rtId: UUID,
        blockCode: String,
        houseNumber: String,
        ownerName: String,
        email: String,
        phone: String,
        activeDate: LocalDate?,
    ): House {
        val house = get(id)
        val rt = rtRepository.findById(rtId).orElseThrow { NotFoundException("RT $rtId not found") }
        house.rt = rt
        house.blockCode = blockCode
        house.houseNumber = houseNumber
        house.ownerName = ownerName
        house.email = email
        house.phone = phone
        if (activeDate != null) house.activeDate = activeDate
        return houseRepository.save(house)
    }

    // Houses are never deleted — a residence is permanent; ownership changes go through update()
    // (grill Q10). No delete operation is exposed.
}
