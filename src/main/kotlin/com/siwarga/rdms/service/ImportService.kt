package com.siwarga.rdms.service

import com.opencsv.CSVParserBuilder
import com.opencsv.CSVReaderBuilder
import com.siwarga.rdms.domain.House
import com.siwarga.rdms.domain.OccupancyStatus
import com.siwarga.rdms.repository.HouseRepository
import com.siwarga.rdms.service.rental.HouseOccupancyInput
import org.springframework.stereotype.Service
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.UUID

data class ImportResult(
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
    ): ImportResult {
        val rows = parse(input, expectedCols = 14, headerFirstField = "rw_code")
        return processRows(rows) { cols ->
            houseService.upsertFromImport(
                HouseImportInput(
                    rtCode = cols[1].trim(),
                    rwCode = cols[0].trim(),
                    blockCode = cols[2].trim(),
                    houseNumber = cols[3].trim(),
                    ownerName = cols[4].trim(),
                    email = cols[5].trim(),
                    phone = cols[6].trim(),
                    activeDate = cols.optional(7)?.let { LocalDate.parse(it) } ?: LocalDate.of(2024, 1, 1),
                    occupancyInput =
                        HouseOccupancyInput(
                            status = cols.optional(8)?.let { OccupancyStatus.valueOf(it.uppercase()) } ?: OccupancyStatus.OWNED,
                            tenantName = cols.optional(9),
                            tenantEmail = cols.optional(10),
                            tenantPhone = cols.optional(11),
                            leaseDurationMonths = cols.optional(12)?.toShort(),
                            rentalGuaranteeAmountIdr = cols.optional(13)?.toLong(),
                        ),
                    actorUsername = actorUsername,
                ),
            )
        }
    }

    fun importPayments(
        input: InputStream,
        username: String,
    ): ImportResult {
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
                val house = resolveHouse(cols[0].trim(), cols[1].trim())
                val draft = PaymentDraft(LocalDate.parse(cols[2].trim()), cols[3].trim().toLong(), cols.optional(4))
                resolved += ResolvedPaymentRow(parsed.line, house.id, draft)
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
        return ImportResult(rows.size, ok, errors.size, errors.values.toList())
    }

    fun importRentalGuaranteePayments(
        input: InputStream,
        username: String,
    ): ImportResult {
        val rows = parse(input, expectedCols = 5, headerFirstField = "block_code")
        return processRows(rows) { cols ->
            val house = resolveHouse(cols[0].trim(), cols[1].trim())
            guaranteePaymentService.create(
                house.id,
                LocalDate.parse(cols[2].trim()),
                cols[3].trim().toLong(),
                cols.optional(4),
                username,
            )
        }
    }

    /**
     * Runs [handle] over each data row, collecting per-row failures (column-count and thrown
     * exceptions) as ordered error strings rather than aborting. Used by importers that process
     * one row at a time; [importPayments] batches per house and tracks errors itself.
     */
    private inline fun processRows(
        rows: List<ParsedCsvRow>,
        handle: (cols: Array<String>) -> Unit,
    ): ImportResult {
        var ok = 0
        val errors = mutableListOf<String>()
        rows.forEach { parsed ->
            if (parsed.columnCountError != null) {
                errors += parsed.columnCountError
                return@forEach
            }
            try {
                handle(parsed.cols)
                ok++
            } catch (e: Exception) {
                errors += "Row ${parsed.line}: ${e.message}"
            }
        }
        return ImportResult(rows.size, ok, errors.size, errors)
    }

    /** Resolves the single house for a block/number, rejecting missing or cross-RT ambiguous matches. */
    private fun resolveHouse(
        blockCode: String,
        houseNumber: String,
    ): House {
        val matches = houseRepository.findByBlockCodeAndHouseNumber(blockCode, houseNumber)
        return when {
            matches.isEmpty() -> throw IllegalArgumentException("No house for block $blockCode no $houseNumber")
            matches.size > 1 -> throw IllegalArgumentException("Ambiguous house $blockCode/$houseNumber across RTs")
            else -> matches.first()
        }
    }

    /** Trimmed value at [index], or null when the column is absent or blank. */
    private fun Array<String>.optional(index: Int): String? = getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }

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
