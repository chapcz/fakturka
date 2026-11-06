package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.Address
import cz.chap.fakturka.domain.Contact
import cz.chap.fakturka.domain.ContactType
import cz.chap.fakturka.domain.VatRate
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.DataRepository
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class TemplatesStateTest {
    @TempDir
    lateinit var dir: Path

    private val item = TemplateItemDraft("Programátorské práce", "h", "1 000,00", VatRate.STANDARD, "")

    private fun repoWithContact(): DataRepository = DataRepository(dir).also {
        it.saveContact(Contact("c1", "Alfa", Address("U", "1", "P", "11000", "CZ"), null, null, null, null, ContactType.DOMESTIC))
    }

    private fun draft(items: List<TemplateItemDraft> = listOf(item), customerId: String = "c1", dueDays: String = "") =
        TemplateDraft("t1", "Alfa – vývoj", customerId, "", dueDays, items)

    @Test fun `template needs at least one item and a customer`() {
        val state = TemplatesState(repoWithContact())
        assertEquals(
            listOf(Messages.get("ui.templates.error.customer"), Messages.get("ui.templates.error.items")),
            state.validate(draft(items = emptyList(), customerId = "")),
        )
        assertEquals(listOf(Messages.get("ui.templates.error.customer")), state.validate(draft(customerId = "missing")))
        assertTrue(state.validate(draft()).isEmpty())
    }

    @Test fun `name and item fields are required`() {
        val state = TemplatesState(repoWithContact())
        val bad = TemplateItemDraft("", "", "1000", VatRate.STANDARD, "")
        assertEquals(
            listOf(Messages.get("ui.templates.error.name")) +
                listOf("description", "unit").map { Messages.get("ui.templates.error.itemRow") + " 1: " + Messages.get("ui.templates.error.item.$it") },
            state.validate(draft(items = listOf(bad)).copy(name = " ")),
        )
    }

    @Test fun `unit price must be a positive decimal in either notation`() {
        val state = TemplatesState(repoWithContact())
        val error = Messages.get("ui.templates.error.itemRow") + " 1: " + Messages.get("ui.templates.error.item.unitPrice")
        for (bad in listOf("", "x", "0", "-5", "1,2,3")) {
            assertEquals(listOf(error), state.validate(draft(items = listOf(item.copy(unitPriceText = bad)))), bad)
        }
        for (good in listOf("1000.00", "1 000,00", "1000", "8 925,5")) {
            assertTrue(state.validate(draft(items = listOf(item.copy(unitPriceText = good)))).isEmpty(), good)
        }
    }

    @Test fun `optional quantity and due days are validated`() {
        val state = TemplatesState(repoWithContact())
        val row = Messages.get("ui.templates.error.itemRow")
        assertEquals(
            listOf(Messages.get("ui.templates.error.dueDays"), "$row 1: " + Messages.get("ui.templates.error.item.quantity")),
            state.validate(draft(items = listOf(item.copy(quantityText = "0")), dueDays = "x")),
        )
        assertTrue(state.validate(draft(items = listOf(item.copy(quantityText = "1,5")), dueDays = "30")).isEmpty())
    }

    @Test fun `save stores money at scale 2 and reloads`() {
        val state = TemplatesState(repoWithContact())
        state.save(draft(items = listOf(item.copy(quantityText = "2")), dueDays = "30"))
        val saved = DataRepository(dir).templates().single()
        assertEquals("Alfa – vývoj", saved.name)
        assertEquals(BigDecimal("1000.00"), saved.items.single().unitPrice)
        assertEquals(BigDecimal("2"), saved.items.single().defaultQuantity)
        assertEquals(30, saved.dueDays)
        assertEquals(null, saved.orderNumber)
        assertEquals(saved, TemplatesState(DataRepository(dir)).templates.single())
        assertEquals(draft(items = listOf(item.copy(unitPriceText = "1000.00", quantityText = "2")), dueDays = "30"), TemplateDraft.from(saved))
        state.delete("t1")
        assertTrue(DataRepository(dir).templates().isEmpty())
        assertTrue(state.templates.isEmpty())
    }

    @Test fun `price rounding to zero is rejected and errors name the row`() {
        val state = TemplatesState(repoWithContact())
        val row = Messages.get("ui.templates.error.itemRow")
        val price = Messages.get("ui.templates.error.item.unitPrice")
        assertEquals(listOf("$row 2: $price"), state.validate(draft(items = listOf(item, item.copy(unitPriceText = "0.001")))))
        assertTrue(state.validate(draft(items = listOf(item.copy(unitPriceText = "0.005")))).isEmpty())
    }

    @Test fun `default quantity keeps its own scale`() {
        val state = TemplatesState(repoWithContact())
        state.save(draft(items = listOf(item.copy(quantityText = "0,125"))))
        assertEquals(BigDecimal("0.125"), DataRepository(dir).templates().single().items.single().defaultQuantity)
    }

    @Test fun `template item cannot use zero VAT`() {
        val state = TemplatesState(repoWithContact())
        val error = Messages.get("ui.templates.error.itemRow") + " 1: " + Messages.get("ui.templates.error.item.vatRate")
        assertEquals(listOf(error), state.validate(draft(items = listOf(item.copy(vatRate = VatRate.ZERO)))))
        assertTrue(state.validate(draft(items = listOf(item.copy(vatRate = VatRate.REDUCED)))).isEmpty())
    }
}
