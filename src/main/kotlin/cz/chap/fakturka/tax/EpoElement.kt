package cz.chap.fakturka.tax

import cz.chap.fakturka.AppInfo
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

data class EpoElement(
    val name: String,
    val attributes: List<Pair<String, String>>,
    val children: List<EpoElement> = emptyList(),
)

object EpoFormat {
    private val dp = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val kh = DateTimeFormatter.ofPattern("d.M.yyyy")

    fun amount(v: BigDecimal): String =
        if (v.signum() == 0) "0" else v.stripTrailingZeros().toPlainString()

    fun whole(v: BigDecimal): String = amount(v.setScale(0, RoundingMode.HALF_UP))

    fun khDate(d: LocalDate): String = d.format(kh)

    fun dpDate(d: LocalDate): String = d.format(dp)
}

object EpoXmlWriter {
    fun write(root: EpoElement): ByteArray {
        val out = ByteArrayOutputStream()
        // The declaration is written by hand: StAX implementations differ in its quoting style.
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>".toByteArray(Charsets.UTF_8))
        val w = XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8")
        w.writeStartElement("Pisemnost")
        w.writeAttribute("nazevSW", "Fakturka")
        w.writeAttribute("verzeSW", AppInfo.VERSION)
        writeElement(w, root)
        w.writeEndElement()
        w.close()
        return out.toByteArray()
    }

    private fun writeElement(w: XMLStreamWriter, e: EpoElement) {
        if (e.children.isEmpty()) w.writeEmptyElement(e.name) else w.writeStartElement(e.name)
        e.attributes.forEach { (k, v) -> w.writeAttribute(k, v) }
        if (e.children.isNotEmpty()) {
            e.children.forEach { writeElement(w, it) }
            w.writeEndElement()
        }
    }
}
