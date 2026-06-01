package com.siwarga.rdms.service

import com.opencsv.CSVParserBuilder
import com.opencsv.CSVReaderBuilder
import com.siwarga.rdms.domain.House
import com.siwarga.rdms.repository.HouseRepository
import com.siwarga.rdms.repository.RtRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.LocalDate

data class ImportOutcome(
    val totalRows: Int,
    val successCount: Int,
    val errorCount: Int,
    val errors: List<String>,
)

/**
 * CSV / TXT bulk import (spec §9). Comma or semicolon delimited, optional header (auto-detected),
 * UTF-8. Row-level errors are collected and skipped; the import does not abort.
 */
@Service
class ImportService(
    private val rtRepository: RtRepository,
    private val houseRepository: HouseRepository,
    private val paymentService: PaymentService,
) {
    @Transactional
    fun importHouses(input: InputStream): ImportOutcome {
        val rows = parse(input, expectedCols = 7, headerFirstField = "rt_code")
        var ok = 0
        val errors = mutableListOf<String>()
        rows.forEachIndexed { idx, cols ->
            val line = idx + 1
            try {
                val rtCode = cols[0].trim()
                val blockCode = cols[1].trim()
                val houseNumber = cols[2].trim()
                val ownerName = cols[3].trim()
                val email = cols[4].trim()
                val phone = cols[5].trim()
                val activeDate =
                    cols
                        .getOrNull(6)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { LocalDate.parse(it) }
                        ?: LocalDate.of(2024, 1, 1)

                val rt =
                    rtRepository.findByRtCode(rtCode)
                        ?: throw IllegalArgumentException("Unknown RT code '$rtCode'")

                val existing =
                    houseRepository
                        .findByRtRtCodeAndBlockCodeAndHouseNumber(rtCode, blockCode, houseNumber)
                if (existing != null) {
                    existing.ownerName = ownerName
                    existing.email = email
                    existing.phone = phone
                    existing.activeDate = activeDate
                    houseRepository.save(existing)
                } else {
                    houseRepository.save(
                        House(
                            rt = rt,
                            blockCode = blockCode,
                            houseNumber = houseNumber,
                            ownerName = ownerName,
                            email = email,
                            phone = phone,
                            activeDate = activeDate,
                        ),
                    )
                }
                ok++
            } catch (e: Exception) {
                errors += "Row $line: ${e.message}"
            }
        }
        return ImportOutcome(rows.size, ok, errors.size, errors)
    }

    fun importPayments(
        input: InputStream,
        username: String,
    ): ImportOutcome {
        val rows = parse(input, expectedCols = 5, headerFirstField = "block_code")
        var ok = 0
        val errors = mutableListOf<String>()
        rows.forEachIndexed { idx, cols ->
            val line = idx + 1
            try {
                val blockCode = cols[0].trim()
                val houseNumber = cols[1].trim()
                val paymentDate = LocalDate.parse(cols[2].trim())
                val grossAmount = cols[3].trim().toLong()
                val note = cols.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }

                val matches = houseRepository.findByBlockCodeAndHouseNumber(blockCode, houseNumber)
                val house =
                    when {
                        matches.isEmpty() -> throw IllegalArgumentException("No house for block $blockCode no $houseNumber")
                        matches.size > 1 -> throw IllegalArgumentException("Ambiguous house $blockCode/$houseNumber across RTs")
                        else -> matches.first()
                    }
                // Each valid row runs the full allocation engine in its own transaction.
                paymentService.create(house.id, paymentDate, grossAmount, note, username)
                ok++
            } catch (e: Exception) {
                errors += "Row $line: ${e.message}"
            }
        }
        return ImportOutcome(rows.size, ok, errors.size, errors)
    }

    /** Reads all data rows, auto-detecting delimiter and an optional header. */
    private fun parse(
        input: InputStream,
        expectedCols: Int,
        headerFirstField: String,
    ): List<Array<String>> {
        val bytes = input.readBytes()
        val text = String(bytes, StandardCharsets.UTF_8)
        val delimiter = if (text.lineSequence().firstOrNull()?.contains(';') == true) ';' else ','

        val parser = CSVParserBuilder().withSeparator(delimiter).build()
        CSVReaderBuilder(InputStreamReader(bytes.inputStream(), StandardCharsets.UTF_8))
            .withCSVParser(parser)
            .build()
            .use { reader ->
                val all = reader.readAll().filter { row -> row.any { it.isNotBlank() } }
                if (all.isEmpty()) return emptyList()
                val first = all.first()
                val hasHeader = first.firstOrNull()?.trim()?.equals(headerFirstField, ignoreCase = true) == true
                return (if (hasHeader) all.drop(1) else all)
            }
    }
}
