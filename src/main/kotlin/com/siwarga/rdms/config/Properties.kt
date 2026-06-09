package com.siwarga.rdms.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "rdms.jwt")
data class JwtProperties(
    val secret: String,
    val expiryMinutes: Long = 120,
)

@ConfigurationProperties(prefix = "rdms.admin")
data class AdminProperties(
    val username: String,
    val password: String,
)

@ConfigurationProperties(prefix = "rdms.rental-guarantee")
data class RentalGuaranteeProperties(
    val minDurationMonths: Int = 6,
    val defaultAmountIdr: Long = 300_000,
    val receiptPrefix: String = "RG",
    val refundPrefix: String = "RF",
)
