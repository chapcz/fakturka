package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.stats.PeriodTotal
import cz.chap.fakturka.stats.Statistics
import cz.chap.fakturka.stats.StatsPeriod
import cz.chap.fakturka.storage.DataRepository
import java.time.Clock
import java.time.LocalDate

/** Loads all issued invoices once; months and quarters are shown for [year], years for the whole history. */
class StatsState(repo: DataRepository, clock: Clock = Clock.systemDefaultZone()) {
    private val invoices: List<IssuedInvoice> = repo.issuedYears().flatMap { repo.issued(it) }

    /** Years that have invoices, plus the current year, ascending. */
    val years: List<Int> = (invoices.map { it.issueDate.year } + LocalDate.now(clock).year).distinct().sorted()

    var year: Int = years.last()

    fun totals(period: StatsPeriod): List<PeriodTotal> {
        val ofYear = invoices.filter { it.issueDate.year == year }
        return when (period) {
            StatsPeriod.MONTH -> Statistics.monthly(ofYear)
            StatsPeriod.QUARTER -> Statistics.quarterly(ofYear)
            StatsPeriod.YEAR -> Statistics.yearly(invoices)
        }
    }
}
