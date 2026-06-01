package com.siwarga.rdms.repository

import com.siwarga.rdms.domain.AppUser
import com.siwarga.rdms.domain.House
import com.siwarga.rdms.domain.Payment
import com.siwarga.rdms.domain.PaymentAllocation
import com.siwarga.rdms.domain.Rt
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface RtRepository : JpaRepository<Rt, UUID> {
    fun findByRtCode(rtCode: String): Rt?

    fun existsByRtCode(rtCode: String): Boolean
}

interface HouseRepository : JpaRepository<House, UUID> {
    fun findByRtId(rtId: UUID): List<House>

    fun findByRtRtCodeAndBlockCodeAndHouseNumber(
        rtCode: String,
        blockCode: String,
        houseNumber: String,
    ): House?

    fun findByBlockCodeAndHouseNumber(
        blockCode: String,
        houseNumber: String,
    ): List<House>

    fun existsByRtId(rtId: UUID): Boolean

    /** Pessimistic write-lock used to serialize per-house recompute (spec §6.4, §8 / grill Q11). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM House h WHERE h.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: UUID,
    ): House?
}

interface AppUserRepository : JpaRepository<AppUser, UUID> {
    fun findByUsername(username: String): AppUser?

    fun existsByUsername(username: String): Boolean
}

interface PaymentRepository : JpaRepository<Payment, UUID> {
    fun findByHouseIdOrderByPaymentDateAscCreatedAtAscIdAsc(houseId: UUID): List<Payment>

    @Query(
        """
        SELECT p FROM Payment p
        WHERE (:houseId IS NULL OR p.house.id = :houseId)
          AND (:rtId IS NULL OR p.house.rt.id = :rtId)
          AND (:from IS NULL OR p.paymentDate >= :from)
          AND (:to IS NULL OR p.paymentDate <= :to)
        """,
    )
    fun search(
        @Param("houseId") houseId: UUID?,
        @Param("rtId") rtId: UUID?,
        @Param("from") from: LocalDate?,
        @Param("to") to: LocalDate?,
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<Payment>
}

interface PaymentAllocationRepository : JpaRepository<PaymentAllocation, UUID> {
    fun findByPaymentId(paymentId: UUID): List<PaymentAllocation>

    fun deleteByPaymentId(paymentId: UUID)
}
