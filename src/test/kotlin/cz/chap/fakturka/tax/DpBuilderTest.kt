package cz.chap.fakturka.tax

import cz.chap.fakturka.domain.*
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.*

class DpBuilderTest {
    private val address = Address("Testovací", "1", "Praha", "11000", "CZ")
    private val seller = SellerConfig(
        "Test Dodavatel", "Test", "Dodavatel", "Ing.", address, "12345678", "CZ12345678", "test@example.com", null, null,
        "Banka", "1/0100", "CZ00", "SWIFT", "461", "2509", "62020",
    )
    private val q4 = Quarter(2026, 4)
    private val filing = LocalDate.of(2026, 11, 6)

    private fun contact(type: ContactType = ContactType.DOMESTIC) =
        Contact("c", "Zákazník", address, "1", "CZ99999999", null, null, type)

    private fun invoice(number: String, base: String, type: ContactType = ContactType.DOMESTIC) = IssuedInvoice(
        number, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15), contact(type), null,
        "0308", number, "x", listOf(InvoiceItem("Práce", BigDecimal.ONE, "ks", BigDecimal(base), vatRate = VatRate.STANDARD)),
    )

    private fun received(base: String, vat: String) = ReceivedInvoice(
        "r", "Dodavatel", "CZ99999999", "D1", LocalDate.of(2026, 11, 2), q4,
        mapOf(VatRate.STANDARD to VatAmount(BigDecimal(base), BigDecimal(vat))),
    )

    private fun build(issued: List<IssuedInvoice> = emptyList(), received: List<ReceivedInvoice> = emptyList()) =
        DpBuilder.build(seller, QuarterData(q4, issued, received), filing)

    private fun EpoElement.one(name: String) = children.single { it.name == name }
    private fun EpoElement.attr(name: String) = attributes.single { it.first == name }.second

    @Test fun `reference figures from the filed Q1 2026 return`() {
        val dp = build(
            listOf(invoice("1", "47750.00")),
            listOf(received("1000.00", "210.00")),
        )
        // 47750.00 * 21 % = 10027.50 -> 10028
        assertEquals("DPHDP3", dp.name)
        assertEquals("47750", dp.one("Veta1").attr("obrat23"))
        assertEquals("10028", dp.one("Veta1").attr("dan23"))
        val v4 = dp.one("Veta4")
        assertEquals("1000", v4.attr("pln23"))
        assertEquals("210", v4.attr("odp_tuz23_nar"))
        assertEquals("210", v4.attr("odp_sum_nar"))
        assertEquals("0", v4.attr("odp_sum_kr"))
        val v6 = dp.one("Veta6")
        assertEquals("10028", v6.attr("dan_zocelk"))
        assertEquals("210", v6.attr("odp_zocelk"))
        assertEquals("9818", v6.attr("dano_da"))
        assertEquals("0", v6.attr("dano_no"))
    }

    @Test fun `excess deduction`() {
        val v6 = build(received = listOf(received("4761.90", "1000.00"))).one("Veta6")
        assertEquals("0", v6.attr("dano_da"))
        assertEquals("1000", v6.attr("dano_no"))
    }

    @Test fun `EU invoice goes to row 21 only`() {
        val dp = build(listOf(invoice("1", "72000.00", ContactType.EU)))
        assertEquals("72000", dp.one("Veta2").attr("pln_sluzby"))
        assertEquals("0", dp.one("Veta1").attr("obrat23"))
    }

    @Test fun `header`() {
        val dp = build()
        val d = dp.one("VetaD")
        assertEquals("Q", d.attr("kod_zo"))
        assertEquals("P", d.attr("typ_platce"))
        assertEquals("4", d.attr("ctvrt"))
        assertEquals("2026", d.attr("rok"))
        assertEquals("06.11.2026", d.attr("d_poddp"))
        val p = dp.one("VetaP")
        assertEquals("12345678", p.attr("dic"))
        assertEquals("1", p.attr("c_pop"))
        assertEquals("Testovací", p.attr("ulice"))
        assertEquals("Ing.", p.attr("titul"))
    }

    @Test fun `titles before and after the name are joined in titul`() {
        val p = DpBuilder.build(seller.copy(title = "Ing.", titleAfter = "Ph.D."), QuarterData(q4, emptyList(), emptyList()), filing)
            .one("VetaP")
        assertEquals("Ing. Ph.D.", p.attr("titul"))
    }

    @Test fun `house number split into c_pop and c_orient`() {
        fun vetaP(houseNumber: String) =
            DpBuilder.build(seller.copy(address = address.copy(houseNumber = houseNumber)), QuarterData(q4, emptyList(), emptyList()), filing)
                .one("VetaP")
        val slash = vetaP("742/15B")
        assertEquals("742", slash.attr("c_pop"))
        assertEquals("15B", slash.attr("c_orient"))
        assertEquals("Testovací", slash.attr("ulice"))
        // not a number: c_pop is a decimal in the schema, so the value goes to the street
        val text = vetaP("12A")
        assertTrue(text.attributes.none { it.first == "c_pop" || it.first == "c_orient" })
        assertEquals("Testovací 12A", text.attr("ulice"))
        assertTrue(vetaP("404").attributes.none { it.first == "c_orient" })
    }

    @Test fun `domestic VAT is summed exactly then rounded once`() {
        // each invoice has VAT 210.25 (base 1001.19 * 21 %); 420.50 -> 421, per-invoice rounding would give 420
        val dp = build(listOf(invoice("1", "1001.19"), invoice("2", "1001.19")))
        assertEquals("421", dp.one("Veta1").attr("dan23"))
        assertEquals("421", dp.one("Veta6").attr("dan_zocelk"))
    }

    @Test fun `received VAT is summed exactly then rounded once`() {
        // 100.25 + 100.25 = 200.50 -> 201, per-invoice rounding would give 200
        val dp = build(received = listOf(received("477.38", "100.25"), received("477.38", "100.25")))
        assertEquals("201", dp.one("Veta4").attr("odp_tuz23_nar"))
        assertEquals("201", dp.one("Veta4").attr("odp_sum_nar"))
        assertEquals("955", dp.one("Veta4").attr("pln23"))
    }

    @Test fun `row 21 equals the SH total summed per customer in whole crowns`() {
        fun eu(number: String, base: String, dic: String) =
            invoice(number, base, ContactType.EU).let { it.copy(customer = it.customer.copy(dic = dic)) }
        val issued = listOf(eu("1", "100.50", "DE123456789"), eu("2", "200.50", "FR12345678901"))
        // Per customer 100.50 -> 101 and 200.50 -> 201, so 302; rounding the plain sum (301.00) would give 301.
        val data = QuarterData(q4, issued, emptyList())
        val sh = ShBuilder.build(seller, data, filing)!!
        val shTotal = sh.children.filter { it.name == "VetaR" }.sumOf { it.attr("pln_hodnota").toInt() }
        assertEquals(302, shTotal)
        assertEquals("302", DpBuilder.build(seller, data, filing).one("Veta2").attr("pln_sluzby"))
    }
}
