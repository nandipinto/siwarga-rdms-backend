package com.siwarga.rdms.web

import com.siwarga.rdms.domain.ArrearsReport
import com.siwarga.rdms.domain.MonthlyDuesReport
import com.siwarga.rdms.service.ReportService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/reports")
class ReportController(
    private val service: ReportService,
) {
    @GetMapping("/dues/monthly")
    fun monthlyDues(
        @RequestParam(required = false) rtId: UUID?,
        @RequestParam(required = false) houseId: UUID?,
    ): MonthlyDuesReport = service.monthlyDues(rtId, houseId)

    @GetMapping("/arrears/{houseId}")
    fun arrears(
        @PathVariable houseId: UUID,
    ): ArrearsReport = service.arrears(houseId)
}
