package com.siwarga.rdms.service

import com.siwarga.rdms.domain.Rt
import com.siwarga.rdms.repository.HouseRepository
import com.siwarga.rdms.repository.RtRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class RtService(
    private val rtRepository: RtRepository,
    private val houseRepository: HouseRepository,
) {
    fun list(): List<Rt> = rtRepository.findAll()

    fun get(id: UUID): Rt = rtRepository.findById(id).orElseThrow { NotFoundException("RT $id not found") }

    @Transactional
    fun create(
        rtCode: String,
        description: String?,
    ): Rt {
        if (rtRepository.existsByRtCode(rtCode)) throw ConflictException("RT code '$rtCode' already exists")
        return rtRepository.save(Rt(rtCode = rtCode, description = description))
    }

    @Transactional
    fun update(
        id: UUID,
        rtCode: String,
        description: String?,
    ): Rt {
        val rt = get(id)
        if (rt.rtCode != rtCode && rtRepository.existsByRtCode(rtCode)) {
            throw ConflictException("RT code '$rtCode' already exists")
        }
        rt.rtCode = rtCode
        rt.description = description
        return rtRepository.save(rt)
    }

    @Transactional
    fun delete(id: UUID) {
        val rt = get(id)
        if (houseRepository.existsByRtId(rt.id)) {
            throw ConflictException("Cannot delete RT with linked houses")
        }
        rtRepository.delete(rt)
    }
}
