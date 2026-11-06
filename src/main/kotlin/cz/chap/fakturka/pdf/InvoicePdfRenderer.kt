package cz.chap.fakturka.pdf

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.openhtmltopdf.util.XRLog
import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.domain.SellerConfig
import cz.chap.fakturka.storage.AtomicFileWriter
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.logging.Level
import java.util.logging.Logger

object InvoicePdfRenderer {
    private const val FONT_FAMILY = "Noto Sans"

    /*
     * openhtmltopdf creates the PDF standard-14 fonts on every render. PDFBox finds no "Symbol" font on a typical
     * Linux system and warns that it falls back to LiberationSans; the invoice never uses Symbol, so that warning
     * is noise. The logger is kept in a field because java.util.logging holds loggers only weakly.
     */
    private val pdfBoxType1Logger: Logger = Logger.getLogger("org.apache.pdfbox.pdmodel.font.PDType1Font")

    init {
        pdfBoxType1Logger.level = Level.SEVERE
        XRLog.listRegisteredLoggers().forEach { XRLog.setLevel(it, Level.WARNING) }
    }

    /** [signature] is a PNG or JPEG placed above the signature line; null leaves the line empty. */
    fun render(invoice: IssuedInvoice, seller: SellerConfig, target: Path, signature: Path? = null) {
        val html = InvoiceHtml.render(invoice, seller, signature?.let(::dataUri))
        val pdf = ByteArrayOutputStream()
        PdfRendererBuilder()
            .useFont({ font("NotoSans-Regular.ttf") }, FONT_FAMILY, 400, FontStyle.NORMAL, true)
            .useFont({ font("NotoSans-Bold.ttf") }, FONT_FAMILY, 700, FontStyle.NORMAL, true)
            .withHtmlContent(html, null)
            .toStream(pdf)
            .run()
        AtomicFileWriter.write(target, pdf.toByteArray())
    }

    private fun dataUri(image: Path): String {
        val mime = if (image.fileName.toString().lowercase().endsWith(".png")) "image/png" else "image/jpeg"
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(image))
    }

    private fun font(name: String) =
        requireNotNull(InvoicePdfRenderer::class.java.getResourceAsStream("/fonts/$name")) { "fonts/$name missing" }
}
