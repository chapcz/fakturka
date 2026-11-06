package cz.chap.fakturka.tax

import cz.chap.fakturka.domain.*
import cz.chap.fakturka.storage.IssuedFile
import cz.chap.fakturka.storage.XmlStore
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.*

class XsdValidatorTest {
    private val address = Address("Testovací", "1", "Praha", "11000", "CZ")
    private val seller = SellerConfig(
        "Test Dodavatel", "Test", "Dodavatel", "Ing. Ph.D.", address, "12345678", "CZ12345678", "test@example.com",
        "+420123456789", null, "Banka", "1/0100", "CZ00", "SWIFT", "461", "2509", "62020",
    )
    private val filing = LocalDate.of(2026, 10, 24)
    private val q3 = Quarter(2026, 3)

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readBytes()

    private fun q3Issued(): List<IssuedInvoice> =
        XmlStore().read(Path.of(javaClass.getResource("/fixtures/q3-2026-issued.xml")!!.toURI()), IssuedFile::class)!!.invoices

    private fun euInvoice(number: String, dic: String) = IssuedInvoice(
        number, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 17),
        Contact("eu", "Kunde GmbH", Address("Straße", "5", "Berlin", "10115", "DE"), null, dic, null, null, ContactType.EU),
        null, "0308", number, "x", listOf(InvoiceItem("Práce", BigDecimal.ONE, "ks", BigDecimal("72000.00"), vatRate = VatRate.ZERO)),
    )

    private fun received(doc: String, base: String, vat: String, rate: VatRate = VatRate.STANDARD) = ReceivedInvoice(
        "r-$doc", "Dodavatel", "CZ99999999", doc, LocalDate.of(2026, 8, 2), q3, mapOf(rate to VatAmount(BigDecimal(base), BigDecimal(vat))),
    )

    private fun q3Data() = QuarterData(
        q3,
        q3Issued() + euInvoice("2600030", "DE123456789") + euInvoice("2600031", "ATU12345678"),
        listOf(received("FV-1", "20000.00", "4200.00"), received("FV-2", "1000.00", "120.00", VatRate.REDUCED)),
    )

    @Test fun `user's accepted DP passes`() {
        assertEquals(emptyList(), XsdValidator(EpoForm.DP).validate(fixture("dp-2026-q1-accepted.xml")))
    }

    @Test fun `user's accepted KH passes once the three EPO extras are removed`() {
        // EPO's own export carries mesic="" and c_pop="" (decimals) and id_dats (not in the XSD); the portal accepts them.
        val original = fixture("kh-2026-q3-accepted.xml").toString(Charsets.UTF_8)
        val validator = XsdValidator(EpoForm.KH)
        val errors = validator.validate(original.toByteArray())
        for (attr in listOf("mesic", "c_pop", "id_dats")) assertTrue(errors.any { "'$attr'" in it }, errors.toString())
        val cleaned = original.replace(" mesic=\"\"", "").replace(" c_pop=\"\"", "").replace(" id_dats=\"\"", "")
        assertEquals(emptyList(), validator.validate(cleaned.toByteArray()))
    }

    @Test fun `generated Q3 KH passes`() {
        val kh = KhBuilder.build(seller, QuarterData(q3, q3Issued(), emptyList()), filing)
        assertEquals(emptyList(), XsdValidator(EpoForm.KH).validate(EpoXmlWriter.write(kh)))
    }

    @Test fun `generated KH with received invoices and EU sales passes`() {
        val kh = KhBuilder.build(seller, q3Data(), filing)
        assertEquals(emptyList(), XsdValidator(EpoForm.KH).validate(EpoXmlWriter.write(kh)))
    }

    @Test fun `generated DP and SH pass`() {
        val data = q3Data()
        assertEquals(emptyList(), XsdValidator(EpoForm.DP).validate(EpoXmlWriter.write(DpBuilder.build(seller, data, filing))))
        assertEquals(emptyList(), XsdValidator(EpoForm.SH).validate(EpoXmlWriter.write(ShBuilder.build(seller, data, filing)!!)))
    }

    @Test fun `house number with orientation number passes in all forms`() {
        val s = seller.copy(address = address.copy(houseNumber = "742/15B"), phone = null)
        val data = q3Data()
        assertEquals(emptyList(), XsdValidator(EpoForm.KH).validate(EpoXmlWriter.write(KhBuilder.build(s, data, filing))))
        assertEquals(emptyList(), XsdValidator(EpoForm.DP).validate(EpoXmlWriter.write(DpBuilder.build(s, data, filing))))
        assertEquals(emptyList(), XsdValidator(EpoForm.SH).validate(EpoXmlWriter.write(ShBuilder.build(s, data, filing)!!)))
    }

    @Test fun `broken document reports errors`() {
        val kh = KhBuilder.build(seller, QuarterData(q3, q3Issued(), emptyList()), filing)
        val broken = kh.copy(children = kh.children.map { c ->
            if (c.name == "VetaA4") c.copy(attributes = c.attributes.filter { it.first != "dic_odb" }) else c
        })
        val errors = XsdValidator(EpoForm.KH).validate(EpoXmlWriter.write(broken))
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.all { "dic_odb" in it }, errors.toString())
    }

    @Test fun `malformed XML reports an error instead of throwing`() {
        assertTrue(XsdValidator(EpoForm.KH).validate("<Pisemnost>".toByteArray()).isNotEmpty())
    }
}
