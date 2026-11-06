package cz.chap.fakturka.tax

import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class EpoXmlWriterTest {
    @Test fun `amount formatting`() {
        assertEquals("1823.5", EpoFormat.amount("1823.50".toBigDecimal()))
        assertEquals("9918", EpoFormat.amount("9918.00".toBigDecimal()))
        assertEquals("0", EpoFormat.amount("0.00".toBigDecimal()))
        assertEquals("0", EpoFormat.amount(java.math.BigDecimal.ZERO))
        assertEquals("9918", EpoFormat.whole("9918.4".toBigDecimal()))
        assertEquals("100", EpoFormat.amount("1E+2".toBigDecimal()))
    }

    @Test fun `date formatting`() {
        assertEquals("1.7.2026", EpoFormat.khDate(LocalDate.of(2026, 7, 1)))
        assertEquals("20.04.2026", EpoFormat.dpDate(LocalDate.of(2026, 4, 20)))
    }

    @Test fun `writer keeps attribute order and escapes`() {
        val root = EpoElement(
            "DPHKH1", listOf("verzePis" to "01.02"),
            listOf(
                EpoElement("VetaD", listOf("z" to "A & B \"x\"", "a" to "1")),
                EpoElement("VetaA5", listOf("b" to "2")),
            ),
        )
        val bytes = EpoXmlWriter.write(root)
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(text.contains("z=\"A &amp; B &quot;x&quot;\" a=\"1\""))
        assertTrue(text.contains("<VetaA5 b=\"2\"/>"))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bytes.inputStream())
        assertEquals("Pisemnost", doc.documentElement.tagName)
        assertEquals("Fakturka", doc.documentElement.getAttribute("nazevSW"))
        assertEquals("1.0.0", doc.documentElement.getAttribute("verzeSW"))
        val vetaD = doc.getElementsByTagName("VetaD").item(0)
        assertEquals("A & B \"x\"", vetaD.attributes.getNamedItem("z").nodeValue)
    }

    @Test fun `czech diacritics survive round trip`() {
        val name = "Řeřicha Žluťoučký"
        val bytes = EpoXmlWriter.write(EpoElement("VetaP", listOf("zkrobchjm" to name)))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bytes.inputStream())
        assertEquals(name, doc.getElementsByTagName("VetaP").item(0).attributes.getNamedItem("zkrobchjm").nodeValue)
    }
}
