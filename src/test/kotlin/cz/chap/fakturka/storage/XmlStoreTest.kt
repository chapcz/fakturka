package cz.chap.fakturka.storage

import cz.chap.fakturka.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class XmlStoreTest {
    @TempDir
    lateinit var dir: Path

    private val store = XmlStore()
    private val address = Address("Ulice", "12", "Praha", "11000", "CZ")
    private val customer = Contact("c1", "Zákazník s.r.o.", address, "87654321", "CZ87654321", null, null, ContactType.DOMESTIC)

    private val invoice = IssuedInvoice(
        number = "2600022",
        issueDate = LocalDate.of(2026, 11, 2),
        taxDate = LocalDate.of(2026, 10, 31),
        dueDate = LocalDate.of(2026, 11, 16),
        customer = customer,
        orderNumber = "OBJ-1",
        constantSymbol = "0308",
        variableSymbol = "2600022",
        paymentMethod = "převodním příkazem",
        items = listOf(
            InvoiceItem("Programátorské práce – příliš žluťoučký kůň", "18.5".toBigDecimal(), "h", "1000.00".toBigDecimal(), "0.00".toBigDecimal(), VatRate.STANDARD),
        ),
    )

    @Test
    fun `round trip issued file`() {
        val file = IssuedFile(2026, listOf(invoice))
        val path = dir.resolve("issued-2026.xml")
        store.write(path, file)
        assertEquals(file, store.read(path, IssuedFile::class))
    }

    @Test
    fun `round trip other files`() {
        val received = ReceivedFile(
            2026,
            listOf(
                ReceivedInvoice(
                    "r1", "Dodavatel", null, "FV1", LocalDate.of(2022, 12, 11), Quarter(2026, 2),
                    mapOf(
                        VatRate.STANDARD to VatAmount("100.00".toBigDecimal(), "21.00".toBigDecimal()),
                        VatRate.ZERO to VatAmount("5.50".toBigDecimal(), "0.00".toBigDecimal()),
                    ),
                ),
            ),
        )
        val p1 = dir.resolve("received.xml")
        store.write(p1, received)
        assertEquals(received, store.read(p1, ReceivedFile::class))

        val contacts = ContactsFile(listOf(customer, customer.copy(id = "c2", deliveryAddress = address, ico = null)))
        val p2 = dir.resolve("contacts.xml")
        store.write(p2, contacts)
        assertEquals(contacts, store.read(p2, ContactsFile::class))
        assertFalse(p2.readText().contains("hasCzVatId"))

        val templates = TemplatesFile(
            listOf(
                Template(
                    "t1", "Měsíční", "c1", null, 14,
                    listOf(TemplateItem("Práce", "h", "1000.00".toBigDecimal(), VatRate.REDUCED, "8".toBigDecimal())),
                ),
                Template("t2", "Prázdná", "c1", "O", null, emptyList()),
            ),
        )
        val p3 = dir.resolve("templates.xml")
        store.write(p3, templates)
        assertEquals(templates, store.read(p3, TemplatesFile::class))

        val config = ConfigFile(
            SellerConfig(
                "Test Dodavatel", "Test", "Dodavatel", "Ing.", address, "12345678", "CZ12345678", "a@b.cz", null, null,
                "Banka", "123/0100", "CZ00", "KOMBCZPP", "451", "2001", "62020",
                titleAfter = "Ph.D.",
            ),
        )
        val p4 = dir.resolve("config.xml")
        store.write(p4, config)
        assertEquals(config, store.read(p4, ConfigFile::class))
        assertTrue(!p4.readText().contains("<titles>"), "computed titles must not be written")
    }

    @Test
    fun `config written before titleAfter existed still reads`() {
        val path = dir.resolve("config.xml")
        val config = ConfigFile(
            SellerConfig(
                "Test Dodavatel", "Test", "Dodavatel", "Ing.", address, "12345678", "CZ12345678", "a@b.cz", null, null,
                "Banka", "123/0100", "CZ00", "KOMBCZPP", "451", "2001", "62020",
            ),
        )
        store.write(path, config)
        path.writeText(path.readText().replace(Regex("\\s*<titleAfter>[^<]*</titleAfter>|\\s*<titleAfter/>"), ""))
        assertTrue(!path.readText().contains("titleAfter"))
        assertEquals("", store.read(path, ConfigFile::class)!!.seller.titleAfter)
    }

    @Test
    fun `money written as plain two-decimal string`() {
        val path = dir.resolve("i.xml")
        store.write(path, IssuedFile(2026, listOf(invoice)))
        val text = path.readText()
        assertTrue(text.contains("<unitPrice>1000.00</unitPrice>"), text)
        assertTrue(text.contains("<quantity>18.5</quantity>"), text)
        assertTrue(text.contains("<invoices>") && text.contains("<invoice>") && text.contains("<item>"), text)
    }

    @Test
    fun `dates written as ISO`() {
        val path = dir.resolve("i.xml")
        store.write(path, IssuedFile(2026, listOf(invoice)))
        assertTrue(path.readText().contains("2026-11-02"))
    }

    @Test
    fun `root carries version`() {
        val path = dir.resolve("i.xml")
        store.write(path, IssuedFile(2026, listOf(invoice)))
        val text = path.readText()
        assertTrue(text.contains("version=\"1\""), text)
        assertTrue(text.contains("<issuedInvoices"), text)
        assertTrue(text.contains("UTF-8"), text)
    }

    @Test
    fun `missing file returns null`() {
        assertNull(store.read(dir.resolve("nope.xml"), IssuedFile::class))
    }

    @Test
    fun `malformed file throws StorageException and is not modified`() {
        val path = dir.resolve("bad.xml")
        Files.writeString(path, "<issuedInvoices><broken")
        val before = path.readBytes()
        val e = assertFailsWith<StorageException> { store.read(path, IssuedFile::class) }
        assertTrue(e.message!!.contains("bad.xml"))
        assertContentEquals(before, path.readBytes())
    }

    @Test
    fun `default discount round trips equal`() {
        val item = InvoiceItem("x", "2".toBigDecimal(), "h", "10.00".toBigDecimal(), vatRate = VatRate.ZERO)
        val file = IssuedFile(2026, listOf(invoice.copy(items = listOf(item))))
        val path = dir.resolve("d.xml")
        store.write(path, file)
        assertEquals(file, store.read(path, IssuedFile::class))
    }

    @Test
    fun `unscaled hand-written money reads as scale two`() {
        val path = dir.resolve("m.xml")
        store.write(path, IssuedFile(2026, listOf(invoice)))
        Files.writeString(path, path.readText().replace("<unitPrice>1000.00</unitPrice>", "<unitPrice>1000</unitPrice>"))
        assertEquals("1000.00".toBigDecimal(), store.read(path, IssuedFile::class)!!.invoices[0].items[0].unitPrice)
    }

    @Test
    fun `unknown element throws StorageException and file unchanged`() {
        val path = dir.resolve("u.xml")
        store.write(path, IssuedFile(2026, listOf(invoice)))
        Files.writeString(path, path.readText().replace("<invoices>", "<mystery>1</mystery><invoices>"))
        val before = path.readBytes()
        val e = assertFailsWith<StorageException> { store.read(path, IssuedFile::class) }
        assertTrue(e.message!!.contains("u.xml"))
        assertContentEquals(before, path.readBytes())
    }

    @Test
    fun `unsupported version throws StorageException`() {
        val path = dir.resolve("v.xml")
        store.write(path, IssuedFile(2026, listOf(invoice)))
        Files.writeString(path, path.readText().replace("version=\"1\"", "version=\"2\""))
        assertFailsWith<StorageException> { store.read(path, IssuedFile::class) }
    }

    @Test
    fun `quantity never in scientific notation`() {
        val item = InvoiceItem("x", "1E+3".toBigDecimal(), "h", "1.00".toBigDecimal(), vatRate = VatRate.ZERO)
        val path = dir.resolve("q.xml")
        store.write(path, IssuedFile(2026, listOf(invoice.copy(items = listOf(item)))))
        assertTrue(path.readText().contains("<quantity>1000</quantity>"), path.readText())
    }
}
