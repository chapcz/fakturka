package cz.chap.fakturka.domain

import java.math.BigDecimal
import java.math.BigDecimal.ZERO

data class VatLine(val rate: VatRate, val base: BigDecimal, val vat: BigDecimal) {
    val total: BigDecimal get() = base + vat
}

data class InvoiceTotals(
    val lineTotals: List<BigDecimal>,
    val vatLines: List<VatLine>,
    val base: BigDecimal,
    val vat: BigDecimal,
    val total: BigDecimal,
)

object InvoiceCalculator {
    fun lineTotal(item: InvoiceItem): BigDecimal = (item.quantity * item.unitPrice - item.discount).money()

    /** VAT is computed per rate from the sum of line totals at that rate, not per line. */
    fun totals(items: List<InvoiceItem>): InvoiceTotals {
        val lineTotals = items.map(::lineTotal)
        val vatLines = VatRate.entries.mapNotNull { rate ->
            val base = items.indices.filter { items[it].vatRate == rate }
                .fold(ZERO) { acc, i -> acc + lineTotals[i] }
                .takeIf { items.any { it.vatRate == rate } }
                ?: return@mapNotNull null
            val vat = (base * BigDecimal(rate.percent)).movePointLeft(2).money()
            VatLine(rate, base.money(), vat)
        }
        val base = vatLines.fold(ZERO) { acc, l -> acc + l.base }.money()
        val vat = vatLines.fold(ZERO) { acc, l -> acc + l.vat }.money()
        return InvoiceTotals(lineTotals, vatLines, base, vat, (base + vat).money())
    }
}

val IssuedInvoice.totals: InvoiceTotals get() = InvoiceCalculator.totals(items)
