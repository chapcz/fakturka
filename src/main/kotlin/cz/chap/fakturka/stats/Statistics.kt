package cz.chap.fakturka.stats

import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.domain.money
import cz.chap.fakturka.domain.totals
import java.math.BigDecimal

/** Invoiced amount without VAT in one period; [period] is the month (1–12), the quarter (1–4) or the year. */
data class PeriodTotal(val period: Int, val base: BigDecimal, val count: Int)

enum class StatsPeriod { MONTH, QUARTER, YEAR }

/**
 * Sums of issued invoices by issue date. The amount is the base without VAT: it is the income, and for EU
 * invoices (reverse charge) it is also what the customer pays.
 */
object Statistics {
    fun monthly(invoices: List<IssuedInvoice>): List<PeriodTotal> = sum(invoices, 1..12) { it.issueDate.monthValue }

    fun quarterly(invoices: List<IssuedInvoice>): List<PeriodTotal> = sum(invoices, 1..4) { (it.issueDate.monthValue + 2) / 3 }

    /** Every year from the first to the last one in [invoices], so a year without invoices shows as zero. */
    fun yearly(invoices: List<IssuedInvoice>): List<PeriodTotal> {
        val years = invoices.map { it.issueDate.year }
        if (years.isEmpty()) return emptyList()
        return sum(invoices, years.min()..years.max()) { it.issueDate.year }
    }

    private fun sum(invoices: List<IssuedInvoice>, periods: IntRange, periodOf: (IssuedInvoice) -> Int): List<PeriodTotal> {
        val grouped = invoices.groupBy(periodOf)
        return periods.map { p ->
            val list = grouped[p].orEmpty()
            PeriodTotal(p, list.fold(BigDecimal.ZERO) { acc, inv -> acc + inv.totals.base }.money(), list.size)
        }
    }
}
