package com.siwarga.rdms.web

import com.siwarga.rdms.domain.AppUser
import com.siwarga.rdms.domain.House
import com.siwarga.rdms.domain.Rt
import com.siwarga.rdms.service.HouseDetail
import com.siwarga.rdms.service.RentalGuaranteePaymentView
import com.siwarga.rdms.service.RentalGuaranteeRefundView
import com.siwarga.rdms.service.RentalGuaranteeSummary
import java.util.UUID

data class RtResponse(
    val id: UUID,
    val rtCode: String,
    val description: String?,
)

fun Rt.toResponse() = RtResponse(id, rtCode, description)

data class RentalGuaranteeSummaryResponse(
    val required: Boolean,
    val amountIdr: Long,
    val obligationId: UUID,
    val status: String,
    val receipt: RentalGuaranteePaymentResponse?,
)

fun RentalGuaranteeSummary.toResponse() =
    RentalGuaranteeSummaryResponse(
        required = required,
        amountIdr = amountIdr,
        obligationId = obligationId,
        status = status,
        receipt = receipt?.toResponse(),
    )

data class RentalGuaranteePaymentResponse(
    val id: UUID,
    val receiptNumber: String,
    val houseId: UUID,
    val obligationId: UUID,
    val paymentDate: String,
    val amountIdr: Long,
    val paidByName: String,
    val paidByEmail: String,
    val paidByPhone: String,
    val note: String?,
    val house: RentalGuaranteeHouseResponse,
    val createdBy: UUID,
    val createdAt: String,
    val refund: RentalGuaranteeRefundResponse?,
)

data class RentalGuaranteeHouseResponse(
    val blockCode: String,
    val houseNumber: String,
    val ownerName: String,
    val tenantName: String?,
    val status: String,
)

fun RentalGuaranteePaymentView.toResponse() =
    RentalGuaranteePaymentResponse(
        id = id,
        receiptNumber = receiptNumber,
        houseId = houseId,
        obligationId = obligationId,
        paymentDate = paymentDate,
        amountIdr = amountIdr,
        paidByName = paidByName,
        paidByEmail = paidByEmail,
        paidByPhone = paidByPhone,
        note = note,
        house =
            RentalGuaranteeHouseResponse(
                blockCode = house.blockCode,
                houseNumber = house.houseNumber,
                ownerName = house.ownerName,
                tenantName = house.tenantName,
                status = house.status,
            ),
        createdBy = createdBy,
        createdAt = createdAt,
        refund = refund?.toResponse(),
    )

data class RentalGuaranteeRefundResponse(
    val id: UUID,
    val refundNumber: String?,
    val status: String,
    val paymentId: UUID,
    val receiptNumber: String,
    val houseId: UUID,
    val obligationId: UUID,
    val amountIdr: Long,
    val refundedToName: String,
    val refundedToEmail: String,
    val refundedToPhone: String,
    val refundDate: String?,
    val note: String?,
    val createdBy: UUID,
    val completedBy: UUID?,
    val createdAt: String,
    val completedAt: String?,
)

fun RentalGuaranteeRefundView.toResponse() =
    RentalGuaranteeRefundResponse(
        id = id,
        refundNumber = refundNumber,
        status = status,
        paymentId = paymentId,
        receiptNumber = receiptNumber,
        houseId = houseId,
        obligationId = obligationId,
        amountIdr = amountIdr,
        refundedToName = refundedToName,
        refundedToEmail = refundedToEmail,
        refundedToPhone = refundedToPhone,
        refundDate = refundDate,
        note = note,
        createdBy = createdBy,
        completedBy = completedBy,
        createdAt = createdAt,
        completedAt = completedAt,
    )

data class HouseResponse(
    val id: UUID,
    val rtId: UUID,
    val rtCode: String,
    val blockCode: String,
    val houseNumber: String,
    val ownerName: String,
    val email: String,
    val phone: String,
    val activeDate: String,
    val status: String,
    val tenantName: String?,
    val tenantEmail: String?,
    val tenantPhone: String?,
    val leaseDurationMonths: Short?,
    val rentalGuaranteeAmountIdr: Long?,
    val rentalGuarantee: RentalGuaranteeSummaryResponse?,
)

fun HouseDetail.toResponse() =
    HouseResponse(
        id = house.id,
        rtId = house.rt.id,
        rtCode = house.rt.rtCode,
        blockCode = house.blockCode,
        houseNumber = house.houseNumber,
        ownerName = house.ownerName,
        email = house.email,
        phone = house.phone,
        activeDate = house.activeDate.toString(),
        status = house.status.name,
        tenantName = house.tenantName,
        tenantEmail = house.tenantEmail,
        tenantPhone = house.tenantPhone,
        leaseDurationMonths = house.leaseDurationMonths,
        rentalGuaranteeAmountIdr = house.rentalGuaranteeAmountIdr,
        rentalGuarantee = rentalGuarantee?.toResponse(),
    )

fun House.toResponse() = HouseDetail(this, null).toResponse()

data class UserResponse(
    val id: UUID,
    val username: String,
    val role: String,
    val isActive: Boolean,
)

fun AppUser.toResponse() = UserResponse(id, username, role.name, isActive)
