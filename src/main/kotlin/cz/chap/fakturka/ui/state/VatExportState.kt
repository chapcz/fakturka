package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.Quarter
import cz.chap.fakturka.tax.ExportResult
import cz.chap.fakturka.tax.ExportService
import cz.chap.fakturka.tax.QuarterSummary
import java.time.Clock
import java.time.LocalDate

/** The quarter before the one containing [today] until the 25th day after its end, then the current one. */
fun defaultQuarter(today: LocalDate): Quarter {
    val current = Quarter.of(today)
    val previous = if (current.quarter == 1) Quarter(current.year - 1, 4) else Quarter(current.year, current.quarter - 1)
    val deadline = LocalDate.of(current.year, current.months.first, 25)
    return if (today <= deadline) previous else current
}

class VatExportState(
    private val exportService: ExportService,
    clock: Clock = Clock.systemDefaultZone(),
) {
    private val initial = defaultQuarter(LocalDate.now(clock))
    private var loadedSummary: QuarterSummary = exportService.summary(initial)

    var quarter: Quarter = initial
        set(value) {
            // Load first: when the service fails, quarter and figures both stay as they were.
            val loaded = exportService.summary(value)
            val exported = exportService.isExported(value)
            loadedSummary = loaded
            alreadyExported = exported
            field = value
        }

    val summary: QuarterSummary get() = loadedSummary

    var alreadyExported: Boolean = exportService.isExported(initial)
        private set

    /** Writes the files; [ExportResult.errors] holds the validator messages when nothing was written. */
    fun export(): ExportResult {
        try {
            return exportService.export(quarter)
        } finally {
            alreadyExported = exportService.isExported(quarter)
        }
    }
}
