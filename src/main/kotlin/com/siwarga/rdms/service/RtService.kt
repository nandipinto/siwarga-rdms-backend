package com.siwarga.rdms.service

import com.siwarga.rdms.domain.Rt
import com.siwarga.rdms.repository.HouseRepository
import com.siwarga.rdms.repository.RtRepository
import com.siwarga.rdms.repository.RwRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class RtService(
    private val rtRepository: RtRepository,
    private val rwRepository: RwRepository,
    private val houseRepository: HouseRepository,
) {
    fun list(rwId: UUID? = null): List<Rt> =
        if (rwId != null) {
            rtRepository.findAllByRwIdOrderByRtCodeAsc(rwId)
        } else {
            rtRepository.findAll()
        }

    fun get(id: UUID): Rt = rtRepository.findById(id).orElseThrow { NotFoundException("RT $id not found") }

    @Transactional
    fun create(
        rwId: UUID,
        rtCode: String,
        description: String?,
    ): Rt {
        val rw = rwRepository.findById(rwId).orElseThrow { NotFoundException("RW $rwId not found") }
        if (rtRepository.existsByRtCode(rtCode)) throw ConflictException("RT code '$rtCode' already exists")
        return rtRepository.save(Rt(rw = rw, rtCode = rtCode, description = description))
    }

    @Transactional
    fun update(
        id: UUID,
        rwId: UUID,
        rtCode: String,
        description: String?,
    ): Rt {
        val rt = get(id)
        val rw = rwRepository.findById(rwId).orElseThrow { NotFoundException("RW $rwId not found") }
        if (rt.rtCode != rtCode && rtRepository.existsByRtCode(rtCode)) {
            throw ConflictException("RT code '$rtCode' already exists")
        }
        rt.rw = rw
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
