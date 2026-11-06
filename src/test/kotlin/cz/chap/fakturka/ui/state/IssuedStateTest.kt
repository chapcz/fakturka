package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.*
import cz.chap.fakturka.pdf.InvoicePdfRenderer
import cz.chap.fakturka.storage.DataRepository
import cz.chap.fakturka.storage.RepositoryException
import cz.chap.fakturka.tax.ExportService
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class IssuedStateTest {
    @TempDir
    lateinit var dir: Path

    private val zone = ZoneId.of("Europe/Prague")
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 11, 2, 10, 0, 0, 0, zone).toInstant(), zone)
    private val customer = Contact("c1", "Alfa", Address("U", "1", "P", "11000", "CZ"), "87654321", "CZ87654321", null, null, ContactType.DOMESTIC)
    private val template = Template(
        "t1", "Alfa – vývoj", "c1", null, null,
        listOf(TemplateItem("Programátorské práce", "h", BigDecimal("1000.00"), VatRate.STANDARD, null)),
    )
    private val seller = SellerConfig(
        "Test Dodavatel", "Test", "Dodavatel", "", Address("Testovací", "1", "Praha", "11000", "Česká republika"), "12345678", "CZ12345678",
        "test@example.com", null, null, "Banka", "1/0100", "CZ6508000000192000145399", "SWIFT", "461", "2509", "62020",
        numberingStartYear = 2026, numberingStartSequence = 23,
    )

    private val repo by lazy {
        DataRepository(dir).also {
            it.saveConfig(seller)
            it.saveContact(customer)
            it.saveTemplate(template)
        }
    }
    private var failRender = false
    private val state by lazy {
        IssuedState(repo, ExportService(repo, clock), clock) { inv, s, target ->
            if (failRender) throw IllegalStateException("render failed")
            InvoicePdfRenderer.render(inv, s, target)
        }
    }

    private fun external(number: String, price: String = "1000", issue: String = "2.11.2026") =
        state.newExternal().withCustomer(customer).withIssueDate(issue).let {
            it.copy(number = number, items = listOf(it.items.single().copy(description = "Práce", quantityText = "1", unitPriceText = price)))
        }

    private fun exportQuarter(q: Quarter) {
        repo.exportDir(q).createDirectories()
        for (f in listOf("KH", "DP")) repo.exportDir(q).resolve("$f-${q.year}-Q${q.quarter}.xml").writeText("<x/>")
    }

    private fun filled(quantity: String = "18.5") =
        state.newFromTemplate(template).let { it.copy(items = it.items.map { i -> i.copy(quantityText = quantity) }) }

    @Test fun `year defaults to the clock year`() = assertEquals(2026, state.year)

    @Test fun `marking paid stores today's date, undo clears it, and an edit keeps it`() {
        state.save(filled(), null)
        val stored = state.invoices.single()
        state.setPaid(stored, true)
        assertEquals(LocalDate.of(2026, 11, 2), repo.issued(2026).single().paidDate)

        // Editing the invoice must not lose the payment.
        state.save(state.edit(repo.issued(2026).single()).copy(orderNumber = "OBJ-1"), stored.number)
        assertEquals(LocalDate.of(2026, 11, 2), repo.issued(2026).single().paidDate)

        state.setPaid(repo.issued(2026).single(), false)
        assertNull(repo.issued(2026).single().paidDate)
    }

    @Test fun `new draft takes next number from repository`() = assertEquals("2600023", state.newFromTemplate(template).number)

    @Test fun `saving own invoice creates pdf`() {
        val outcome = state.save(filled(), null)
        assertNotNull(outcome.pdf)
        assertTrue(outcome.pdf!!.exists())
        assertTrue(Files.size(outcome.pdf!!) > 0)
        assertNull(outcome.exportedQuarterWarning)
        assertEquals(listOf("2600023"), state.invoices.map { it.number })
        assertEquals("2600024", state.newFromTemplate(template).number)
    }

    @Test fun `external invoice creates no pdf and keeps typed number`() {
        val d = state.newExternal().withCustomer(customer).let {
            it.copy(number = "2600020", items = listOf(it.items.single().copy(description = "Práce", quantityText = "1", unitPriceText = "1000")))
        }
        assertTrue(d.validate().isEmpty())
        val outcome = state.save(d, null)
        assertNull(outcome.pdf)
        val stored = repo.issued(2026).single()
        assertEquals("2600020", stored.number)
        assertTrue(stored.external)
        assertFalse(repo.pdfPath(stored).exists())
        assertEquals("2600023", state.newFromTemplate(template).number)

        // On request a PDF can still be made for it.
        val pdf = state.renderPdf(stored)
        assertEquals(repo.pdfPath(stored), pdf)
        assertTrue(pdf.exists())
    }

    @Test fun `warning when quarter already exported`() {
        val q4 = Quarter(2026, 4)
        repo.exportDir(q4).createDirectories()
        repo.exportDir(q4).resolve("KH-2026-Q4.xml").writeText("<x/>")
        repo.exportDir(q4).resolve("DP-2026-Q4.xml").writeText("<x/>")
        val outcome = state.save(filled(), null)
        assertEquals(q4, outcome.exportedQuarterWarning)
    }

    @Test fun `editing keeps number replaces invoice and regenerates pdf`() {
        state.save(filled(), null)
        val stored = state.invoices.single()
        val edited = state.edit(stored).let { it.copy(items = it.items.map { i -> i.copy(quantityText = "20") }) }
        val outcome = state.save(edited, stored.number)
        assertTrue(outcome.pdf!!.exists())
        val after = repo.issued(2026).single()
        assertEquals("2600023", after.number)
        assertEquals(BigDecimal("20"), after.items.single().quantity)
    }

    @Test fun `changing issue date to another year recomputes the number`() {
        val moved = state.withIssueDate(state.newFromTemplate(template), "5.1.2027")
        assertEquals("2700001", moved.number)
        assertEquals("5.1.2027", moved.taxDate)
    }

    @Test fun `delete only last own invoice but external any time`() {
        state.save(filled(), null)
        state.save(state.newFromTemplate(template).let { it.copy(items = it.items.map { i -> i.copy(quantityText = "1") }) }, null)
        val (last, first) = state.invoices
        assertTrue(state.canDelete(last))
        assertFalse(state.canDelete(first))
        assertFailsWith<RepositoryException> { state.delete(first.number) }
        state.delete(last.number)
        assertEquals(listOf(first.number), state.invoices.map { it.number })
    }

    @Test fun `switching year reloads invoices`() {
        state.save(filled(), null)
        state.year = 2025
        assertTrue(state.invoices.isEmpty())
        state.year = 2026
        assertEquals(1, state.invoices.size)
    }

    @Test fun `template with missing customer fails with message`() {
        repo.deleteContact("c1")
        assertFailsWith<RepositoryException> { state.newFromTemplate(template) }
    }

    @Test fun `duplicate external number is refused every time and stored invoice stays`() {
        state.save(external("2600020", "1000"), null)
        val dup = external("2600020", "2000")
        repeat(2) { assertFailsWith<RepositoryException> { state.save(dup, null) } }
        assertEquals(BigDecimal("1000.00"), repo.issued(2026).single().items.single().unitPrice)
    }

    @Test fun `pdf failure after save is typed and retry replaces the stored invoice`() {
        failRender = true
        val d = filled()
        val failure = assertFailsWith<PdfFailedAfterSave> { state.save(d, null) }
        assertEquals("2600023", failure.invoice.number)
        assertEquals(1, repo.issued(2026).size)
        failRender = false
        val outcome = state.save(d.copy(orderNumber = "X"), failure.invoice.number)
        assertTrue(outcome.pdf!!.exists())
        assertEquals("X", repo.issued(2026).single().orderNumber)
    }

    @Test fun `editing external invoice into another year warns about the original exported quarter`() {
        state.save(external("2600020"), null)
        exportQuarter(Quarter(2026, 4))
        val stored = state.invoices.single()
        val moved = state.edit(stored).withIssueDate("5.2.2027").withTaxDate("5.2.2027")
        val outcome = state.save(moved, stored.number)
        assertEquals(Quarter(2026, 4), outcome.exportedQuarterWarning)
        assertEquals(1, repo.issued(2027).size)
        assertTrue(repo.issued(2026).isEmpty())
    }

    @Test fun `deleting an invoice of an exported quarter reports the stale quarter`() {
        state.save(external("2600020"), null)
        assertNull(state.delete("2600020"))
        state.save(external("2600021"), null)
        exportQuarter(Quarter(2026, 4))
        assertEquals(Quarter(2026, 4), state.delete("2600021"))
    }

    @Test fun `year stays unchanged when loading the new year fails`() {
        state.year = 2026
        Files.createDirectories(dir.resolve("2025"))
        Files.writeString(dir.resolve("2025").resolve("issued.xml"), "<not xml")
        val before = state.invoices
        assertFails { state.year = 2025 }
        assertEquals(2026, state.year)
        assertEquals(before, state.invoices)
    }

    @Test fun `editing an issued invoice can pick the customer again from the address book`() {
        state.save(filled(), null)
        val stored = state.invoices.single()
        val fixed = customer.copy(dic = "CZ11223344", name = "Alfa opravená")
        repo.saveContact(fixed)
        val draft = state.edit(stored)
        assertFalse(draft.customerLocked)
        val current = state.contacts.single { it.id == customer.id }
        state.save(draft.withCustomer(current), stored.number)
        assertEquals(fixed, repo.issued(2026).single().customer)
    }

    @Test fun `saving into another year switches the list to that year`() {
        assertEquals(2026, state.year)
        state.save(external("2700001", issue = "5.1.2027"), null)
        assertEquals(2027, state.year)
        assertEquals(listOf("2700001"), state.invoices.map { it.number })
    }
}
