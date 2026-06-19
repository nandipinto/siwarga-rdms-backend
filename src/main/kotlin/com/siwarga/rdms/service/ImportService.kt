package com.siwarga.rdms.service

import com.opencsv.CSVParserBuilder
import com.opencsv.CSVReaderBuilder
import com.siwarga.rdms.domain.OccupancyStatus
import com.siwarga.rdms.repository.HouseRepository
import com.siwarga.rdms.service.rental.HouseOccupancyInput
import org.springframework.stereotype.Service
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.UUID

data class ImportOutcome(
    val totalRows: Int,
    val successCount: Int,
    val errorCount: Int,
    val errors: List<String>,
)

/** A payment import row whose house has been resolved, awaiting per-house batch insert. */
private data class ResolvedPaymentRow(
    val line: Int,
    val houseId: UUID,
    val draft: PaymentDraft,
)

/**
 * CSV / TXT bulk import (spec §9). Comma or semicolon delimited, optional header (auto-detected),
 * UTF-8. Row-level errors are collected and skipped; the import does not abort.
 */
@Service
class ImportService(
    private val houseRepository: HouseRepository,
    private val houseService: HouseService,
    private val paymentService: PaymentService,
    private val guaranteePaymentService: RentalGuaranteePaymentService,
) {
    fun importHouses(
        input: InputStream,
        actorUsername: String,
    ): ImportOutcome {
        val rows = parse(input, expectedCols = 13, headerFirstField = "rt_code")
        var ok = 0
        val errors = mutableListOf<String>()
        rows.forEach { parsed ->
            if (parsed.columnCountError != null) {
                errors += parsed.columnCountError
                return@forEach
            }
            try {
                val cols = parsed.cols
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
                val status =
                    cols
                        .getOrNull(7)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { OccupancyStatus.valueOf(it.uppercase()) }
                        ?: OccupancyStatus.OWNED
                val tenantName = cols.getOrNull(8)?.trim()?.takeIf { it.isNotEmpty() }
                val tenantEmail = cols.getOrNull(9)?.trim()?.takeIf { it.isNotEmpty() }
                val tenantPhone = cols.getOrNull(10)?.trim()?.takeIf { it.isNotEmpty() }
                val leaseDurationMonths =
                    cols
                        .getOrNull(11)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.toShort()
                val rentalGuaranteeAmountIdr =
                    cols
                        .getOrNull(12)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.toLong()
                // Optional trailing column: disambiguate rt_code reused across RWs.
                val rwCode = cols.getOrNull(13)?.trim()?.takeIf { it.isNotEmpty() }

                houseService.upsertFromImport(
                    HouseImportInput(
                        rtCode = rtCode,
                        rwCode = rwCode,
                        blockCode = blockCode,
                        houseNumber = houseNumber,
                        ownerName = ownerName,
                        email = email,
                        phone = phone,
                        activeDate = activeDate,
                        occupancyInput =
                            HouseOccupancyInput(
                                status = status,
                                tenantName = tenantName,
                                tenantEmail = tenantEmail,
                                tenantPhone = tenantPhone,
                                leaseDurationMonths = leaseDurationMonths,
                                rentalGuaranteeAmountIdr = rentalGuaranteeAmountIdr,
                            ),
                        actorUsername = actorUsername,
                    ),
                )
                ok++
            } catch (e: Exception) {
                errors += "Row ${parsed.line}: ${e.message}"
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
        // Collected with line numbers so the final error list stays in row order despite per-house batching.
        val errors = sortedMapOf<Int, String>()

        // Phase 1: parse each row and resolve its house. Parse/lookup failures are per-row errors.
        val resolved = mutableListOf<ResolvedPaymentRow>()
        rows.forEach { parsed ->
            if (parsed.columnCountError != null) {
                errors[parsed.line] = parsed.columnCountError
                return@forEach
            }
            try {
                val cols = parsed.cols
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
                resolved += ResolvedPaymentRow(parsed.line, house.id, PaymentDraft(paymentDate, grossAmount, note))
            } catch (e: Exception) {
                errors[parsed.line] = "Row ${parsed.line}: ${e.message}"
            }
        }

        // Phase 2: one batch + single account recompute per house (avoids the O(n²) per-row recompute).
        resolved.groupBy { it.houseId }.forEach { (houseId, group) ->
            try {
                paymentService.createBatchForHouse(houseId, group.map { it.draft }, username).forEach { result ->
                    val line = group[result.index].line
                    if (result.error == null) ok++ else errors[line] = "Row $line: ${result.error}"
                }
            } catch (e: Exception) {
                // An unexpected failure rolls back this house's batch only; other houses already committed.
                group.forEach { errors[it.line] = "Row ${it.line}: ${e.message}" }
            }
        }
        return ImportOutcome(rows.size, ok, errors.size, errors.values.toList())
    }

    fun importRentalGuaranteePayments(
        input: InputStream,
        username: String,
    ): ImportOutcome {
        val rows = parse(input, expectedCols = 5, headerFirstField = "block_code")
        var ok = 0
        val errors = mutableListOf<String>()
        rows.forEach { parsed ->
            if (parsed.columnCountError != null) {
                errors += parsed.columnCountError
                return@forEach
            }
            try {
                val cols = parsed.cols
                val blockCode = cols[0].trim()
                val houseNumber = cols[1].trim()
                val paymentDate = LocalDate.parse(cols[2].trim())
                val amountIdr = cols[3].trim().toLong()
                val note = cols.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }

                val matches = houseRepository.findByBlockCodeAndHouseNumber(blockCode, houseNumber)
                val house =
                    when {
                        matches.isEmpty() -> throw IllegalArgumentException("No house for block $blockCode no $houseNumber")
                        matches.size > 1 -> throw IllegalArgumentException("Ambiguous house $blockCode/$houseNumber across RTs")
                        else -> matches.first()
                    }
                guaranteePaymentService.create(house.id, paymentDate, amountIdr, note, username)
                ok++
            } catch (e: Exception) {
                errors += "Row ${parsed.line}: ${e.message}"
            }
        }
        return ImportOutcome(rows.size, ok, errors.size, errors)
    }

    private data class ParsedCsvRow(
        val line: Int,
        val cols: Array<String>,
        val columnCountError: String? = null,
    )

    /** Reads all data rows, auto-detecting delimiter and an optional header. */
    private fun parse(
        input: InputStream,
        expectedCols: Int,
        headerFirstField: String,
    ): List<ParsedCsvRow> {
        val bytes = input.use { it.readBytes() }
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
                val headerOffset = if (hasHeader) 1 else 0
                val dataRows = if (hasHeader) all.drop(1) else all
                return dataRows.mapIndexed { idx, row ->
                    val line = idx + 1 + headerOffset
                    if (row.size < expectedCols) {
                        ParsedCsvRow(
                            line = line,
                            cols = row,
                            columnCountError = "Row $line: expected $expectedCols columns but found ${row.size}",
                        )
                    } else {
                        ParsedCsvRow(line = line, cols = row)
                    }
                }
            }
    }
}
