package cz.chap.fakturka.domain

import java.math.BigDecimal.ZERO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.LocalDate

class InvoiceCalculatorTest {
    private fun item(qty: String, price: String, rate: VatRate, discount: String = "0") =
        InvoiceItem("x", qty.toBigDecimal(), "h", price.toBigDecimal(), discount.toBigDecimal(), rate)

    @Test
    fun referenceInvoice() {
        val item = InvoiceItem(
            "Programátorské práce", "18.5".toBigDecimal(), "h", "1000.00".toBigDecimal(),
            vatRate = VatRate.STANDARD,
        )
        val t = InvoiceCalculator.totals(listOf(item))
        assertEquals(listOf("18500.00".toBigDecimal()), t.lineTotals)
        assertEquals("18500.00".toBigDecimal(), t.base)
        assertEquals("3885.00".toBigDecimal(), t.vat)
        assertEquals("22385.00".toBigDecimal(), t.total)
    }

    @Test
    fun twoRatesOrderedStandardThenReduced() {
        val t = InvoiceCalculator.totals(listOf(item("1", "100", VatRate.REDUCED), item("1", "200", VatRate.STANDARD)))
        assertEquals(listOf(VatRate.STANDARD, VatRate.REDUCED), t.vatLines.map { it.rate })
        assertEquals("42.00".toBigDecimal(), t.vatLines[0].vat)
        assertEquals("12.00".toBigDecimal(), t.vatLines[1].vat)
        assertEquals("354.00".toBigDecimal(), t.total)
    }

    @Test
    fun vatPerRateNotPerLine() {
        // 2 x 0.10 at 21%: per line 0.02 + 0.02 = 0.04, per rate 0.20*0.21 = 0.042 -> 0.04; use values where they differ
        val t = InvoiceCalculator.totals(listOf(item("1", "0.50", VatRate.STANDARD), item("1", "0.50", VatRate.STANDARD)))
        assertEquals("0.21".toBigDecimal(), t.vat) // 1.00*0.21; per line would be 0.11+0.11
    }

    @Test
    fun zeroRate() {
        val t = InvoiceCalculator.totals(listOf(item("2", "50", VatRate.ZERO)))
        assertEquals("0.00".toBigDecimal(), t.vat)
        assertEquals(t.base, t.total)
    }

    @Test
    fun discount() {
        assertEquals("950.00".toBigDecimal(), InvoiceCalculator.lineTotal(item("10", "100", VatRate.STANDARD, "50")))
    }

    @Test
    fun emptyItems() {
        val t = InvoiceCalculator.totals(emptyList())
        assertEquals("0.00".toBigDecimal(), t.total)
        assertEquals(emptyList(), t.vatLines)
        assertEquals(ZERO.setScale(2), t.base)
    }

    @Test fun `overdue means unpaid and past the due date`() {
        val inv = IssuedInvoice(
            "2600001", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15),
            Contact("c", "C", Address("U", "1", "P", "11000", "CZ"), null, null, null, null, ContactType.DOMESTIC),
            null, "0308", "2600001", "x", emptyList(),
        )
        assertFalse(inv.isOverdue(LocalDate.of(2026, 10, 15))) // due today is not overdue yet
        assertTrue(inv.isOverdue(LocalDate.of(2026, 10, 16)))
        assertFalse(inv.copy(paidDate = LocalDate.of(2026, 10, 20)).isOverdue(LocalDate.of(2026, 11, 1)))
    }
}
