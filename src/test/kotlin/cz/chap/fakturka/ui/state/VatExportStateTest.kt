package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.*
import cz.chap.fakturka.storage.DataRepository
import cz.chap.fakturka.storage.IssuedFile
import cz.chap.fakturka.storage.XmlStore
import cz.chap.fakturka.tax.EpoForm
import cz.chap.fakturka.tax.ExportService
import cz.chap.fakturka.tax.FormBuilder
import cz.chap.fakturka.tax.defaultFormBuilder
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class VatExportStateTest {
    @TempDir
    lateinit var dir: Path

    private val zone = ZoneId.of("Europe/Prague")
    private fun clockOn(y: Int, m: Int, d: Int) = Clock.fixed(ZonedDateTime.of(y, m, d, 10, 0, 0, 0, zone).toInstant(), zone)
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

    private fun state(y: Int = 2026, m: Int = 11, d: Int = 6): VatExportState {
        val clock = clockOn(y, m, d)
        return VatExportState(ExportService(repo, clock), clock)
    }

    @Test fun `default quarter follows the 25th day deadline`() {
        assertEquals(Quarter(2026, 3), defaultQuarter(LocalDate.of(2026, 10, 25)))
        assertEquals(Quarter(2026, 4), defaultQuarter(LocalDate.of(2026, 10, 26)))
        assertEquals(Quarter(2026, 4), defaultQuarter(LocalDate.of(2026, 11, 6)))
        assertEquals(Quarter(2026, 4), defaultQuarter(LocalDate.of(2027, 1, 10)))
        assertEquals(Quarter(2027, 1), defaultQuarter(LocalDate.of(2027, 1, 26)))
        assertEquals(Quarter(2025, 4), defaultQuarter(LocalDate.of(2026, 1, 25)))
    }

    @Test fun `state starts on the default quarter`() {
        assertEquals(Quarter(2026, 4), state().quarter)
        assertEquals(Quarter(2026, 3), state(2026, 10, 20).quarter)
    }

    @Test fun `summary for Q3 fixture`() {
        val s = state()
        s.quarter = Quarter(2026, 3)
        assertEquals(5, s.summary.a4Count)
        assertEquals(BigDecimal("4200.00"), s.summary.a5Base)
        assertEquals(s.summary.outputVat - s.summary.inputVat, s.summary.toPay)
    }

    @Test fun `export lists created files and marks the quarter exported`() {
        val s = state()
        s.quarter = Quarter(2026, 3)
        assertFalse(s.alreadyExported)
        val result = s.export()
        assertEquals(emptyList(), result.errors)
        assertEquals(listOf("KH-2026-Q3.xml", "DP-2026-Q3.xml"), result.files.map { it.fileName.toString() })
        assertTrue(s.alreadyExported)
        s.quarter = Quarter(2026, 4)
        assertFalse(s.alreadyExported)
    }

    @Test fun `failed quarter change keeps the previous quarter`() {
        val s = state()
        s.quarter = Quarter(2026, 3)
        s.export()
        val summary = s.summary
        java.nio.file.Files.delete(dir.resolve("config.xml"))
        assertFailsWith<RuntimeException> { s.quarter = Quarter(2026, 2) }
        assertEquals(Quarter(2026, 3), s.quarter)
        assertEquals(summary, s.summary)
        assertTrue(s.alreadyExported)
    }

    @Test fun `export with validator errors creates nothing`() {
        val clock = clockOn(2026, 11, 6)
        val invalid = FormBuilder { form, sel, data, date ->
            val e = defaultFormBuilder.build(form, sel, data, date)
            if (form == EpoForm.DP) e!!.copy(children = e.children.filter { it.name != "VetaP" }) else e
        }
        val s = VatExportState(ExportService(repo, clock, invalid), clock)
        s.quarter = Quarter(2026, 3)
        val result = s.export()
        assertTrue(result.errors.isNotEmpty())
        assertEquals(emptyList(), result.files)
        assertFalse(s.alreadyExported)
    }
}
