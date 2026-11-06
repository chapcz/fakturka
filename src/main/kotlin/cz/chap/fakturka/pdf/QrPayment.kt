package cz.chap.fakturka.pdf

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.domain.SellerConfig
import cz.chap.fakturka.domain.totals
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter

/** Czech "QR Platba" (Short Payment Descriptor, SPD 1.0). */
object QrPayment {
    private val SPD_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    fun spd(invoice: IssuedInvoice, seller: SellerConfig): String = listOf(
        "SPD",
        "1.0",
        "ACC:${seller.iban.filterNot(Char::isWhitespace).uppercase()}",
        "AM:${invoice.asPrinted().totals.total.toPlainString()}",
        "CC:CZK",
        "DT:${invoice.dueDate.format(SPD_DATE)}",
        "X-VS:${invoice.variableSymbol}",
        "X-KS:${invoice.constantSymbol}",
    ).joinToString("*")

    fun png(text: String, sizePx: Int = 300): ByteArray {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        return ByteArrayOutputStream().also { MatrixToImageWriter.writeToStream(matrix, "PNG", it) }.toByteArray()
    }
}
