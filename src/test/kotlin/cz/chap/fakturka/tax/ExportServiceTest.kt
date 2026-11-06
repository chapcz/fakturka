package cz.chap.fakturka.tax

import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.domain.*
import cz.chap.fakturka.storage.DataRepository
import cz.chap.fakturka.storage.IssuedFile
import cz.chap.fakturka.storage.XmlStore
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class ExportServiceTest {
    @TempDir
    lateinit var dir: Path

    private val zone = ZoneId.of("Europe/Prague")
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 11, 6, 10, 0, 0, 0, zone).toInstant(), zone)
    private val q3 = Quarter(2026, 3)
    private val address = Address("Testovací", "1", "Praha", "11000", "CZ")
    private val seller = SellerConfig(
        "Test Dodavatel", "Test", "Dodavatel", "", address, "12345678", "CZ12345678", "test@example.com", null, null,
        "Banka", "1/0100", "CZ00", "SWIFT", "461", "2509", "62020",
    )

    private val repo by lazy {
        DataRepository(dir).also { r ->
            r.saveConfig(seller)
            val fixture = Path.of(javaClass.getResource("/fixtures/q3-2026-issued.xml")!!.toURI())
            XmlStore().read(fixture, IssuedFile::class)!!.invoices.forEach { r.saveIssued(it) }
        }
    }

    private fun euInvoice(number: String, dic: String?) = IssuedInvoice(
        number, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 4),
        Contact("eu", "Kunde GmbH", Address("Straße", "5", "Berlin", "10115", "DE"), null, dic, null, null, ContactType.EU),
        null, "0308", number, "x", listOf(InvoiceItem("Práce", BigDecimal.ONE, "ks", BigDecimal("72000.00"), vatRate = VatRate.ZERO)),
    )

    private fun exportFiles(): List<String> {
        val d = repo.exportDir(q3)
        return if (d.exists()) d.listDirectoryEntries().map { it.name }.sorted() else emptyList()
    }

    @Test fun `writes KH and DP, SH only with EU invoices`() {
        val result = ExportService(repo, clock).export(q3)
        assertEquals(emptyList(), result.errors)
        assertEquals(listOf("KH-2026-Q3.xml", "DP-2026-Q3.xml"), result.files.map { it.name })
        assertEquals(listOf("DP-2026-Q3.xml", "KH-2026-Q3.xml"), exportFiles())

        repo.saveIssued(euInvoice("2600030", "DE123456789"))
        val withEu = ExportService(repo, clock).export(q3)
        assertEquals(emptyList(), withEu.errors)
        assertEquals(listOf("KH-2026-Q3.xml", "DP-2026-Q3.xml", "SH-2026-Q3.xml"), withEu.files.map { it.name })
    }

    @Test fun `stale SH is moved to bak when the quarter has no EU invoice any more`() {
        repo.saveIssued(euInvoice("2600030", "DE123456789"))
        ExportService(repo, clock).export(q3)
        val dirOf = repo.exportDir(q3)
        val former = (dirOf / "SH-2026-Q3.xml").readText()
        (dirOf / "SH-2026-Q3.xml.bak").toFile().writeText("older backup")
        repo.deleteIssued(2026, "2600030")
        val result = ExportService(repo, clock).export(q3)
        assertEquals(emptyList(), result.errors)
        assertFalse((dirOf / "SH-2026-Q3.xml").exists())
        assertEquals(former, (dirOf / "SH-2026-Q3.xml.bak").readText())
    }

    @Test fun `failing stale SH move still returns the written files plus an error`() {
        repo.saveIssued(euInvoice("2600030", "DE123456789"))
        ExportService(repo, clock).export(q3)
        repo.deleteIssued(2026, "2600030")
        val bak = Files.createDirectories(repo.exportDir(q3).resolve("SH-2026-Q3.xml.bak"))
        Files.writeString(bak.resolve("blocker"), "x")
        val result = ExportService(repo, clock).export(q3)
        assertEquals(listOf("KH-2026-Q3.xml", "DP-2026-Q3.xml"), result.files.map { it.name })
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().startsWith("SH: "), result.errors.toString())
    }

    @Test fun `stale SH is kept when the new export fails`() {
        repo.saveIssued(euInvoice("2600030", "DE123456789"))
        ExportService(repo, clock).export(q3)
        repo.deleteIssued(2026, "2600030")
        val invalid = FormBuilder { form, s, data, date ->
            val e = defaultFormBuilder.build(form, s, data, date)
            if (form == EpoForm.DP) e!!.copy(children = e.children.filter { it.name != "VetaP" }) else e
        }
        assertTrue(ExportService(repo, clock, invalid).export(q3).errors.isNotEmpty())
        assertTrue("SH-2026-Q3.xml" in exportFiles())
    }

    @Test fun `missing tax office code is a plain error and writes nothing`() {
        repo.saveConfig(seller.copy(taxOfficeCode = ""))
        val result = ExportService(repo, clock).export(q3)
        assertEquals(listOf(Messages.get("ui.vat.error.taxOfficeCode")), result.errors)
        assertEquals(emptyList(), exportFiles())
    }

    @Test fun `blank tax office branch is omitted and the export stays valid`() {
        repo.saveConfig(seller.copy(taxOfficeBranch = ""))
        val result = ExportService(repo, clock).export(q3)
        assertEquals(emptyList(), result.errors)
        assertTrue(result.files.none { "c_pracufo" in it.readText() })
    }

    @Test fun `filing date comes from the clock`() {
        val files = ExportService(repo, clock).export(q3).files
        assertTrue("d_poddp=\"6.11.2026\"" in files[0].readText())
        assertTrue("d_poddp=\"06.11.2026\"" in files[1].readText())
    }

    @Test fun `invalid export writes nothing`() {
        val invalid = FormBuilder { form, s, data, date ->
            val e = defaultFormBuilder.build(form, s, data, date)
            if (form == EpoForm.DP) e!!.copy(children = e.children.filter { it.name != "VetaP" }) else e
        }
        val result = ExportService(repo, clock, invalid).export(q3)
        assertTrue(result.errors.isNotEmpty())
        assertTrue(result.errors.all { it.startsWith("DP: ") }, result.errors.toString())
        assertEquals(emptyList(), result.files)
        assertEquals(emptyList(), exportFiles())
    }

    @Test fun `EU customer without VAT ID is an error, not a crash`() {
        repo.saveIssued(euInvoice("2600030", null))
        val result = ExportService(repo, clock).export(q3)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().startsWith("SH: ") && "2600030" in result.errors.single(), result.errors.toString())
        assertEquals(emptyList(), exportFiles())
    }

    @Test fun `isExported true after export`() {
        val service = ExportService(repo, clock)
        assertFalse(service.isExported(q3))
        service.export(q3)
        assertTrue(service.isExported(q3))
        assertFalse(service.isExported(Quarter(2026, 4)))
    }

    @Test fun `summary of Q3`() {
        repo.saveIssued(euInvoice("2600030", "DE123456789"))
        val r = ReceivedInvoice(
            "r1", "Dodavatel", "CZ99999999", "FV-1", LocalDate.of(2026, 8, 2), q3,
            mapOf(VatRate.STANDARD to VatAmount(BigDecimal("20000.00"), BigDecimal("4200.00"))),
        )
        repo.saveReceived(r)
        repo.saveReceived(r.copy(id = "r2", documentNumber = "FV-2", amounts = mapOf(VatRate.STANDARD to VatAmount(BigDecimal("100.00"), BigDecimal("21.00")))))
        val s = ExportService(repo, clock).summary(q3)
        // output VAT: 2520 + 1890 + 2100 + 1978.20 + 1911 + 882 = 11281.20 -> 11281
        assertEquals(
            QuarterSummary(
                a4Count = 5, a5Base = BigDecimal("4200.00"), b2Count = 1, b3Base = BigDecimal("100.00"),
                outputVat = BigDecimal("11281"), inputVat = BigDecimal("4221"), toPay = BigDecimal("7060"), euCount = 1,
            ),
            s,
        )
    }

    @Test fun `excess deduction gives negative toPay`() {
        repo.saveReceived(
            ReceivedInvoice(
                "r1", "Dodavatel", "CZ99999999", "FV-1", LocalDate.of(2026, 8, 2), Quarter(2026, 4),
                mapOf(VatRate.STANDARD to VatAmount(BigDecimal("1000.00"), BigDecimal("210.00"))),
            ),
        )
        assertEquals(BigDecimal("-210"), ExportService(repo, clock).summary(Quarter(2026, 4)).toPay)
    }

    @Test fun `no export without seller config`() {
        assertFailsWith<RuntimeException> { ExportService(DataRepository(Files.createDirectories(dir / "empty")), clock).export(q3) }
    }

    private operator fun Path.div(other: String): Path = resolve(other)
}
