package cz.chap.fakturka.importing

import cz.chap.fakturka.domain.*
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.DataRepository
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportTest {
    @TempDir
    lateinit var dir: Path

    private val fixture: Path = Path.of(javaClass.getResource("/fixtures/profact-export.xml")!!.toURI())
    private fun parsed() = ImportFormats.parse(fixture)

    @Test fun `domestic invoice is read with customer, items, discount and dates`() {
        val inv = parsed().filterIsInstance<ImportDocument.Ready>().single { it.number == "2600020" }.invoice
        assertTrue(inv.external)
        assertEquals(LocalDate.of(2026, 10, 1), inv.issueDate)
        assertEquals(LocalDate.of(2026, 10, 1), inv.taxDate)
        assertEquals(LocalDate.of(2026, 10, 15), inv.dueDate)
        assertEquals("0308", inv.constantSymbol)
        assertEquals("2600020", inv.variableSymbol)
        assertEquals("převodním příkazem", inv.paymentMethod)
        assertEquals(ContactType.DOMESTIC, inv.customer.type)
        assertEquals("CZ12345678", inv.customer.dic)
        assertEquals(Address("Smyšlená", "742/15B", "Praha 2", "12000", "Česká republika"), inv.customer.address)
        assertEquals(null, inv.customer.deliveryAddress) // same as the main address
        assertEquals(2, inv.items.size)
        assertEquals(BigDecimal("20.5"), inv.items[0].quantity)
        assertEquals("h", inv.items[0].unit)
        assertEquals(BigDecimal("1000.00"), inv.items[0].unitPrice)
        assertEquals(BigDecimal("100.00"), inv.items[1].discount) // 10 % of 1 × 1000
        assertEquals(BigDecimal("25894.00"), inv.totals.total)
    }

    @Test fun `EU invoice becomes an EU customer with zero VAT`() {
        val inv = parsed().filterIsInstance<ImportDocument.Ready>().single { it.number == "2600021" }.invoice
        assertEquals(ContactType.EU, inv.customer.type)
        assertEquals("DE123456789", inv.customer.dic)
        assertTrue(inv.items.all { it.vatRate == VatRate.ZERO })
        assertEquals(Address("Teststraße", "5", "Berlin", "10115", "Německo"), inv.customer.address)
    }

    @Test fun `cancelled, other types, other currency and wrong totals are skipped with a reason`() {
        val skipped = parsed().filterIsInstance<ImportDocument.Skipped>().associate { it.number to it.reason }
        assertEquals(Messages.get("ui.import.skip.cancelled"), skipped["2600019"])
        assertEquals(Messages.get("ui.import.skip.type").format("Zálohová faktura"), skipped["2690001"])
        assertEquals(Messages.get("ui.import.skip.currency").format("EUR"), skipped["2600023"])
        assertEquals(Messages.get("ui.import.skip.total").format("999.99", "1 210.00"), skipped["2600024"])
    }

    @Test fun `plan skips existing numbers, adds missing contacts, apply stores taken-over invoices`() {
        val repo = DataRepository(dir)
        val importer = InvoiceImporter(repo)
        val plan = importer.plan(parsed())
        assertEquals(listOf("2600020", "2600021"), plan.toImport.map { it.number })
        assertEquals(listOf("Testovací firma s.r.o."), plan.newContacts.map { it.name }) // the EU customer has no IČ
        importer.apply(plan)
        assertEquals(listOf("2600020", "2600021"), repo.issued(2026).map { it.number })
        assertTrue(repo.issued(2026).all { it.external })
        assertEquals(1, repo.contacts().size)

        val again = importer.plan(parsed())
        assertEquals(emptyList(), again.toImport)
        assertEquals(Messages.get("ui.import.skip.exists"), again.skipped.single { it.number == "2600020" }.reason)
        assertEquals(emptyList(), again.newContacts)
    }

    @Test fun `own numbering is not moved by imported invoices`() {
        val repo = DataRepository(dir)
        repo.saveConfig(
            SellerConfig(
                "Test Dodavatel", "Test", "Dodavatel", "", Address("Ulice", "1", "Praha", "11000", "CZ"),
                "12345678", "CZ12345678", "a@b.cz", null, null, "Banka", "1/0100", "CZ00", "SWIFT", "451", "", "62020",
            ),
        )
        InvoiceImporter(repo).apply(InvoiceImporter(repo).plan(parsed()))
        assertEquals("2600001", repo.nextNumber(java.time.LocalDate.of(2026, 11, 2)))
    }

    @Test fun `unknown formats are refused with a plain message`() {
        val otherXml = dir.resolve("other.xml").also { it.writeText("<?xml version=\"1.0\"?><issuedInvoices/>") }
        val notXml = dir.resolve("data.csv").also { it.writeText("číslo;částka\n1;100") }
        for (file in listOf(otherXml, notXml)) {
            val e = assertFailsWith<IllegalArgumentException> { ImportFormats.parse(file) }
            assertEquals(Messages.get("ui.import.error.unsupported"), e.message)
        }
    }
}
