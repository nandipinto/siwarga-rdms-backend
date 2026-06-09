package com.siwarga.rdms.service.rental

import com.siwarga.rdms.config.RentalGuaranteeProperties
import com.siwarga.rdms.repository.DocumentSequenceRepository
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class DocumentNumberService(
    private val sequenceRepository: DocumentSequenceRepository,
    private val config: RentalGuaranteeProperties,
) {
    fun nextReceiptNumber(referenceDate: LocalDate = LocalDate.now()): String =
        formatNumber(config.receiptPrefix, referenceDate)

    fun nextRefundNumber(referenceDate: LocalDate = LocalDate.now()): String =
        formatNumber(config.refundPrefix, referenceDate)

    private fun formatNumber(
        prefix: String,
        referenceDate: LocalDate,
    ): String {
        val year = referenceDate.year.toShort()
        val seq = sequenceRepository.nextSequence(prefix, year)
        return "$prefix-$year-${seq.toString().padStart(6, '0')}"
    }
}
