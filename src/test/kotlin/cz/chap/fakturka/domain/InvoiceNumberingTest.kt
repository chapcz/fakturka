package cz.chap.fakturka.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvoiceNumberingTest {
    private val address = Address("Ulice", "1", "Praha", "11000", "CZ")
    private val customer = Contact("c1", "Zákazník", address, null, null, null, null, ContactType.DOMESTIC)

    private fun seller(startYear: Int? = 2026, startSeq: Int? = 23, format: String = NumberFormat.DEFAULT) = SellerConfig(
        "Test Dodavatel", "Test", "Dodavatel", "", address, "12345678", "CZ12345678", "a@b.cz", null, null,
        "Banka", "1/0100", "CZ00", "KOMBCZPP", "451", "2001", "62020",
        numberingStartYear = startYear, numberingStartSequence = startSeq, numberFormat = format,
    )

    private val jan2026 = LocalDate.of(2026, 1, 1)
    private fun error(pattern: String) = NumberFormat.parse(pattern).exceptionOrNull()?.message

    private fun inv(number: String, external: Boolean = false, date: LocalDate = LocalDate.of(2026, 1, 1)) = IssuedInvoice(
        number, date, date, LocalDate.of(2026, 1, 15), customer, null,
        "0308", number, "x", emptyList(), external,
    )

    @Test fun `default pattern keeps the old numbers`() {
        val f = NumberFormat.of(NumberFormat.DEFAULT)
        assertEquals("2600023", f.format(jan2026, 23))
        assertEquals(22, f.sequenceInYear("2600022", 2026))
        assertNull(f.sequenceInYear("2600022", 2027))
        assertNull(f.sequenceInYear("FV-2026/7", 2026))
    }

    @Test fun `pattern with text, full year and month`() {
        val f = NumberFormat.of("FV-{RRRR}/{MM}-{NNN}")
        val nov = LocalDate.of(2026, 11, 9)
        assertEquals("FV-2026/11-023", f.format(nov, 23))
        assertEquals(23, f.sequenceInYear("FV-2026/11-023", 2026))
        assertEquals(1234, f.sequenceInYear("FV-2026/03-1234", 2026))
        assertNull(f.sequenceFor("FV-2026/10-023", nov))
        assertTrue(f.usesMonth)
        assertEquals("202611023", NumberFormat.variableSymbol("FV-2026/11-023"))
    }

    @Test fun `invalid patterns are refused with a reason`() {
        assertNull(error("FV-{RRRR}-{NNN}"))
        assertEquals("ui.settings.error.numberFormat.sequence", error("{RRRR}"))
        assertEquals("ui.settings.error.numberFormat.sequence", error("{RR}{NN}{NNN}"))
        assertEquals("ui.settings.error.numberFormat.year", error("FV-{NNNN}"))
        assertEquals("ui.settings.error.numberFormat.token", error("{RR}{NNN}{X}"))
        assertEquals("ui.settings.error.numberFormat.token", error("{RR}{NNN"))
        assertEquals("ui.settings.error.numberFormat.vs", error("{RRRR}{MM}{NNNNN}"))
    }

    @Test fun `sequence continues and restarts yearly with any pattern`() {
        val s = seller(startYear = null, startSeq = null, format = "FV-{RRRR}-{NNN}")
        assertEquals("FV-2026-001", InvoiceNumbering.next(jan2026, emptyList(), s))
        assertEquals("FV-2026-008", InvoiceNumbering.next(jan2026, listOf(inv("FV-2026-007")), s))
        assertEquals("FV-2027-001", InvoiceNumbering.next(LocalDate.of(2027, 1, 2), emptyList(), s))
    }

    @Test fun `month in pattern does not restart the yearly sequence`() {
        val s = seller(startYear = null, startSeq = null, format = "{RRRR}{MM}{NNN}")
        val existing = listOf(inv("202601004", date = jan2026))
        assertEquals("202602005", InvoiceNumbering.next(LocalDate.of(2026, 2, 3), existing, s))
    }

    @Test fun `numbers from before a pattern change still count`() {
        val s = seller(startYear = null, startSeq = null, format = "FV-{RRRR}-{NNN}")
        assertEquals("FV-2026-031", InvoiceNumbering.next(jan2026, listOf(inv("2600030")), s))
    }

    @Test fun `start number applies to configured year`() =
        assertEquals("2600023", InvoiceNumbering.next(jan2026, emptyList(), seller()))

    @Test fun `new year restarts at 1`() =
        assertEquals("2700001", InvoiceNumbering.next(LocalDate.of(2027, 1, 1), emptyList(), seller()))

    @Test fun `external invoices do not move sequence`() =
        assertEquals(
            "2600023",
            InvoiceNumbering.next(jan2026, listOf("2600020", "2600021", "2600022").map { inv(it, true) }, seller()),
        )

    @Test fun `skips number taken by external invoice`() =
        assertEquals("2600024", InvoiceNumbering.next(jan2026, listOf(inv("2600023", true)), seller()))

    @Test fun `ignores external numbers of foreign shape`() =
        assertEquals("2600023", InvoiceNumbering.next(jan2026, listOf(inv("FV-2026/7", true), inv("2500030", true)), seller()))

    @Test fun `continues after own invoices`() =
        assertEquals("2600025", InvoiceNumbering.next(jan2026, listOf(inv("2600024")), seller()))
}
