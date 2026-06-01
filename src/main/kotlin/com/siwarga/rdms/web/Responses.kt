package com.siwarga.rdms.web

import com.siwarga.rdms.domain.AppUser
import com.siwarga.rdms.domain.House
import com.siwarga.rdms.domain.Rt
import java.util.UUID

data class RtResponse(
    val id: UUID,
    val rtCode: String,
    val description: String?,
)

fun Rt.toResponse() = RtResponse(id, rtCode, description)

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
)

fun House.toResponse() =
    HouseResponse(
        id,
        rt.id,
        rt.rtCode,
        blockCode,
        houseNumber,
        ownerName,
        email,
        phone,
        activeDate.toString(),
    )

data class UserResponse(
    val id: UUID,
    val username: String,
    val role: String,
    val isActive: Boolean,
)

fun AppUser.toResponse() = UserResponse(id, username, role.name, isActive)
