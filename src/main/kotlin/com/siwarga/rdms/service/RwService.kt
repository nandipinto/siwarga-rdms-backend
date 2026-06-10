package com.siwarga.rdms.service

import com.siwarga.rdms.domain.Rw
import com.siwarga.rdms.repository.RtRepository
import com.siwarga.rdms.repository.RwRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class RwService(
    private val rwRepository: RwRepository,
    private val rtRepository: RtRepository,
) {
    fun list(): List<Rw> = rwRepository.findAll()

    fun get(id: UUID): Rw = rwRepository.findById(id).orElseThrow { NotFoundException("RW $id not found") }

    @Transactional
    fun create(
        rwCode: String,
        description: String?,
    ): Rw {
        if (rwRepository.existsByRwCode(rwCode)) throw ConflictException("RW code '$rwCode' already exists")
        return rwRepository.save(Rw(rwCode = rwCode, description = description))
    }

    @Transactional
    fun update(
        id: UUID,
        rwCode: String,
        description: String?,
    ): Rw {
        val rw = get(id)
        if (rw.rwCode != rwCode && rwRepository.existsByRwCode(rwCode)) {
            throw ConflictException("RW code '$rwCode' already exists")
        }
        rw.rwCode = rwCode
        rw.description = description
        return rwRepository.save(rw)
    }

    @Transactional
    fun delete(id: UUID) {
        val rw = get(id)
        if (rtRepository.existsByRwId(rw.id)) {
            throw ConflictException("Cannot delete RW with linked RTs")
        }
        rwRepository.delete(rw)
    }
}
