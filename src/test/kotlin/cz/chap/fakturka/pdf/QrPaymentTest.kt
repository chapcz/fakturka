package cz.chap.fakturka.pdf

import com.google.zxing.BinaryBitmap
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import cz.chap.fakturka.domain.VatRate
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrPaymentTest {
    @Test
    fun `spd string for invoice 2600022`() {
        assertEquals(
            "SPD*1.0*ACC:CZ6508000000192000145399*AM:22385.00*CC:CZK*DT:20261118*X-VS:2600022*X-KS:0308",
            QrPayment.spd(PdfFixtures.invoice2600022(), PdfFixtures.seller),
        )
    }

    @Test
    fun `EU invoice is paid without VAT even when an item carries 21 percent`() {
        val eu = PdfFixtures.invoice2600022(PdfFixtures.euCustomer)
        val invoice = eu.copy(items = eu.items.map { it.copy(vatRate = VatRate.STANDARD) })
        assertTrue(QrPayment.spd(invoice, PdfFixtures.seller).contains("*AM:18500.00*"))
    }

    @Test
    fun `png decodes back to the same text`() {
        val text = QrPayment.spd(PdfFixtures.invoice2600022(), PdfFixtures.seller)
        val image = ImageIO.read(ByteArrayInputStream(QrPayment.png(text)))
        val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))
        assertEquals(text, QRCodeReader().decode(bitmap).text)
    }
}
