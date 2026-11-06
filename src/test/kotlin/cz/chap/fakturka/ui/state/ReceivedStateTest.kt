package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.*
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.DataRepository
import cz.chap.fakturka.tax.ExportService
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class ReceivedStateTest {
    @TempDir
    lateinit var dir: Path

    private val zone = ZoneId.of("Europe/Prague")
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 11, 2, 10, 0, 0, 0, zone).toInstant(), zone)
    private val repo by lazy { DataRepository(dir) }
    private val state by lazy { ReceivedState(repo, ExportService(repo, clock), clock) }

    private fun received(
        taxDate: LocalDate = LocalDate.of(2026, 2, 10),
        claim: Quarter = Quarter(2026, 1),
        dic: String? = "CZ11111111",
        number: String = "FV-1",
        base: String = "1000.00",
    ) = ReceivedInvoice(
        "r1", "Dodavatel s.r.o.", dic, number, taxDate, claim,
        mapOf(VatRate.STANDARD to VatAmount(BigDecimal(base), BigDecimal(base).multiply(BigDecimal("0.21")).money())),
    )

    private fun draft(block: ReceivedDraft.() -> ReceivedDraft = { this }) =
        ReceivedDraft.new(LocalDate.of(2026, 2, 10)).copy(supplierName = "Dodavatel s.r.o.", documentNumber = "FV-1")
            .withBase(VatRate.STANDARD, "1000").block()

    private fun markExported(q: Quarter) {
        val d = repo.exportDir(q)
        Files.createDirectories(d)
        Files.writeString(d.resolve("KH-${q.year}-Q${q.quarter}.xml"), "<x/>")
        Files.writeString(d.resolve("DP-${q.year}-Q${q.quarter}.xml"), "<x/>")
    }

    @Test fun `claim period defaults to quarter of DUZP`() {
        val d = ReceivedDraft.new(LocalDate.of(2026, 5, 3)).withTaxDate("17.8.2026")
        assertEquals("2026", d.claimYear)
        assertEquals(3, d.claimQuarter)
        // once the user picks the claim period it no longer follows the DUZP
        val edited = d.withClaim("2027", 1).withTaxDate("1.2.2026")
        assertEquals("2027", edited.claimYear)
        assertEquals(1, edited.claimQuarter)
    }

    @Test fun `claim period cannot be before DUZP quarter`() {
        val r = received(taxDate = LocalDate.of(2026, 5, 1), claim = Quarter(2026, 1))
        assertEquals(listOf(Messages.get("ui.received.error.claimBeforeTaxDate")), state.validate(r))
        assertEquals(listOf(Messages.get("ui.received.error.claimBeforeTaxDate")), draft { withTaxDate("1.5.2026").withClaim("2026", 1) }.validate())
        assertEquals(emptyList(), state.validate(received(taxDate = LocalDate.of(2026, 5, 1), claim = Quarter(2026, 2))))
    }

    @Test fun `late claim of an older invoice is allowed`() {
        assertEquals(emptyList(), state.validate(received(taxDate = LocalDate.of(2022, 12, 11), claim = Quarter(2026, 2))))
    }

    @Test fun `over 10k needs supplier DIC and document number`() {
        val big = received(dic = null, number = "", base = "10000.00") // total 12100.00
        val expected = setOf(Messages.get("ui.received.error.dicRequired"), Messages.get("ui.received.error.numberRequired"))
        assertEquals(expected, state.validate(big).toSet())
        assertEquals(emptyList(), state.validate(big.copy(supplierDic = "CZ11111111", documentNumber = "FV-9")))
    }

    @Test fun `exactly 10000 incl VAT needs neither DIC nor number`() {
        val r = ReceivedInvoice(
            "r1", "X", null, "", LocalDate.of(2026, 2, 10), Quarter(2026, 1),
            mapOf(VatRate.STANDARD to VatAmount(BigDecimal("8264.46"), BigDecimal("1735.54"))),
        )
        assertEquals(BigDecimal("10000.00"), r.total)
        assertEquals(emptyList(), state.validate(r))
    }

    @Test fun `stored in claim year`() {
        state.save(received(taxDate = LocalDate.of(2025, 12, 20), claim = Quarter(2026, 1)))
        assertEquals(listOf("r1"), repo.received(2026).map { it.id })
        assertTrue(repo.received(2025).isEmpty())
        state.year = 2026
        assertEquals(1, state.invoices.size)
    }

    @Test fun `VAT is prefilled from base until the user edits it`() {
        var d = ReceivedDraft.new(LocalDate.of(2026, 2, 10)).withBase(VatRate.STANDARD, "1 000,00")
        assertEquals("210.00", d.amount(VatRate.STANDARD).vatText)
        d = d.withBase(VatRate.REDUCED, "100")
        assertEquals("12.00", d.amount(VatRate.REDUCED).vatText)
        d = d.withVat(VatRate.STANDARD, "210.01").withBase(VatRate.STANDARD, "9000")
        assertEquals("210.01", d.amount(VatRate.STANDARD).vatText)
    }

    @Test fun `draft converts to invoice with typed values`() {
        val d = draft { copy(supplierDic = "CZ11111111").withBase(VatRate.STANDARD, "1 000,00").withVat(VatRate.STANDARD, "210,01") }
        assertEquals(emptyList(), d.validate())
        val r = d.toInvoice()
        assertEquals(BigDecimal("1000.00"), r.amounts.getValue(VatRate.STANDARD).base)
        assertEquals(BigDecimal("210.01"), r.amounts.getValue(VatRate.STANDARD).vat)
        assertEquals("CZ11111111", r.supplierDic)
        assertFalse(VatRate.REDUCED in r.amounts)
    }

    @Test fun `more than two decimals rejected`() {
        assertEquals(listOf(Messages.get("ui.received.error.amount")), draft { withBase(VatRate.STANDARD, "100.005") }.validate())
        assertEquals(listOf(Messages.get("ui.received.error.amount")), draft { withVat(VatRate.STANDARD, "1,234") }.validate())
    }

    @Test fun `at least one non-zero base`() {
        val d = ReceivedDraft.new(LocalDate.of(2026, 2, 10)).copy(supplierName = "X")
        assertEquals(listOf(Messages.get("ui.received.error.noAmount")), d.validate())
        assertEquals(listOf(Messages.get("ui.received.error.noAmount")), d.withBase(VatRate.STANDARD, "0").validate())
    }

    @Test fun `invalid date and claim year reported`() {
        val errors = draft { withTaxDate("31.2.2026").withClaim("abc", 5) }.validate()
        assertTrue(Messages.get("ui.received.error.taxDate") in errors)
        assertTrue(Messages.get("ui.received.error.claim") in errors)
    }

    @Test fun `supplier name is required`() {
        assertEquals(listOf(Messages.get("ui.received.error.supplier")), draft { copy(supplierName = " ") }.validate())
    }

    @Test fun `save warns when claim quarter is already exported`() {
        markExported(Quarter(2026, 1))
        assertEquals(Quarter(2026, 1), state.save(received()))
        assertNull(state.save(received(taxDate = LocalDate.of(2026, 5, 1), claim = Quarter(2026, 2)).copy(id = "r2")))
    }

    @Test fun `moving an exported invoice away also warns about its old quarter`() {
        state.save(received())
        markExported(Quarter(2026, 1))
        assertEquals(Quarter(2026, 1), state.save(received(claim = Quarter(2026, 2))))
    }

    @Test fun `delete returns stale quarter and removes`() {
        state.save(received())
        state.year = 2026
        markExported(Quarter(2026, 1))
        assertEquals(Quarter(2026, 1), state.delete("r1"))
        assertTrue(state.invoices.isEmpty())
    }

    @Test fun `contact pick fills supplier name and DIC`() {
        val c = Contact("c1", "Alfa", Address("U", "1", "P", "11000", "CZ"), "87654321", "CZ87654321", null, null, ContactType.DOMESTIC)
        val d = ReceivedDraft.new(LocalDate.of(2026, 2, 10)).withSupplier(c)
        assertEquals("Alfa", d.supplierName)
        assertEquals("CZ87654321", d.supplierDic)
    }

    @Test fun `edit draft round trips`() {
        val r = received()
        val back = ReceivedDraft.from(r).toInvoice()
        assertEquals(r, back)
    }

    @Test fun `invoices sorted by DUZP descending`() {
        state.save(received().copy(id = "a", taxDate = LocalDate.of(2026, 1, 5)))
        state.save(received().copy(id = "b", taxDate = LocalDate.of(2026, 3, 5)))
        state.year = 2026
        assertEquals(listOf("b", "a"), state.invoices.map { it.id })
    }

    @Test fun `year stays unchanged when loading the new year fails`() {
        state.save(received())
        state.year = 2026
        Files.createDirectories(dir.resolve("2025"))
        Files.writeString(dir.resolve("2025").resolve("received.xml"), "<not xml")
        assertFails { state.year = 2025 }
        assertEquals(2026, state.year)
        assertEquals(listOf("r1"), state.invoices.map { it.id })
    }

    @Test fun `clearing VAT keeps the field blank and the prefill resumes on the next base change`() {
        val d = ReceivedDraft.new(LocalDate.of(2026, 2, 10)).withBase(VatRate.STANDARD, "100").withVat(VatRate.STANDARD, "20")
        val cleared = d.withVat(VatRate.STANDARD, "")
        assertEquals("", cleared.amount(VatRate.STANDARD).vatText)
        assertFalse(cleared.amount(VatRate.STANDARD).vatEdited)
        assertEquals("42.00", cleared.withBase(VatRate.STANDARD, "200").amount(VatRate.STANDARD).vatText)
        // Clearing a prefilled VAT (never typed) also stays blank until the base changes.
        val prefilledCleared = ReceivedDraft.new(LocalDate.of(2026, 2, 10)).withBase(VatRate.STANDARD, "100").withVat(VatRate.STANDARD, " ")
        assertEquals(" ", prefilledCleared.amount(VatRate.STANDARD).vatText)
        assertEquals("21.00", prefilledCleared.withBase(VatRate.STANDARD, "100.00").amount(VatRate.STANDARD).vatText)
    }

    @Test fun `saving into another claim year switches the list to that year`() {
        assertEquals(2026, state.year)
        state.save(received(taxDate = LocalDate.of(2026, 12, 10), claim = Quarter(2027, 1)))
        assertEquals(2027, state.year)
        assertEquals(listOf("r1"), state.invoices.map { it.id })
    }
}
