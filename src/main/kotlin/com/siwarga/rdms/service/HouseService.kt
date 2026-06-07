package com.siwarga.rdms.service

import com.siwarga.rdms.domain.House
import com.siwarga.rdms.repository.HouseRepository
import com.siwarga.rdms.repository.RtRepository
import com.siwarga.rdms.web.HouseRequest
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
            houseRepository.findAllByRtId(rtId)
        } else {
            houseRepository.findAll()
        }

    fun get(id: UUID): House = houseRepository.findById(id).orElseThrow { NotFoundException("House $id not found") }

    @Transactional
    fun create(req: HouseRequest): House {
        val rt = rtRepository.findById(req.rtId).orElseThrow { NotFoundException("RT ${req.rtId} not found") }
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
        return houseRepository.save(house)
    }

    @Transactional
    fun update(
        id: UUID,
        req: HouseRequest,
    ): House {
        val house = get(id)
        val rt = rtRepository.findById(req.rtId).orElseThrow { NotFoundException("RT ${req.rtId} not found") }
        house.rt = rt
        house.blockCode = req.blockCode
        house.houseNumber = req.houseNumber
        house.ownerName = req.ownerName
        house.email = req.email
        house.phone = req.phone
        if (req.activeDate != null) house.activeDate = req.activeDate
        return houseRepository.save(house)
    }

    // Houses are never deleted — a residence is permanent; ownership changes go through update()
    // (grill Q10). No delete operation is exposed.
}
