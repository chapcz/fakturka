package cz.chap.fakturka.domain

import cz.chap.fakturka.i18n.Messages
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InvoiceFactoryTest {
    private val address = Address("Ulice", "1", "Praha", "11000", "CZ")
    private val seller = SellerConfig(
        "Test Dodavatel", "Test", "Dodavatel", "", address, "12345678", "CZ12345678", "a@b.cz", null, null,
        "Banka", "1/0100", "CZ00", "KOMBCZPP", "451", "2001", "62020", constantSymbol = "0308", dueDays = 14,
    )
    private val template = Template(
        "t1", "Práce", "c1", "OBJ-1", null,
        listOf(TemplateItem("Práce", "h", BigDecimal("1000.00"), VatRate.STANDARD, null)),
    )

    private fun customer(type: ContactType) =
        Contact("c1", "Zákazník", address, "1", "CZ1", null, null, type)

    @Test fun `builds invoice from template`() {
        val c = customer(ContactType.DOMESTIC)
        val i = InvoiceFactory.fromTemplate(template, c, seller, "2600022", LocalDate.of(2026, 11, 2), listOf(BigDecimal("18.5")))
        assertEquals("2600022", i.number)
        assertEquals(i.issueDate, i.taxDate)
        assertEquals(LocalDate.of(2026, 11, 16), i.dueDate)
        assertEquals("2600022", i.variableSymbol)
        assertEquals("0308", i.constantSymbol)
        assertEquals(c, i.customer)
        assertEquals(BigDecimal("18.5"), i.items[0].quantity)
        assertEquals(BigDecimal("1000.00"), i.items[0].unitPrice)
        assertEquals(VatRate.STANDARD, i.items[0].vatRate)
        assertEquals(Messages.get("payment.transfer"), i.paymentMethod)
    }

    @Test fun `template due days override seller`() {
        val i = InvoiceFactory.fromTemplate(template.copy(dueDays = 30), customer(ContactType.DOMESTIC), seller, "2600022", LocalDate.of(2026, 11, 2), listOf(BigDecimal.ONE))
        assertEquals(LocalDate.of(2026, 12, 2), i.dueDate)
    }

    @Test fun `EU customer forces ZERO rate on all items`() {
        val t = template.copy(items = template.items + template.items)
        val i = InvoiceFactory.fromTemplate(t, customer(ContactType.EU), seller, "2600022", LocalDate.of(2026, 11, 2), listOf(BigDecimal.ONE, BigDecimal.TEN))
        assertEquals(listOf(VatRate.ZERO, VatRate.ZERO), i.items.map { it.vatRate })
    }

    @Test fun `quantity count mismatch fails`() {
        assertFailsWith<IllegalArgumentException> {
            InvoiceFactory.fromTemplate(template, customer(ContactType.DOMESTIC), seller, "2600022", LocalDate.of(2026, 11, 2), emptyList())
        }
    }
}
