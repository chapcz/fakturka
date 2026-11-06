package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.Address
import cz.chap.fakturka.domain.Contact
import cz.chap.fakturka.domain.ContactType
import cz.chap.fakturka.domain.Template
import cz.chap.fakturka.domain.TemplateItem
import cz.chap.fakturka.domain.VatRate
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.DataRepository
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ContactsStateTest {
    @TempDir
    lateinit var dir: Path

    private fun contact(id: String = "c1", type: ContactType = ContactType.DOMESTIC, dic: String? = null) = Contact(
        id, "Alfa Software s.r.o.", Address("Ulice", "1", "Praha", "11000", "CZ"),
        "12345678", dic, null, null, type,
    )

    @Test fun `EU contact needs VAT ID with non-CZ country prefix`() {
        val state = ContactsState(DataRepository(dir))
        val error = Messages.get("ui.contacts.error.euDic")
        assertEquals(listOf(error), state.validate(contact(type = ContactType.EU, dic = "CZ123")))
        assertEquals(listOf(error), state.validate(contact(type = ContactType.EU, dic = null)))
        assertEquals(listOf(error), state.validate(contact(type = ContactType.EU, dic = "123456789")))
        assertTrue(state.validate(contact(type = ContactType.EU, dic = "DE123456789")).isEmpty())
    }

    @Test fun `domestic contact may have no DIC`() {
        assertTrue(ContactsState(DataRepository(dir)).validate(contact(dic = null)).isEmpty())
    }

    @Test fun `name is required`() {
        val state = ContactsState(DataRepository(dir))
        assertEquals(listOf(Messages.get("ui.contacts.error.name")), state.validate(contact().copy(name = " ")))
    }

    @Test fun `save and delete persist`() {
        val state = ContactsState(DataRepository(dir))
        state.save(contact())
        assertEquals(listOf(contact()), ContactsState(DataRepository(dir)).contacts)
        assertNull(state.delete("c1"))
        assertTrue(state.contacts.isEmpty())
        assertTrue(DataRepository(dir).contacts().isEmpty())
    }

    @Test fun `contact used by a template cannot be deleted`() {
        val repo = DataRepository(dir)
        val state = ContactsState(repo)
        state.save(contact())
        repo.saveTemplate(
            Template("t1", "T", "c1", null, null, listOf(TemplateItem("x", "h", BigDecimal("1.00"), VatRate.STANDARD, null))),
        )
        val message = state.delete("c1")
        assertNotNull(message)
        assertEquals(Messages.get("ui.contacts.error.usedByTemplate"), message)
        assertEquals(1, state.contacts.size)
        assertEquals(1, repo.contacts().size)
    }

    @Test fun `save trims text fields and blanks become null`() {
        val state = ContactsState(DataRepository(dir))
        val a = Address(" Ulice ", " 1 ", " Praha ", " 11000 ", " CZ ")
        state.save(contact().copy(name = "  Firma  ", address = a, ico = " 123 ", dic = "  ", email = " a@b.cz ", deliveryAddress = a))
        val saved = DataRepository(dir).contacts().single()
        val t = Address("Ulice", "1", "Praha", "11000", "CZ")
        assertEquals(contact().copy(name = "Firma", address = t, ico = "123", dic = null, email = "a@b.cz", deliveryAddress = t), saved)
    }

    @Test fun `save removes whitespace from DIC and uppercases it`() {
        val state = ContactsState(DataRepository(dir))
        state.save(contact(dic = "cz 11223344"))
        assertEquals("CZ11223344", DataRepository(dir).contacts().single().dic)
    }

    @Test fun `domestic DIC must be CZ followed by 8 to 10 digits`() {
        val state = ContactsState(DataRepository(dir))
        val error = listOf(Messages.get("ui.contacts.error.domesticDic"))
        assertEquals(error, state.validate(contact(dic = "11223344")))
        assertEquals(error, state.validate(contact(dic = "CZ1234567")))
        assertEquals(error, state.validate(contact(dic = "CZ12345678901")))
        assertEquals(error, state.validate(contact(dic = "DE123456789")))
        assertTrue(state.validate(contact(dic = "cz 11223344")).isEmpty())
        assertTrue(state.validate(contact(dic = "CZ7001011234")).isEmpty())
        assertTrue(state.validate(contact(dic = "  ")).isEmpty())
    }
}
