package com.siwarga.rdms.web

import com.siwarga.rdms.domain.OccupancyStatus
import com.siwarga.rdms.domain.UserRole
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.time.LocalDate
import java.util.UUID

@Schema(description = "Credentials for JWT login")
data class LoginRequest(
    @field:Schema(example = "admin")
    @field:NotBlank val username: String,
    @field:Schema(example = "admin123")
    @field:NotBlank val password: String,
)

@Schema(description = "JWT and user identity returned after successful login")
data class LoginResponse(
    @field:Schema(description = "Bearer token for Authorization header")
    val token: String,
    @field:Schema(example = "ADMINISTRATOR")
    val role: String,
    val username: String,
    @field:Schema(description = "RT the supervisor is confined to; null for administrators")
    val rtId: UUID? = null,
    @field:Schema(description = "RT code for display; null for administrators", example = "RT 05")
    val rtCode: String? = null,
)

@Schema(description = "Create or update an RW")
data class RwRequest(
    @field:Schema(example = "01")
    @field:NotBlank val rwCode: String,
    val description: String? = null,
)

@Schema(description = "Create or update an RT under an RW")
data class RtRequest(
    @field:NotNull val rwId: UUID,
    @field:Schema(example = "05")
    @field:NotBlank val rtCode: String,
    val description: String? = null,
)

@Schema(description = "Create or update a house")
data class HouseRequest(
    @field:NotNull val rtId: UUID,
    @field:Schema(example = "A")
    @field:NotBlank val blockCode: String,
    @field:Schema(example = "12")
    @field:NotBlank val houseNumber: String,
    @field:NotBlank val ownerName: String,
    @field:Email val email: String? = null,
    @field:NotBlank val phone: String,
    val activeDate: LocalDate? = null,
    val status: OccupancyStatus? = OccupancyStatus.OWNED,
    val tenantName: String? = null,
    val tenantEmail: String? = null,
    val tenantPhone: String? = null,
    val leaseDurationMonths: Short? = null,
    @field:Schema(description = "Override default rental guarantee amount in IDR")
    val rentalGuaranteeAmountIdr: Long? = null,
)

@Schema(description = "Record a rental guarantee receipt")
data class RentalGuaranteePaymentRequest(
    @field:NotNull val houseId: UUID,
    @field:NotNull val paymentDate: LocalDate,
    @field:Schema(description = "Amount in Indonesian Rupiah (IDR)")
    @field:Positive val amountIdr: Long,
    val note: String? = null,
)

@Schema(description = "Update a rental guarantee receipt (amount is immutable)")
data class RentalGuaranteePaymentUpdateRequest(
    @field:NotNull val paymentDate: LocalDate,
    val note: String? = null,
)

@Schema(description = "Mark a pending rental guarantee refund as completed")
data class RentalGuaranteeRefundCompleteRequest(
    @field:NotNull val refundDate: LocalDate,
    val note: String? = null,
)

@Schema(description = "Record a monthly dues payment")
data class PaymentRequest(
    @field:NotNull val houseId: UUID,
    @field:NotNull val paymentDate: LocalDate,
    @field:Schema(description = "Gross amount in Indonesian Rupiah (IDR)")
    @field:Positive val grossAmount: Long,
    val note: String? = null,
)

@Schema(description = "Update a monthly dues payment")
data class PaymentUpdateRequest(
    @field:NotNull val paymentDate: LocalDate,
    @field:Schema(description = "Gross amount in Indonesian Rupiah (IDR)")
    @field:Positive val grossAmount: Long,
    val note: String? = null,
)

@Schema(description = "Create an application user")
data class UserCreateRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
    @field:NotNull val role: UserRole,
    @field:Schema(description = "RT to confine a supervisor to; required for SUPERVISOR, must be null for ADMINISTRATOR")
    val rtId: UUID? = null,
)

@Schema(description = "Update an application user")
data class UserUpdateRequest(
    val role: UserRole? = null,
    val isActive: Boolean? = null,
    val password: String? = null,
    @field:Schema(description = "Reassign the supervisor's RT; ignored for administrators")
    val rtId: UUID? = null,
)

@Schema(description = "Outcome of a CSV import operation")
data class ImportResult(
    val totalRows: Int,
    val successCount: Int,
    val errorCount: Int,
    val errors: List<String>,
)
