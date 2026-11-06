package cz.chap.fakturka.tax

import cz.chap.fakturka.domain.*
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.*

class ShBuilderTest {
    private val address = Address("Testovací", "1", "Praha", "11000", "CZ")
    private val seller = SellerConfig(
        "Test Dodavatel", "Test", "Dodavatel", "", address, "12345678", "CZ12345678", "test@example.com", null, null,
        "Banka", "1/0100", "CZ00", "SWIFT", "461", "2509", "62020",
    )
    private val q4 = Quarter(2026, 4)

    private fun invoice(number: String, base: String, dic: String?, type: ContactType = ContactType.EU) = IssuedInvoice(
        number, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15),
        Contact("c", "Kunde", address, null, dic, null, null, type), null,
        "0308", number, "x", listOf(InvoiceItem("Práce", BigDecimal.ONE, "ks", BigDecimal(base), vatRate = VatRate.ZERO)),
    )

    private fun build(issued: List<IssuedInvoice>) =
        ShBuilder.build(seller, QuarterData(q4, issued, emptyList()), LocalDate.of(2026, 11, 6))

    private fun EpoElement.attr(name: String) = attributes.single { it.first == name }.second

    @Test fun `no EU invoice returns null`() {
        assertNull(build(listOf(invoice("1", "100.00", "CZ99999999", ContactType.DOMESTIC))))
    }

    @Test fun `two invoices same EU customer aggregate`() {
        val sh = build(listOf(invoice("1", "72000.00", "DE123456789"), invoice("2", "9000.40", "DE123456789")))!!
        assertEquals("DPHSHV", sh.name)
        val r = sh.children.single { it.name == "VetaR" }
        assertEquals("1", r.attr("c_rad"))
        assertEquals("DE", r.attr("k_stat"))
        assertEquals("123456789", r.attr("c_vat"))
        assertEquals("3", r.attr("k_pln_eu"))
        assertEquals("2", r.attr("pln_pocet"))
        assertEquals("81000", r.attr("pln_hodnota"))
        val d = sh.children.single { it.name == "VetaD" }
        assertEquals("SHV", d.attr("dokument"))
        assertEquals("06.11.2026", d.attr("d_poddp"))
    }

    @Test fun `VetaP without phone and email, which DPHSHV does not know`() {
        val p = build(listOf(invoice("1", "100.00", "DE123456789")))!!.children.single { it.name == "VetaP" }
        assertTrue(p.attributes.none { it.first in setOf("c_telef", "email") })
        assertEquals("12345678", p.attr("dic"))
    }

    @Test fun `EU customer without a VAT ID is rejected`() {
        for (dic in listOf(null, "", "DE")) {
            val e = assertFailsWith<IllegalArgumentException> { build(listOf(invoice("2600042", "100.00", dic))) }
            assertTrue("2600042" in e.message!!)
        }
    }
}
