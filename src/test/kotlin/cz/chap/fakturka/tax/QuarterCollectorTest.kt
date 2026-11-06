package cz.chap.fakturka.tax

import cz.chap.fakturka.domain.*
import cz.chap.fakturka.storage.DataRepository
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class QuarterCollectorTest {
    @TempDir
    lateinit var dir: Path

    private val address = Address("Ulice", "1", "Praha", "11000", "CZ")
    private val customer = Contact("c1", "Zákazník", address, "1", "CZ1", null, null, ContactType.DOMESTIC)
    private val repo get() = DataRepository(dir)

    private fun inv(number: String, issue: LocalDate, tax: LocalDate) =
        IssuedInvoice(
            number, issue, tax, issue.plusDays(14), customer, null, "0308", number, "x",
            listOf(InvoiceItem("Práce", BigDecimal("2"), "h", BigDecimal("100.00"), vatRate = VatRate.STANDARD)),
        )

    @Test fun `january invoice with december DUZP belongs to previous Q4`() {
        repo.saveIssued(inv("2700001", LocalDate.of(2027, 1, 2), LocalDate.of(2026, 12, 31)))
        assertEquals(listOf("2700001"), QuarterCollector(repo).collect(Quarter(2026, 4)).issued.map { it.number })
        assertTrue(QuarterCollector(repo).collect(Quarter(2027, 1)).issued.isEmpty())
    }

    @Test fun `december invoice with january DUZP belongs to next Q1`() {
        repo.saveIssued(inv("2600099", LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2)))
        assertEquals(listOf("2600099"), QuarterCollector(repo).collect(Quarter(2027, 1)).issued.map { it.number })
        assertTrue(QuarterCollector(repo).collect(Quarter(2026, 4)).issued.isEmpty())
    }

    @Test fun `late claimed received invoice`() {
        val r = ReceivedInvoice(
            "r1", "Dodavatel", "CZ1", "F1", LocalDate.of(2022, 12, 11), Quarter(2026, 2),
            mapOf(VatRate.STANDARD to VatAmount(BigDecimal("100.00"), BigDecimal("21.00"))),
        )
        repo.saveReceived(r)
        assertEquals(listOf("r1"), QuarterCollector(repo).collect(Quarter(2026, 2)).received.map { it.id })
        assertTrue(QuarterCollector(repo).collect(Quarter(2026, 3)).received.isEmpty())
    }

    @Test fun `DUZP decides quarter`() {
        repo.saveIssued(inv("2600020", LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1)))
        assertTrue(QuarterCollector(repo).collect(Quarter(2026, 3)).issued.isEmpty())
        assertEquals(1, QuarterCollector(repo).collect(Quarter(2026, 4)).issued.size)
    }

    @Test fun `sorted by tax date then number`() {
        repo.saveIssued(inv("2600003", LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 5)))
        repo.saveIssued(inv("2600002", LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 5)))
        repo.saveIssued(inv("2600001", LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 1)))
        assertEquals(
            listOf("2600001", "2600002", "2600003"),
            QuarterCollector(repo).collect(Quarter(2026, 3)).issued.map { it.number },
        )
    }
}
