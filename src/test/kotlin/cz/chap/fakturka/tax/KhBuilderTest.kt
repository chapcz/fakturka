package cz.chap.fakturka.tax

import cz.chap.fakturka.domain.*
import cz.chap.fakturka.storage.IssuedFile
import cz.chap.fakturka.storage.XmlStore
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.*

class KhBuilderTest {
    private val address = Address("Testovací", "1", "Praha", "11000", "CZ")
    private val seller = SellerConfig(
        "Test Dodavatel", "Test", "Dodavatel", "", address, "12345678", "CZ12345678", "test@example.com", null, null,
        "Banka", "1/0100", "CZ00", "SWIFT", "461", "2509", "62020",
    )
    private val filing = LocalDate.of(2026, 10, 20)
    private val q3 = Quarter(2026, 3)

    private fun contact(dic: String?, type: ContactType = ContactType.DOMESTIC) =
        Contact("c", "Zákazník", address, "1", dic, null, null, type)

    private fun invoice(number: String, base: String, customer: Contact, rate: VatRate = VatRate.STANDARD) =
        IssuedInvoice(
            number, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15), customer, null,
            "0308", number, "x", listOf(InvoiceItem("Práce", BigDecimal.ONE, "ks", BigDecimal(base), vatRate = rate)),
        )

    private fun build(issued: List<IssuedInvoice> = emptyList(), received: List<ReceivedInvoice> = emptyList()) =
        KhBuilder.build(seller, QuarterData(q3, issued, received), filing)

    private fun EpoElement.all(name: String) = children.filter { it.name == name }
    private fun EpoElement.one(name: String) = all(name).single()
    private fun EpoElement.attr(name: String) = attributes.single { it.first == name }.second

    private fun received(doc: String, taxDate: LocalDate, base: String, vat: String, dic: String? = "CZ99999999") =
        ReceivedInvoice(
            "r-$doc", "Dodavatel", dic, doc, taxDate, q3,
            mapOf(VatRate.STANDARD to VatAmount(BigDecimal(base), BigDecimal(vat))),
        )

    @Test fun `Q3 2026 matches the ProFact export`() {
        val path = Path.of(javaClass.getResource("/fixtures/q3-2026-issued.xml")!!.toURI())
        val issued = XmlStore().read(path, IssuedFile::class)!!.invoices
        val kh = build(issued)

        assertEquals("DPHKH1", kh.name)
        val d = kh.one("VetaD")
        assertEquals("20.10.2026", d.attr("d_poddp"))
        assertEquals("3", d.attr("ctvrt"))
        assertEquals("2026", d.attr("rok"))

        val rows = kh.all("VetaA4").map {
            listOf(it.attr("c_evid_dd"), it.attr("dic_odb"), it.attr("dppd"), it.attr("zakl_dane1"), it.attr("dan1"))
        }
        assertEquals(
            listOf(
                listOf("2600013", "11223344", "3.7.2026", "12000", "2520"),
                listOf("2600015", "22334455", "14.7.2026", "9000", "1890"),
                listOf("2600016", "11223344", "4.8.2026", "10000", "2100"),
                listOf("2600018", "11223344", "2.9.2026", "9420", "1978.2"),
                listOf("2600019", "33445566", "16.9.2026", "9100", "1911"),
            ),
            rows,
        )
        val a5 = kh.one("VetaA5")
        assertEquals("4200", a5.attr("zakl_dane1"))
        assertEquals("882", a5.attr("dan1"))
        val c = kh.one("VetaC")
        assertEquals("53720", c.attr("obrat23"))
        assertEquals("0", c.attr("obrat5"))
        assertEquals("0", c.attr("pln23"))
    }

    @Test fun `seller data in VetaP`() {
        val p = build().one("VetaP")
        assertEquals("12345678", p.attr("dic"))
        assertEquals("Testovací 1", p.attr("ulice"))
        assertEquals("F", p.attr("typ_ds"))
        assertEquals("Test Dodavatel", p.attr("zkrobchjm"))
    }

    @Test fun `no attributes the DPHKH1 schema rejects`() {
        // mesic and c_pop are decimals (an empty value is invalid), id_dats is not in the schema at all
        val kh = build()
        assertTrue(kh.one("VetaD").attributes.none { it.first == "mesic" })
        assertTrue(kh.one("VetaP").attributes.none { it.first in setOf("c_pop", "id_dats") })
    }

    @Test fun `exactly 10 000 incl VAT goes to A5`() {
        val kh = build(listOf(invoice("1", "8264.46", contact("CZ1234"))))
        assertTrue(kh.all("VetaA4").isEmpty())
        assertEquals("8264.46", kh.one("VetaA5").attr("zakl_dane1"))
        assertEquals("1735.54", kh.one("VetaA5").attr("dan1"))
    }

    @Test fun `domestic customer without CZ DIC goes to A5`() {
        val kh = build(listOf(invoice("1", "50000.00", contact(null)), invoice("2", "50000.00", contact("SK123"))))
        assertTrue(kh.all("VetaA4").isEmpty())
        assertEquals("100000", kh.one("VetaA5").attr("zakl_dane1"))
    }

    @Test fun `EU invoice excluded`() {
        val kh = build(listOf(invoice("1", "50000.00", contact("DE123", ContactType.EU))))
        assertTrue(kh.all("VetaA4").isEmpty())
        assertEquals("0", kh.one("VetaA5").attr("zakl_dane1"))
        assertEquals("0", kh.one("VetaC").attr("obrat23"))
    }

    @Test fun `reduced rate maps to second columns`() {
        val kh = build(listOf(invoice("1", "20000.00", contact("CZ1234"), VatRate.REDUCED)))
        val a4 = kh.one("VetaA4")
        assertEquals("0", a4.attr("zakl_dane1"))
        assertEquals("20000", a4.attr("zakl_dane2"))
        assertEquals("2400", a4.attr("dan2"))
        assertEquals("20000", kh.one("VetaC").attr("obrat5"))
    }

    @Test fun `received over 10k goes to B2 with own DUZP`() {
        val kh = build(received = listOf(received("FV-1", LocalDate.of(2022, 12, 11), "10000.00", "2100.00")))
        val b2 = kh.one("VetaB2")
        assertEquals("11.12.2022", b2.attr("dppd"))
        assertEquals("99999999", b2.attr("dic_dod"))
        assertEquals("FV-1", b2.attr("c_evid_dd"))
        assertEquals("10000", b2.attr("zakl_dane1"))
        assertEquals("10000", kh.one("VetaC").attr("pln23"))
    }

    @Test fun `small received invoices summed in B3`() {
        val d = LocalDate.of(2026, 5, 1)
        val kh = build(
            received = listOf(
                received("1", d, "2113.86", "443.91"), received("2", d, "5776.86", "1213.14"),
                received("3", d, "743.80", "156.20"), received("4", d, "4434.00", "931.14"),
            ),
        )
        assertTrue(kh.all("VetaB2").isEmpty())
        val b3 = kh.one("VetaB3")
        assertEquals("13068.52", b3.attr("zakl_dane1"))
        assertEquals("2744.39", b3.attr("dan1"))
        assertEquals("13068.52", kh.one("VetaC").attr("pln23"))
    }

    @Test fun `B3 and A5 always present`() {
        val kh = build()
        assertEquals("0", kh.one("VetaA5").attr("dan1"))
        assertEquals("0", kh.one("VetaB3").attr("zakl_dane1"))
    }

    @Test fun `stored customer DIC with spaces or lower case still goes to A4 without prefix`() {
        for (dic in listOf("CZ 11223344", "cz11223344", " CZ 1122 3344 ")) {
            val kh = build(listOf(invoice("2600001", "20000", contact(dic))))
            assertEquals("11223344", kh.one("VetaA4").attr("dic_odb"), dic)
        }
    }
}
