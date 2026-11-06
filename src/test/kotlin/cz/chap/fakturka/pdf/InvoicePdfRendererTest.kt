package cz.chap.fakturka.pdf

import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.domain.VatRate
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class InvoicePdfRendererTest {
    @TempDir
    lateinit var dir: Path

    private fun renderText(invoice: IssuedInvoice): String {
        val target = dir.resolve("${invoice.number}.pdf")
        InvoicePdfRenderer.render(invoice, PdfFixtures.seller, target)
        return Loader.loadPDF(target.toFile()).use { PDFTextStripper().getText(it) }
    }

    private fun assertContainsAll(text: String, vararg expected: String) =
        expected.forEach { assertTrue(text.contains(it), "missing \"$it\" in:\n$text") }

    @Test
    fun `domestic invoice contains`() {
        assertContainsAll(
            renderText(PdfFixtures.invoice2600022()),
            "FAKTURA - daňový doklad", "2600022", "DODAVATEL", "ODBĚRATEL", "ADRESA DODÁNÍ",
            "Programátorské práce", "18 500.00", "3 885.00", "22 385.00 CZK", "18.11.2026",
            "razítko a podpis", "Fakturka",
        )
    }

    @Test
    fun `czech diacritics survive`() {
        val customer = PdfFixtures.domesticCustomer.copy(name = "Řeřicha s.r.o.")
        val text = renderText(PdfFixtures.invoice2600022(customer))
        assertContainsAll(text, "Peněžní ústav", "Řeřicha s.r.o.")
    }

    @Test
    fun `EU invoice is bilingual`() {
        val text = renderText(PdfFixtures.invoice2600022(PdfFixtures.euCustomer))
        assertContainsAll(text, "Faktura / Invoice", "Odběratel / Customer", "Reverse charge", "DE123456789")
        assertTrue(Regex("""\b0\.00\b""").containsMatchIn(text), "VAT total 0.00 missing:\n$text")
        assertFalse(text.contains("21%") || text.contains("21 %"), "EU invoice must not show 21 %:\n$text")
    }

    @Test
    fun `EU invoice shows no VAT even when an item carries 21 percent`() {
        val eu = PdfFixtures.invoice2600022(PdfFixtures.euCustomer)
        val text = renderText(eu.copy(items = eu.items.map { it.copy(vatRate = VatRate.STANDARD) }))
        assertContainsAll(text, "Reverse charge", "18 500.00 CZK")
        assertFalse(text.contains("21%") || text.contains("21 %"), "EU invoice must not show 21 %:\n$text")
        assertFalse(text.contains("3 885.00") || text.contains("22 385.00"), "EU invoice must not add VAT:\n$text")
    }

    private fun imageCount(pdf: Path): Int = Loader.loadPDF(pdf.toFile()).use { doc ->
        doc.pages.sumOf { page -> page.resources.xObjectNames.count { page.resources.isImageXObject(it) } }
    }

    private fun stamp(): Path {
        val img = java.awt.image.BufferedImage(300, 200, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        img.createGraphics().apply { color = java.awt.Color(30, 60, 160); fillOval(10, 10, 280, 180); dispose() }
        return dir.resolve("signature.png").also { javax.imageio.ImageIO.write(img, "png", it.toFile()) }
    }

    @Test
    fun `stamp image is drawn only when given`() {
        val without = dir.resolve("without.pdf").also { InvoicePdfRenderer.render(PdfFixtures.invoice2600022(), PdfFixtures.seller, it) }
        val with = dir.resolve("with.pdf").also { InvoicePdfRenderer.render(PdfFixtures.invoice2600022(), PdfFixtures.seller, it, stamp()) }
        assertEquals(imageCount(without) + 1, imageCount(with))
    }

    /**
     * Writes preview PDFs to build/preview for the visual comparison with the ProFact original.
     * Opt-in: `./gradlew test --tests '*InvoicePdfRendererTest*' -Pfakturka.preview=true`.
     */
    @Test
    fun `render previews`() {
        assumeTrue(System.getProperty("fakturka.preview") == "true", "preview rendering not requested")
        val out = Path.of("build/preview").createDirectories()
        InvoicePdfRenderer.render(PdfFixtures.invoice2600022(), PdfFixtures.seller, out.resolve("2600022.pdf"))
        InvoicePdfRenderer.render(
            PdfFixtures.invoice2600022(PdfFixtures.euCustomer), PdfFixtures.seller, out.resolve("eu-example.pdf"),
        )
        InvoicePdfRenderer.render(PdfFixtures.invoice2600022(), PdfFixtures.seller, out.resolve("stamp-example.pdf"), stamp())
    }
}
