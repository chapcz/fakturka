package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.*
import cz.chap.fakturka.i18n.Messages
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvoiceDraftTest {
    private val seller = SellerConfig(
        "Test Dodavatel", "Test", "Dodavatel", "", Address("Testovací", "1", "Praha", "11000", "CZ"), "12345678", "CZ12345678",
        "test@example.com", null, null, "Banka", "1/0100", "CZ00", "SWIFT", "461", "2509", "62020",
    )
    private val customer = Contact("c1", "Alfa", Address("U", "1", "P", "11000", "CZ"), null, "CZ87654321", null, null, ContactType.DOMESTIC)
    private val template = Template(
        "t1", "Alfa – vývoj", "c1", "OBJ-1", 30,
        listOf(TemplateItem("Programátorské práce", "h", BigDecimal("1000.00"), VatRate.STANDARD, null)),
    )
    private val issue = LocalDate.of(2026, 11, 2)

    private fun draft() = InvoiceDraft.fromTemplate(template, customer, seller, "2600023", issue)

    private fun withQuantity(text: String) = draft().let { it.copy(items = it.items.map { i -> i.copy(quantityText = text) }) }

    @Test fun `template draft totals update with quantity`() {
        assertEquals(BigDecimal("22385.00"), withQuantity("18.5").totals.total)
        assertEquals(BigDecimal("0.00"), draft().totals.total)
        assertEquals(BigDecimal("22385.00"), withQuantity("18,5").totals.total)
    }

    @Test fun `template fills number dates and items`() {
        val d = draft()
        assertEquals("2600023", d.number)
        assertEquals("2.11.2026", d.issueDate)
        assertEquals("2.11.2026", d.taxDate)
        assertEquals("2.12.2026", d.dueDate)
        assertEquals("OBJ-1", d.orderNumber)
        assertEquals("1000.00", d.items.single().unitPriceText)
    }

    @Test fun `quantity required for each item`() {
        assertEquals(
            listOf(Messages.get("ui.invoice.error.itemRow") + " 1: " + Messages.get("ui.invoice.error.item.quantity")),
            draft().validate(),
        )
        assertTrue(withQuantity("18.5").validate().isEmpty())
        assertEquals(1, withQuantity("abc").validate().size)
        assertEquals(1, withQuantity("0").validate().size)
    }

    @Test fun `changing issue date in dialog moves due date by template due days`() {
        val moved = draft().withIssueDate("10.11.2026")
        assertEquals("10.11.2026", moved.taxDate)
        assertEquals("10.12.2026", moved.dueDate)
    }

    @Test fun `dates typed by the user stop following the issue date`() {
        val moved = draft().withDueDate("20.11.2026").withIssueDate("10.11.2026")
        assertEquals("20.11.2026", moved.dueDate)
        assertEquals("10.11.2026", moved.taxDate)
    }

    @Test fun `unparseable issue date leaves other dates alone`() {
        val d = draft().withIssueDate("10.11.20")
        assertEquals("2.11.2026", d.taxDate)
        assertTrue(Messages.get("ui.invoice.error.issueDate") in withQuantity("1").withIssueDate("10.11.20").validate())
    }

    @Test fun `unparseable line counts as zero in live total`() {
        val d = draft().let { it.copy(items = it.items + it.items.first().copy(quantityText = "2")) }
        assertEquals(BigDecimal("2000.00"), d.totals.base)
    }

    @Test fun `toInvoice builds invoice with symbols and money scale`() {
        val inv = withQuantity("18.5").toInvoice()
        assertEquals("2600023", inv.variableSymbol)
        assertEquals(BigDecimal("18.5"), inv.items.single().quantity)
        assertEquals(BigDecimal("1000.00"), inv.items.single().unitPrice)
        assertEquals(LocalDate.of(2026, 12, 2), inv.dueDate)
        assertEquals(false, inv.external)
    }

    @Test fun `external draft needs number and customer and keeps dates independent`() {
        val d = InvoiceDraft.external(seller, issue)
        assertEquals(
            listOf(Messages.get("ui.invoice.error.number"), Messages.get("ui.invoice.error.customer")) +
                (Messages.get("ui.invoice.error.itemRow") + " 1: ").let { p ->
                    listOf("description", "quantity", "unitPrice").map { p + Messages.get("ui.invoice.error.item.$it") }
                },
            d.validate(),
        )
        val moved = d.withIssueDate("10.11.2026")
        assertEquals("2.11.2026", moved.taxDate)
    }

    @Test fun `eu customer on empty draft gets zero vat`() {
        val eu = customer.copy(type = ContactType.EU, dic = "DE123456789")
        assertEquals(VatRate.ZERO, InvoiceDraft.empty(seller, "2600023", issue).withCustomer(eu).items.single().vatRate)
    }

    @Test fun `own invoice number must match issue year`() {
        val d = withQuantity("1").withIssueDate("2.1.2027")
        assertEquals(listOf(Messages.get("ui.invoice.error.numberYear")), d.validate())
    }

    private fun withItem(price: String, discount: String, qty: String = "2") =
        draft().let { it.copy(items = listOf(it.items.single().copy(quantityText = qty, unitPriceText = price, discountText = discount))) }

    @Test fun `discount above line amount is rejected`() {
        assertEquals(listOf(row("discountTooHigh")), withItem("100", "200.01").validate())
        assertTrue(withItem("100", "200").validate().isEmpty())
        assertEquals(BigDecimal("0.00"), withItem("100", "200").totals.total)
    }

    @Test fun `more than two decimals in price or discount is rejected`() {
        assertEquals(listOf(row("unitPrice")), withItem("100.123", "").validate())
        assertEquals(listOf(row("discount")), withItem("100", "1.234").validate())
        assertTrue(withItem("100.10", "1,50").validate().isEmpty())
        assertTrue(withItem("100.100", "").validate().isEmpty())
    }

    private fun row(key: String) = Messages.get("ui.invoice.error.itemRow") + " 1: " + Messages.get("ui.invoice.error.item.$key")

    @Test fun `switching from eu back to domestic restores chosen rates`() {
        val eu = customer.copy(type = ContactType.EU, dic = "DE123456789")
        val base = InvoiceDraft.empty(seller, "2600023", issue)
        val reduced = base.copy(items = base.items.map { it.copy(vatRate = VatRate.REDUCED) })
        val inEu = reduced.withCustomer(eu)
        assertEquals(VatRate.ZERO, inEu.items.single().vatRate)
        assertEquals(VatRate.REDUCED, inEu.withCustomer(customer).items.single().vatRate)
        assertEquals(VatRate.REDUCED, reduced.withCustomer(customer).items.single().vatRate)
    }

    @Test fun `domestic customer refuses zero VAT`() {
        val zero = withQuantity("1").let { it.copy(items = it.items.map { i -> i.copy(vatRate = VatRate.ZERO) }) }
        assertEquals(listOf(row("rateDomestic")), zero.validate())
        val reduced = withQuantity("1").let { it.copy(items = it.items.map { i -> i.copy(vatRate = VatRate.REDUCED) }) }
        assertTrue(reduced.validate().isEmpty())
    }

    @Test fun `EU customer needs zero VAT on every item`() {
        val eu = customer.copy(type = ContactType.EU, dic = "DE123456789")
        val inEu = withQuantity("1").withCustomer(eu)
        assertTrue(inEu.validate().isEmpty())
        val standard = inEu.copy(items = inEu.items.map { it.copy(vatRate = VatRate.STANDARD) })
        assertEquals(listOf(row("rateEu")), standard.validate())
    }
}
