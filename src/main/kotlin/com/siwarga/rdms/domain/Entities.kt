package com.siwarga.rdms.domain

import com.siwarga.rdms.calc.AllocationType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class UserRole { ADMINISTRATOR, SUPERVISOR }

@Entity
@Table(name = "rt")
class Rt(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "rt_code", nullable = false, unique = true, length = 10)
    var rtCode: String,
    @Column(name = "description")
    var description: String? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PrePersist fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate fun onUpdate() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "house")
class House(
    @Id
    var id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "rt_id", nullable = false)
    var rt: Rt,
    @Column(name = "block_code", nullable = false, length = 10)
    var blockCode: String,
    @Column(name = "house_number", nullable = false, length = 10)
    var houseNumber: String,
    @Column(name = "owner_name", nullable = false, length = 200)
    var ownerName: String,
    @Column(name = "email", nullable = false, length = 255)
    var email: String,
    @Column(name = "phone", nullable = false, length = 30)
    var phone: String,
    @Column(name = "active_date", nullable = false)
    var activeDate: LocalDate = LocalDate.of(2024, 1, 1),
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PrePersist fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate fun onUpdate() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "app_user")
class AppUser(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "username", nullable = false, unique = true, length = 100)
    var username: String,
    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    var role: UserRole,
    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PrePersist fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate fun onUpdate() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "payment")
class Payment(
    @Id
    var id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "house_id", nullable = false)
    var house: House,
    @Column(name = "payment_date", nullable = false)
    var paymentDate: LocalDate,
    @Column(name = "gross_amount", nullable = false)
    var grossAmount: Long,
    @Column(name = "note")
    var note: String? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    var createdBy: AppUser,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PrePersist fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate fun onUpdate() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "payment_allocation")
class PaymentAllocation(
    @Id
    var id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    var payment: Payment,
    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_type", nullable = false, length = 20)
    var allocationType: AllocationType,
    @Column(name = "period_year")
    var periodYear: Short? = null,
    @Column(name = "period_month")
    var periodMonth: Short? = null,
    @Column(name = "amount", nullable = false)
    var amount: Long,
    @Column(name = "discount_applied", nullable = false)
    var discountApplied: Long = 0,
)
