package cz.chap.fakturka.importing

import cz.chap.fakturka.domain.*
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.DataRepository
import org.w3c.dom.Element
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.inputStream

/** Reads the ProFact XML export of issued documents (root `DOKLADY`, one `DOKLAD` per invoice). */
object ProfactParser {
    const val ROOT = "DOKLADY"
    private val DATE = DateTimeFormatter.ofPattern("d.M.uuuu")
    private const val INVOICE_TYPE = "FAKTURA - daňový doklad"
    private val HOUSE_NUMBER_AT_END = Regex("""^(.*\S)\s+(\d+[A-Za-z]?(?:/\d+[A-Za-z]?)?)$""")

    fun parse(path: Path): List<ImportDocument> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val root = path.inputStream().use { factory.newDocumentBuilder().parse(it) }.documentElement
        require(root.tagName == ROOT) { Messages.get("ui.import.error.format") }
        return root.children("DOKLAD").map(::document)
    }

    private fun document(d: Element): ImportDocument {
        val number = d.text("Cislo_dokladu")
        fun skip(key: String, vararg args: Any) = ImportDocument.Skipped(number, Messages.get(key).format(*args))
        if (number.isBlank()) return skip("ui.import.skip.noNumber")
        if (d.text("Stornovano") == "1") return skip("ui.import.skip.cancelled")
        if (d.text("Typ_dokladu") != INVOICE_TYPE) return skip("ui.import.skip.type", d.text("Typ_dokladu"))
        if (d.text("Mena").ifBlank { "CZK" } != "CZK") return skip("ui.import.skip.currency", d.text("Mena"))
        return try {
            val customer = customer(d)
            val items = d.child("POLOZKY")?.children("POLOZKA").orEmpty().map { item(it, customer.type) }
            if (items.isEmpty()) return skip("ui.import.skip.noItems")
            val invoice = IssuedInvoice(
                number = number,
                issueDate = date(d.text("Datum_vystaveni").ifBlank { d.text("Datum") }),
                taxDate = date(d.text("DUZP")),
                dueDate = date(d.text("Datum_splatnosti")),
                customer = customer,
                orderNumber = null,
                constantSymbol = d.text("KS"),
                variableSymbol = d.text("VS").ifBlank { number },
                paymentMethod = d.text("Forma_uhrady").ifBlank { Messages.get("payment.transfer") },
                items = items,
                external = true,
            )
            // Recomputing must give ProFact's own total; otherwise the import would change what was invoiced.
            val expected = money(d.text("Celkem_s_DPH"))
            val actual = invoice.totals.total
            if (expected.compareTo(actual) != 0) skip("ui.import.skip.total", formatAmount(expected), formatAmount(actual))
            else ImportDocument.Ready(invoice)
        } catch (e: Exception) {
            skip("ui.import.skip.invalid", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun customer(d: Element): Contact {
        val dic = d.text("O_DIC").filterNot { it.isWhitespace() }.uppercase().ifBlank { null }
        val eu = d.text("EUVAT") == "1" || (dic != null && !dic.startsWith("CZ") && dic.take(2).all { it.isLetter() })
        val main = address(d, "O_")
        val delivery = address(d, "P_").takeIf { d.text("Prijemce").isNotBlank() && it != main }
        return Contact(
            id = UUID.randomUUID().toString(),
            name = d.text("Odberatel"),
            address = main,
            ico = d.text("O_IC").ifBlank { null },
            dic = dic,
            email = null,
            deliveryAddress = delivery,
            type = if (eu) ContactType.EU else ContactType.DOMESTIC,
        )
    }

    private fun address(d: Element, prefix: String): Address {
        val line = d.text("${prefix}Adresa_1")
        val split = HOUSE_NUMBER_AT_END.matchEntire(line)
        return Address(
            street = split?.groupValues?.get(1) ?: line,
            houseNumber = split?.groupValues?.get(2) ?: "",
            city = d.text("${prefix}Mesto"),
            postcode = d.text("${prefix}PSC").filterNot { it.isWhitespace() },
            country = d.text("${prefix}Stat").ifBlank { "Česká republika" },
        )
    }

    private fun item(p: Element, type: ContactType): InvoiceItem {
        val quantity = BigDecimal(p.text("Mnozstvi").ifBlank { "1" })
        val unitPrice = money(p.text("Cena_MJ"))
        val discountPercent = BigDecimal(p.text("Sleva_v_proc").ifBlank { "0" })
        val discount = (quantity * unitPrice * discountPercent).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
        val rate = when (p.text("DPH_proc").ifBlank { "0" }.toBigDecimal().toInt()) {
            21 -> VatRate.STANDARD
            12 -> VatRate.REDUCED
            0 -> VatRate.ZERO
            else -> throw IllegalArgumentException(Messages.get("ui.import.skip.rate").format(p.text("DPH_proc")))
        }
        return InvoiceItem(
            description = p.text("Nazev"),
            quantity = quantity,
            unit = p.text("MJ"),
            unitPrice = unitPrice,
            discount = discount,
            vatRate = if (type == ContactType.EU) VatRate.ZERO else rate,
        )
    }

    private fun date(text: String): LocalDate = LocalDate.parse(text.trim(), DATE)
    private fun money(text: String): BigDecimal = BigDecimal(text.trim().ifBlank { "0" }).money()

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private fun Element.child(tag: String): Element? = children(tag).firstOrNull()
    private fun Element.text(tag: String): String = child(tag)?.textContent?.trim().orEmpty()
}

