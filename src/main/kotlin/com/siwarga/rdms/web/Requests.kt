package com.siwarga.rdms.web

import com.siwarga.rdms.domain.OccupancyStatus
import com.siwarga.rdms.domain.UserRole
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.time.LocalDate
import java.util.UUID

data class LoginRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
)

data class LoginResponse(
    val token: String,
    val role: String,
    val username: String,
)

data class RwRequest(
    @field:NotBlank val rwCode: String,
    val description: String? = null,
)

data class RtRequest(
    @field:NotNull val rwId: UUID,
    @field:NotBlank val rtCode: String,
    val description: String? = null,
)

data class HouseRequest(
    @field:NotNull val rtId: UUID,
    @field:NotBlank val blockCode: String,
    @field:NotBlank val houseNumber: String,
    @field:NotBlank val ownerName: String,
    @field:Email @field:NotBlank val email: String,
    @field:NotBlank val phone: String,
    val activeDate: LocalDate? = null,
    val status: OccupancyStatus? = OccupancyStatus.OWNED,
    val tenantName: String? = null,
    val tenantEmail: String? = null,
    val tenantPhone: String? = null,
    val leaseDurationMonths: Short? = null,
    val rentalGuaranteeAmountIdr: Long? = null,
)

data class RentalGuaranteePaymentRequest(
    @field:NotNull val houseId: UUID,
    @field:NotNull val paymentDate: LocalDate,
    @field:Positive val amountIdr: Long,
    val note: String? = null,
)

data class RentalGuaranteePaymentUpdateRequest(
    @field:NotNull val paymentDate: LocalDate,
    val note: String? = null,
)

data class RentalGuaranteeRefundCompleteRequest(
    @field:NotNull val refundDate: LocalDate,
    val note: String? = null,
)

data class PaymentRequest(
    @field:NotNull val houseId: UUID,
    @field:NotNull val paymentDate: LocalDate,
    @field:Positive val grossAmount: Long,
    val note: String? = null,
)

data class PaymentUpdateRequest(
    @field:NotNull val paymentDate: LocalDate,
    @field:Positive val grossAmount: Long,
    val note: String? = null,
)

data class UserCreateRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
    @field:NotNull val role: UserRole,
)

data class UserUpdateRequest(
    val role: UserRole? = null,
    val isActive: Boolean? = null,
    val password: String? = null,
)

data class ImportResult(
    val totalRows: Int,
    val successCount: Int,
    val errorCount: Int,
    val errors: List<String>,
)
