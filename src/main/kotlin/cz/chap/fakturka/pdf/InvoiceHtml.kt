package cz.chap.fakturka.pdf

import cz.chap.fakturka.domain.Address
import cz.chap.fakturka.domain.ContactType
import cz.chap.fakturka.domain.InvoiceTotals
import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.domain.SellerConfig
import cz.chap.fakturka.domain.VatRate
import cz.chap.fakturka.domain.formatAmount
import cz.chap.fakturka.domain.money
import cz.chap.fakturka.domain.totals
import cz.chap.fakturka.i18n.Messages
import kotlinx.html.FlowContent
import kotlinx.html.TBODY
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.img
import kotlinx.html.meta
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Builds the XHTML of an invoice in the ProFact layout; [InvoicePdfRenderer] turns it into a PDF. */
object InvoiceHtml {
    private const val CURRENCY = "CZK"
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")
    private val css: String by lazy {
        requireNotNull(InvoiceHtml::class.java.getResourceAsStream("/pdf/invoice.css")) { "pdf/invoice.css missing" }
            .use { it.readBytes().decodeToString() }
    }

    /** [signatureUri] is a data URI of the stamp and signature image, drawn above the signature line. */
    fun render(invoice: IssuedInvoice, seller: SellerConfig, signatureUri: String? = null): String =
        renderPrinted(invoice.asPrinted(), seller, signatureUri)

    private fun renderPrinted(invoice: IssuedInvoice, seller: SellerConfig, signatureUri: String?): String {
        val labels = Labels(eu = invoice.customer.type == ContactType.EU)
        val totals = invoice.totals
        val qr = Base64.getEncoder().encodeToString(QrPayment.png(QrPayment.spd(invoice, seller)))

        return createHTML(prettyPrint = false, xhtmlCompatible = true).html {
            head {
                meta(charset = "UTF-8")
                title { +"${labels.title} ${invoice.number}" }
                style { unsafe { raw(css) } }
            }
            body(if (labels.eu) "eu" else null) {
                footer(seller, labels)

                div("page") {
                    div("header") {
                        div("title") { +labels.title }
                        div("number") { +invoice.number }
                    }

                    parties(invoice, seller, labels)
                    infoStrip(invoice, labels)
                    items(invoice, totals, labels)

                    summary(totals, labels)

                    div("bottom") {
                        div("qr") {
                            img(alt = labels["pdf.qr"], src = "data:image/png;base64,$qr", classes = "qr-image")
                            div("qr-label") { +labels["pdf.qr"] }
                        }
                        signatureUri?.let { uri ->
                            div("signature-image") { img(alt = labels["pdf.signature"], src = uri) }
                        }
                        div("signature") { +labels["pdf.signature"] }
                    }
                }
            }
        }
    }

    private fun FlowContent.summary(totals: InvoiceTotals, labels: Labels) = div("summary") {
        table("recap-outer") {
            tbody {
                tr {
                    td("recap-label") { +labels["pdf.vatSummary"] }
                    td {
                        table("recap") {
                            thead {
                                tr {
                                    th(classes = "left") { +labels["pdf.vatSummary.rate"] }
                                    th { +labels["pdf.vatSummary.base"] }
                                    th { +labels["pdf.vatSummary.vat"] }
                                    th { +labels["pdf.vatSummary.total"] }
                                }
                            }
                            tbody {
                                totals.vatLines.forEach { line ->
                                    tr {
                                        td("left rate") { +"${line.rate.percent} %" }
                                        td { +formatAmount(line.base) }
                                        td { +formatAmount(line.vat) }
                                        td { +formatAmount(line.total) }
                                    }
                                }
                                tr("sum") {
                                    td("left rate") { +labels.colon("pdf.vatSummary.sum") }
                                    td { +formatAmount(totals.base) }
                                    td { +formatAmount(totals.vat) }
                                    td { +formatAmount(totals.total) }
                                }
                            }
                        }
                    }
                }
            }
        }
        table("amount-due") {
            tbody {
                tr {
                    td { +labels.colon("pdf.amountDue") }
                    td("value") { +"${formatAmount(totals.total)} $CURRENCY" }
                }
            }
        }
        if (labels.eu) div("reverse-charge") { +labels["pdf.reverseCharge"] }
    }

    private fun FlowContent.footer(seller: SellerConfig, labels: Labels) = div("footer") {
        table {
            tbody {
                tr {
                    td { +seller.name }
                    td("right") {
                        +"${labels["pdf.page"]} "
                        span("page-number")
                    }
                }
                tr {
                    val bank = "${labels.colon("pdf.footer.bankDetails")} ${seller.bankName}, " +
                        "${labels["pdf.footer.account"]} ${seller.accountNumber}"
                    td { +bank }
                    td("right") { +labels["pdf.footer.createdWith"] }
                }
            }
        }
    }

    private fun FlowContent.parties(invoice: IssuedInvoice, seller: SellerConfig, labels: Labels) {
        val customer = invoice.customer
        table("parties") {
            tbody {
                tr {
                    td("box box-top") {
                        div("heading") { +labels.heading("pdf.supplier") }
                        div("party-name") { +seller.name }
                        address(seller.address)
                        idLine(labels, seller.ico, seller.dic)
                        table("contacts") {
                            tbody {
                                labeled(labels.colon("pdf.email"), seller.email)
                                seller.phone?.let { labeled(labels.colon("pdf.phone"), it) }
                                seller.web?.let { labeled(labels.colon("pdf.web"), it) }
                            }
                        }
                    }
                    td("gap")
                    td("box box-top") {
                        div("heading") { +labels.heading("pdf.customer") }
                        div("party-name") { +customer.name }
                        address(customer.address)
                        idLine(labels, customer.ico, customer.dic)
                    }
                }
                tr {
                    td("box box-bottom") {
                        table("bank") {
                            tbody {
                                tr("bank-name") {
                                    td("label") { +labels.colon("pdf.bank") }
                                    td { +seller.bankName }
                                }
                                tr("account") {
                                    td("label") { +labels.colon("pdf.account") }
                                    td("strong") { +seller.accountNumber }
                                }
                                labeled(labels.colon("pdf.iban"), seller.iban)
                                labeled(labels.colon("pdf.swift"), seller.swift)
                            }
                        }
                    }
                    td("gap")
                    td("box box-bottom") {
                        div("heading small") { +labels.heading("pdf.deliveryAddress") }
                        div("delivery") {
                            div("strong") { +customer.name }
                            addressLines(customer.deliveryAddress ?: customer.address)
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.address(address: Address) = div("address") { addressLines(address) }

    private fun FlowContent.addressLines(address: Address) {
        div { +"${address.street} ${address.houseNumber}".trim() }
        div { +"${address.postcode}\u00A0\u00A0${address.city}".trim() }
        div { +address.country }
    }

    private fun FlowContent.idLine(labels: Labels, ico: String?, dic: String?) = table("ids") {
        tbody {
            tr {
                td("ico") { ico?.let { +"${labels.colon("pdf.ico")} $it" } }
                td { dic?.let { +"${labels.colon("pdf.dic")} $it" } }
            }
        }
    }

    private fun TBODY.labeled(label: String, value: String) = tr {
        td("label") { +label }
        td { +value }
    }

    private fun FlowContent.infoStrip(invoice: IssuedInvoice, labels: Labels) = div("info") {
        table {
            tbody {
                infoRow(
                    labels.colon("pdf.orderNumber") to (invoice.orderNumber ?: ""),
                    labels.colon("pdf.issueDate") to invoice.issueDate.cz(),
                    labels.colon("pdf.paymentMethod") to invoice.paymentMethod,
                    lastStrong = true,
                )
                infoRow(
                    "" to "",
                    labels.colon("pdf.taxDate") to invoice.taxDate.cz(),
                    labels.colon("pdf.constantSymbol") to invoice.constantSymbol,
                )
                tr("spacer") { td { attributes["colspan"] = "6" } }
                infoRow(
                    "" to "",
                    labels.colon("pdf.dueDate") to invoice.dueDate.cz(),
                    labels.colon("pdf.variableSymbol") to invoice.variableSymbol,
                    big = true,
                )
            }
        }
    }

    private fun TBODY.infoRow(
        vararg cells: Pair<String, String>,
        big: Boolean = false,
        lastStrong: Boolean = false,
    ) = tr(if (big) "big" else null) {
        cells.forEachIndexed { i, (label, value) ->
            td("label col$i") { +label }
            val valueClass = when {
                big && i > 0 -> "value strong"
                lastStrong && i == cells.lastIndex -> "value strong"
                else -> "value"
            }
            td(valueClass) { +value }
        }
    }

    private fun FlowContent.items(invoice: IssuedInvoice, totals: InvoiceTotals, labels: Labels) {
        val lineTotals = totals.lineTotals
        table("items") {
            thead {
                tr {
                    th(classes = "left w-desc") { +labels["pdf.item.description"] }
                    th(classes = "w-qty") { +labels["pdf.item.quantity"] }
                    th(classes = "left w-unit") { +labels["pdf.item.unit"] }
                    th(classes = "w-price") { +labels["pdf.item.unitPrice"] }
                    th(classes = "left w-discount") { +labels["pdf.item.discount"] }
                    th(classes = "w-total") { +labels["pdf.item.total"] }
                    th(classes = "w-rate") { +labels["pdf.item.vatRate"] }
                    th(classes = "w-vat") { +labels["pdf.item.vat"] }
                    th(classes = "w-gross") { +labels["pdf.item.totalWithVat"] }
                }
            }
            tbody {
                invoice.items.forEachIndexed { i, item ->
                    val lineTotal = lineTotals[i]
                    val vat = (lineTotal * BigDecimal(item.vatRate.percent)).movePointLeft(2).money()
                    tr {
                        td("left description") { +item.description }
                        td { +item.quantity.stripTrailingZeros().toPlainString() }
                        td("left") { +item.unit }
                        td { +formatAmount(item.unitPrice) }
                        td("left") { if (item.discount.signum() != 0) +formatAmount(item.discount) }
                        td { +formatAmount(lineTotal) }
                        td { +rate(item.vatRate) }
                        td { +formatAmount(vat) }
                        td("strong") { +formatAmount(lineTotal + vat) }
                    }
                }
            }
        }
    }

    private fun rate(rate: VatRate) = "${rate.percent}%"

    private fun LocalDate.cz(): String = format(DATE)

    /** PDF labels: Czech, or "Czech / English" for EU customers. */
    private class Labels(val eu: Boolean) {
        operator fun get(key: String): String {
            val cs = Messages.get(key, Messages.CS)
            if (!eu) return cs
            val en = Messages.get(key, Messages.EN)
            return if (cs == en) cs else "$cs / $en"
        }

        fun colon(key: String) = "${get(key)}:"

        /** Box headings are upper case on Czech invoices, as in the ProFact layout. */
        fun heading(key: String) = if (eu) get(key) else get(key).uppercase(Messages.CS)

        val title: String get() = if (eu) get("pdf.title.eu") else get("pdf.title")
    }
}
