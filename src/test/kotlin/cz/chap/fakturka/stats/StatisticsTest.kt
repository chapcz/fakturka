package cz.chap.fakturka.stats

import cz.chap.fakturka.domain.*
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatisticsTest {
    private val customer = Contact("c1", "Zákazník", Address("U", "1", "P", "11000", "CZ"), "1", "CZ1", null, null, ContactType.DOMESTIC)

    private fun inv(number: String, date: String, price: String, rate: VatRate = VatRate.STANDARD) = LocalDate.parse(date).let { d ->
        IssuedInvoice(number, d, d, d.plusDays(14), customer, null, "0308", number, "x",
            listOf(InvoiceItem("Práce", BigDecimal("2"), "h", BigDecimal(price), vatRate = rate)), false)
    }

    private val invoices = listOf(
        inv("2500001", "2025-12-01", "50.00"),
        inv("2600001", "2026-01-10", "100.00"),
        inv("2600002", "2026-01-31", "25.50", VatRate.ZERO),
        inv("2600003", "2026-04-01", "10.00"),
    )

    @Test fun `months sum the base without VAT and keep empty months`() {
        val m = Statistics.monthly(invoices.filter { it.issueDate.year == 2026 })
        assertEquals(12, m.size)
        assertEquals(PeriodTotal(1, BigDecimal("251.00"), 2), m[0])
        assertEquals(PeriodTotal(2, BigDecimal("0.00"), 0), m[1])
        assertEquals(BigDecimal("20.00"), m[3].base)
    }

    @Test fun `quarters group three months`() {
        val q = Statistics.quarterly(invoices.filter { it.issueDate.year == 2026 })
        assertEquals(listOf(BigDecimal("251.00"), BigDecimal("20.00"), BigDecimal("0.00"), BigDecimal("0.00")), q.map { it.base })
    }

    @Test fun `years span from first to last invoice`() {
        val y = Statistics.yearly(invoices + inv("2800001", "2028-03-01", "1.00"))
        assertEquals(listOf(2025, 2026, 2027, 2028), y.map { it.period })
        assertEquals(BigDecimal("100.00"), y[0].base)
        assertEquals(0, y[2].count)
        assertTrue(Statistics.yearly(emptyList()).isEmpty())
    }
}
