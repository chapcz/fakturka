package cz.chap.fakturka.storage

import cz.chap.fakturka.domain.*
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class DataRepositoryTest {
    @TempDir
    lateinit var dir: Path

    private val address = Address("Ulice", "1", "Praha", "11000", "CZ")
    private val customer = Contact("c1", "Zákazník", address, "1", "CZ1", null, null, ContactType.DOMESTIC)
    private val seller = SellerConfig(
        "Test Dodavatel", "Test", "Dodavatel", "", address, "12345678", "CZ12345678", "a@b.cz", null, null,
        "Banka", "1/0100", "CZ00", "KOMBCZPP", "451", "2001", "62020",
        numberingStartYear = 2026, numberingStartSequence = 23,
    )
    private val repo get() = DataRepository(dir)

    private fun inv(number: String, issue: LocalDate = LocalDate.of(2026, 11, 2), tax: LocalDate = issue, external: Boolean = false, price: String = "100.00") =
        IssuedInvoice(
            number, issue, tax, issue.plusDays(14), customer, null, "0308", number, "x",
            listOf(InvoiceItem("Práce", BigDecimal("2"), "h", BigDecimal(price), vatRate = VatRate.STANDARD)), external,
        )

    private fun image(name: String, format: String): Path {
        val img = java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB)
        return dir.resolve("src").also { java.nio.file.Files.createDirectories(it) }.resolve(name)
            .also { javax.imageio.ImageIO.write(img, format, it.toFile()) }
    }

    @Test fun `signature image is copied into the data folder, replaced and removed`() {
        assertNull(repo.signatureImage())
        repo.setSignatureImage(image("razitko.PNG", "png"))
        assertEquals(dir.resolve("signature.png"), repo.signatureImage())

        repo.setSignatureImage(image("podpis.jpg", "jpg"))
        assertEquals(dir.resolve("signature.jpg"), repo.signatureImage())
        assertFalse(java.nio.file.Files.exists(dir.resolve("signature.png")))

        repo.removeSignatureImage()
        assertNull(repo.signatureImage())
    }

    @Test fun `signature must be a png or jpeg image`() {
        val notImage = dir.resolve("fake.png").also { java.nio.file.Files.writeString(it, "not an image") }
        assertFailsWith<RepositoryException> { repo.setSignatureImage(notImage) }
        assertFailsWith<RepositoryException> { repo.setSignatureImage(image("x.gif", "gif")) }
        assertNull(repo.signatureImage())
    }

    @Test fun `issued invoice stored by issue year`() {
        repo.saveIssued(inv("2700001", LocalDate.of(2027, 1, 2), LocalDate.of(2026, 12, 31)))
        assertEquals(1, repo.issued(2027).size)
        assertEquals(0, repo.issued(2026).size)
    }

    @Test fun `duplicate number refused`() {
        repo.saveIssued(inv("2600023"))
        assertFailsWith<RepositoryException> { repo.saveIssued(inv("2600023")) }
    }

    @Test fun `only last own invoice can be deleted`() {
        repo.saveIssued(inv("2600023"))
        repo.saveIssued(inv("2600024"))
        assertFailsWith<RepositoryException> { repo.deleteIssued(2026, "2600023") }
        repo.deleteIssued(2026, "2600024")
        assertEquals(listOf("2600023"), repo.issued(2026).map { it.number })
    }

    @Test fun `external invoice can be deleted any time`() {
        repo.saveIssued(inv("2600010", external = true))
        repo.saveIssued(inv("2600023"))
        repo.deleteIssued(2026, "2600010")
        assertEquals(listOf("2600023"), repo.issued(2026).map { it.number })
    }

    @Test fun `deleting the only issued invoice leaves a readable empty file`() {
        repo.saveIssued(inv("2600023"))
        repo.deleteIssued(2026, "2600023")
        assertEquals(0, repo.issued(2026).size)
    }

    @Test fun `received invoice stored by claim period year`() {
        val r = ReceivedInvoice(
            "r1", "Dodavatel", "CZ1", "F1", LocalDate.of(2022, 12, 11), Quarter(2026, 2),
            mapOf(VatRate.STANDARD to VatAmount(BigDecimal("100.00"), BigDecimal("21.00"))),
        )
        repo.saveReceived(r)
        assertEquals(listOf(r), repo.received(2026))
        assertEquals(0, repo.received(2022).size)
        repo.deleteReceived(2026, "r1")
        assertEquals(0, repo.received(2026).size)
    }

    @Test fun `edit keeps number`() {
        repo.saveIssued(inv("2600023"))
        val changed = inv("2600023", price = "200.00")
        repo.saveIssued(changed, replacing = "2600023")
        assertEquals(listOf(changed), repo.issued(2026))
    }

    @Test fun `edit moving issue date to another year moves the invoice`() {
        repo.saveIssued(inv("2600023"))
        repo.saveIssued(inv("2700001", LocalDate.of(2027, 1, 3)), replacing = "2600023")
        assertEquals(0, repo.issued(2026).size)
        assertEquals(1, repo.issued(2027).size)
    }

    private fun corrupt(year: Int, file: String) {
        val d = dir.resolve(year.toString()); java.nio.file.Files.createDirectories(d)
        d.resolve(file).toFile().writeText("<not xml")
    }

    @Test fun `failed move of issued invoice keeps it in old year`() {
        repo.saveIssued(inv("2600023"))
        corrupt(2027, "issued.xml")
        assertFailsWith<StorageException> { repo.saveIssued(inv("2700001", LocalDate.of(2027, 1, 3)), replacing = "2600023") }
        assertEquals(1, repo.issued(2026).size)
    }

    @Test fun `failed move of received invoice keeps it in old year`() {
        val r = ReceivedInvoice("r1", "D", null, "F1", LocalDate.of(2026, 1, 1), Quarter(2026, 1),
            mapOf(VatRate.STANDARD to VatAmount(BigDecimal("100.00"), BigDecimal("21.00"))))
        repo.saveReceived(r)
        corrupt(2027, "received.xml")
        assertFailsWith<StorageException> { repo.saveReceived(r.copy(claimPeriod = Quarter(2027, 1))) }
        assertEquals(listOf(r), repo.received(2026))
    }

    @Test fun `move onto existing number in new year refused`() {
        repo.saveIssued(inv("2700001", LocalDate.of(2027, 1, 2)))
        repo.saveIssued(inv("2600023"))
        assertFailsWith<RepositoryException> { repo.saveIssued(inv("2700001", LocalDate.of(2027, 1, 3)), replacing = "2600023") }
        assertEquals(1, repo.issued(2026).size)
        assertEquals(1, repo.issued(2027).size)
    }

    @Test fun `own numbers follow the configured pattern and old ones stay editable after a change`() {
        repo.saveConfig(seller)
        repo.saveIssued(inv("2600023"))
        repo.saveConfig(seller.copy(numberFormat = "FV-{RRRR}-{NNN}"))
        assertFailsWith<RepositoryException> { repo.saveIssued(inv("2600024")) }
        val next = repo.nextNumber(LocalDate.of(2026, 11, 2))
        assertEquals("FV-2026-024", next)
        repo.saveIssued(inv(next))
        // The invoice numbered under the old pattern can still be edited in place.
        repo.saveIssued(inv("2600023", price = "200.00"), replacing = "2600023")
        assertEquals(BigDecimal("200.00"), repo.issued(2026).single { it.number == "2600023" }.items.single().unitPrice)
        // Only the highest sequence may be deleted, whatever pattern it was numbered with.
        assertFailsWith<RepositoryException> { repo.deleteIssued(2026, "2600023") }
        repo.deleteIssued(2026, "FV-2026-024")
    }

    @Test fun `external invoice with foreign number accepted and numbering still works`() {
        repo.saveConfig(seller)
        repo.saveIssued(inv("FV-2026/7", external = true))
        assertEquals(1, repo.issued(2026).size)
        assertEquals("2600023", repo.nextNumber(java.time.LocalDate.of(2026, 11, 2)))
        assertFailsWith<RepositoryException> { repo.saveIssued(inv("FV-2026/7", external = true)) }
    }

    @Test fun `moving external invoice onto same number in new year refused`() {
        repo.saveIssued(inv("FV-1", LocalDate.of(2026, 5, 1), external = true))
        repo.saveIssued(inv("FV-1", LocalDate.of(2027, 5, 1), external = true))
        assertFailsWith<RepositoryException> {
            repo.saveIssued(inv("FV-1", LocalDate.of(2027, 6, 1), external = true), replacing = "FV-1")
        }
        assertEquals(1, repo.issued(2026).size)
        assertEquals(LocalDate.of(2027, 5, 1), repo.issued(2027).single().issueDate)
    }

    @Test fun `pdf path is sanitised`() {
        assertEquals(dir.resolve("2026/pdf/FV-2026_7.pdf"), repo.pdfPath(inv("FV-2026/7", external = true)))
        val p = repo.pdfPath(inv("../x", external = true))
        assertEquals(dir.resolve("2026/pdf"), p.parent)
    }

    @Test fun `malformed number refused`() {
        assertFailsWith<RepositoryException> { repo.saveIssued(inv("26-23")) }
        assertFailsWith<RepositoryException> { repo.saveIssued(inv("2700001")) }
    }

    @Test fun `files survive reload`() {
        val r = repo
        r.saveConfig(seller)
        r.saveContact(customer)
        r.saveContact(customer.copy(name = "Nový"))
        r.saveIssued(inv("2600023"))
        val template = Template("t1", "T", "c1", null, null, listOf(TemplateItem("P", "h", BigDecimal("1.00"), VatRate.STANDARD, null)))
        r.saveTemplate(template)
        val again = DataRepository(dir)
        assertEquals(seller, again.loadConfig())
        assertEquals(listOf(customer.copy(name = "Nový")), again.contacts())
        assertEquals(listOf(inv("2600023")), again.issued(2026))
        assertEquals(listOf(template), again.templates())
        again.deleteTemplate("t1"); again.deleteContact("c1")
        assertTrue(again.templates().isEmpty() && again.contacts().isEmpty())
    }

    @Test fun `next number uses config`() {
        repo.saveConfig(seller)
        assertEquals("2600023", repo.nextNumber(java.time.LocalDate.of(2026, 11, 2)))
        repo.saveIssued(inv("2600023"))
        assertEquals("2600024", repo.nextNumber(java.time.LocalDate.of(2026, 11, 2)))
    }

    @Test fun `paths`() {
        assertEquals(dir.resolve("2026/pdf/2600023.pdf"), repo.pdfPath(inv("2600023")))
        assertEquals(dir.resolve("2026/export/Q3"), repo.exportDir(Quarter(2026, 3)))
        assertTrue(java.nio.file.Files.isDirectory(repo.receivedDir(2026)))
    }

    @Test fun `app settings persist data dir`() {
        val s = AppSettings(dir)
        assertNull(s.dataDir)
        s.dataDir = dir.resolve("data")
        assertEquals(dir.resolve("data"), AppSettings(dir).dataDir)
    }
}
