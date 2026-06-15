package com.siwarga.rdms.repository

import com.siwarga.rdms.domain.AppUser
import com.siwarga.rdms.domain.House
import com.siwarga.rdms.domain.Payment
import com.siwarga.rdms.domain.PaymentAllocation
import com.siwarga.rdms.domain.RefundStatus
import com.siwarga.rdms.domain.RentalGuaranteePayment
import com.siwarga.rdms.domain.RentalGuaranteeRefund
import com.siwarga.rdms.domain.Rt
import com.siwarga.rdms.domain.Rw
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface RwRepository : JpaRepository<Rw, UUID> {
    fun findByRwCode(rwCode: String): Rw?

    fun existsByRwCode(rwCode: String): Boolean
}

interface RtRepository : JpaRepository<Rt, UUID> {
    fun findByRtCode(rtCode: String): Rt?

    fun existsByRtCode(rtCode: String): Boolean

    fun existsByRwId(rwId: UUID): Boolean

    fun findAllByRwIdOrderByRtCodeAsc(rwId: UUID): List<Rt>
}

interface HouseRepository : JpaRepository<House, UUID> {
    @Query(
        "SELECT h FROM House h WHERE h.rt.id = :rtId ORDER BY h.rt.rtCode ASC, h.blockCode ASC, h.houseNumber ASC",
    )
    fun findAllByRtId(
        @Param("rtId") rtId: UUID,
    ): List<House>

    fun findAllByOrderByRtRtCodeAsc(): List<House>

    fun findByRtRtCodeAndBlockCodeAndHouseNumber(
        rtCode: String,
        blockCode: String,
        houseNumber: String,
    ): House?

    fun findByBlockCodeAndHouseNumber(
        blockCode: String,
        houseNumber: String,
    ): List<House>

    @Query("SELECT COUNT(h) > 0 FROM House h WHERE h.rt.id = :rtId")
    fun existsByRtId(
        @Param("rtId") rtId: UUID,
    ): Boolean

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

    @Query("SELECT COALESCE(SUM(p.grossAmount), 0) FROM Payment p")
    fun sumGrossAmount(): Long

    @Query(
        """
        SELECT p FROM Payment p
        JOIN FETCH p.house h
        JOIN FETCH h.rt
        ORDER BY p.createdAt DESC, p.id DESC
        """,
    )
    fun findRecent(pageable: org.springframework.data.domain.Pageable): List<Payment>
}

interface PaymentAllocationRepository : JpaRepository<PaymentAllocation, UUID> {
    fun findByPaymentId(paymentId: UUID): List<PaymentAllocation>

    fun deleteByPaymentId(paymentId: UUID)
}

interface RentalGuaranteePaymentRepository : JpaRepository<RentalGuaranteePayment, UUID> {
    fun findByObligationId(obligationId: UUID): RentalGuaranteePayment?

    fun existsByObligationId(obligationId: UUID): Boolean

    @Query(
        """
        SELECT p FROM RentalGuaranteePayment p
        WHERE (:houseId IS NULL OR p.house.id = :houseId)
          AND (:rtId IS NULL OR p.house.rt.id = :rtId)
          AND (:from IS NULL OR p.paymentDate >= :from)
          AND (:to IS NULL OR p.paymentDate <= :to)
        ORDER BY p.paymentDate DESC, p.createdAt DESC
        """,
    )
    fun search(
        @Param("houseId") houseId: UUID?,
        @Param("rtId") rtId: UUID?,
        @Param("from") from: LocalDate?,
        @Param("to") to: LocalDate?,
    ): List<RentalGuaranteePayment>
}

interface RentalGuaranteeRefundRepository : JpaRepository<RentalGuaranteeRefund, UUID> {
    fun findByPaymentId(paymentId: UUID): RentalGuaranteeRefund?

    fun existsByPaymentId(paymentId: UUID): Boolean

    @Query(
        """
        SELECT r FROM RentalGuaranteeRefund r
        WHERE (:houseId IS NULL OR r.house.id = :houseId)
          AND (:rtId IS NULL OR r.house.rt.id = :rtId)
          AND (:status IS NULL OR r.status = :status)
          AND (:filterFrom = FALSE OR r.createdAt >= :fromInstant)
          AND (:filterTo = FALSE OR r.createdAt <= :toInstant)
        ORDER BY r.createdAt DESC
        """,
    )
    fun search(
        @Param("houseId") houseId: UUID?,
        @Param("rtId") rtId: UUID?,
        @Param("status") status: RefundStatus?,
        @Param("filterFrom") filterFrom: Boolean,
        @Param("fromInstant") fromInstant: java.time.Instant,
        @Param("filterTo") filterTo: Boolean,
        @Param("toInstant") toInstant: java.time.Instant,
    ): List<RentalGuaranteeRefund>

    fun countByStatus(status: RefundStatus): Long
}

