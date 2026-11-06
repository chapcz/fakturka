package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.Contact
import cz.chap.fakturka.domain.ContactType
import cz.chap.fakturka.domain.InvoiceCalculator
import cz.chap.fakturka.domain.InvoiceFactory
import cz.chap.fakturka.domain.InvoiceItem
import cz.chap.fakturka.domain.NumberFormat
import cz.chap.fakturka.domain.InvoiceTotals
import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.domain.SellerConfig
import cz.chap.fakturka.domain.Template
import cz.chap.fakturka.domain.VatRate
import cz.chap.fakturka.domain.money
import cz.chap.fakturka.i18n.Messages
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT)

/** Formats a date as d.M.yyyy (the notation the user types). */
fun formatDate(date: LocalDate): String = DATE_FORMAT.format(date)

/** Parses d.M.yyyy (spaces after the dots are tolerated); null when malformed or not a real date. */
fun parseDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text.filterNot { it.isWhitespace() }, DATE_FORMAT) }.getOrNull()

/** One invoice line as typed; numbers stay text until [InvoiceDraft.validate] / [InvoiceDraft.toInvoice]. */
data class InvoiceItemDraft(
    val description: String,
    val quantityText: String,
    val unit: String,
    val unitPriceText: String,
    val discountText: String,
    val vatRate: VatRate,
    /** Rate chosen before an EU customer forced 0 %; restored when a domestic customer is picked again. */
    val rateBeforeEu: VatRate? = null,
) {
    val quantity: BigDecimal? get() = parsePositiveDecimal(quantityText)
    val unitPrice: BigDecimal? get() = parsePositiveDecimal(unitPriceText)?.takeIf { it.hasMoneyScale() }?.money()
    val discount: BigDecimal?
        get() = if (discountText.isBlank()) BigDecimal.ZERO.money() else parseMoney(discountText)

    /** Amount before discount, when quantity and price are valid. */
    val lineAmount: BigDecimal? get() = quantity?.let { q -> unitPrice?.let { p -> (q * p).money() } }

    /** Lines that cannot be parsed count as zero in the live total. */
    internal fun lenientItem() = InvoiceItem(
        description, quantity ?: BigDecimal.ZERO, unit, unitPrice ?: BigDecimal.ZERO,
        discount ?: BigDecimal.ZERO.money(), vatRate,
    )

    companion object {
        fun blank() = InvoiceItemDraft("", "", "h", "", "", VatRate.STANDARD)

        fun from(i: InvoiceItem) = InvoiceItemDraft(
            i.description, i.quantity.toPlainString(), i.unit, i.unitPrice.money().toPlainString(),
            i.discount.money().toPlainString(), i.vatRate,
        )
    }
}

/**
 * Editable state of the new-invoice dialog. Dates are text (d.M.yyyy), quantities and prices are text parsed in
 * [validate]. [autoDates] (a new own invoice) makes the tax and due dates follow the issue date until the user types them.
 */
data class InvoiceDraft(
    val number: String,
    val issueDate: String,
    val taxDate: String,
    val dueDate: String,
    val customer: Contact?,
    val orderNumber: String,
    val constantSymbol: String,
    val variableSymbol: String?,
    val paymentMethod: String,
    val items: List<InvoiceItemDraft>,
    val external: Boolean,
    val dueDays: Int,
    val autoDates: Boolean,
    val customerLocked: Boolean,
    val taxDateEdited: Boolean = false,
    val dueDateEdited: Boolean = false,
    /** Carried over from the stored invoice so that an edit keeps the payment. */
    val paidDate: LocalDate? = null,
    /** Pattern a new own number must match; null for taken-over invoices and edits, whose number stays as stored. */
    val numberFormat: String? = null,
) {
    val totals: InvoiceTotals get() = InvoiceCalculator.totals(items.map { it.lenientItem() })

    fun withIssueDate(text: String): InvoiceDraft {
        val moved = copy(issueDate = text)
        val new = parseDate(text)
        if (!autoDates || new == null) return moved
        return moved.copy(
            taxDate = if (taxDateEdited) taxDate else formatDate(new),
            dueDate = if (dueDateEdited) dueDate else formatDate(new.plusDays(dueDays.toLong())),
        )
    }

    fun withTaxDate(text: String) = copy(taxDate = text, taxDateEdited = true)

    fun withDueDate(text: String) = copy(dueDate = text, dueDateEdited = true)

    /** Picks the customer of a template-less draft; EU customers force 0 % VAT, a domestic one restores the earlier rates. */
    fun withCustomer(contact: Contact): InvoiceDraft = copy(
        customer = contact,
        items = items.map {
            if (contact.type == ContactType.EU) it.copy(vatRate = VatRate.ZERO, rateBeforeEu = it.rateBeforeEu ?: it.vatRate)
            else it.copy(vatRate = it.rateBeforeEu ?: it.vatRate, rateBeforeEu = null)
        },
    )

    /** Messages for everything that stops the draft from being saved; empty when [toInvoice] is safe. */
    fun validate(): List<String> = buildList {
        if (external) {
            if (number.isBlank()) add(Messages.get("ui.invoice.error.number"))
        }
        if (customer == null) add(Messages.get("ui.invoice.error.customer"))
        val issue = parseDate(issueDate)
        if (issue == null) add(Messages.get("ui.invoice.error.issueDate"))
        if (parseDate(taxDate) == null) add(Messages.get("ui.invoice.error.taxDate"))
        if (parseDate(dueDate) == null) add(Messages.get("ui.invoice.error.dueDate"))
        if (!external && issue != null && numberFormat != null && NumberFormat.of(numberFormat).sequenceFor(number, issue) == null) {
            add(Messages.get("ui.invoice.error.numberYear"))
        }
        if (items.isEmpty()) add(Messages.get("ui.invoice.error.items"))
        val rowLabel = Messages.get("ui.invoice.error.itemRow")
        items.forEachIndexed { index, i ->
            val row = buildList {
                if (i.description.isBlank()) add(Messages.get("ui.invoice.error.item.description"))
                if (i.unit.isBlank()) add(Messages.get("ui.invoice.error.item.unit"))
                if (i.quantity == null) add(Messages.get("ui.invoice.error.item.quantity"))
                if (i.unitPrice == null) add(Messages.get("ui.invoice.error.item.unitPrice"))
                val discount = i.discount
                if (discount == null) add(Messages.get("ui.invoice.error.item.discount"))
                else if (i.lineAmount?.let { discount > it } == true) add(Messages.get("ui.invoice.error.item.discountTooHigh"))
                when (customer?.type) {
                    ContactType.DOMESTIC -> if (i.vatRate == VatRate.ZERO) add(Messages.get("ui.invoice.error.item.rateDomestic"))
                    ContactType.EU -> if (i.vatRate != VatRate.ZERO) add(Messages.get("ui.invoice.error.item.rateEu"))
                    null -> Unit
                }
            }
            addAll(row.map { "$rowLabel ${index + 1}: $it" })
        }
    }

    /** Call only when [validate] is empty. */
    fun toInvoice(): IssuedInvoice = IssuedInvoice(
        number = number.trim(),
        issueDate = parseDate(issueDate)!!,
        taxDate = parseDate(taxDate)!!,
        dueDate = parseDate(dueDate)!!,
        customer = customer!!,
        orderNumber = orderNumber.trim().ifBlank { null },
        constantSymbol = constantSymbol.trim(),
        variableSymbol = variableSymbol ?: NumberFormat.variableSymbol(number),
        paymentMethod = paymentMethod,
        items = items.map {
            InvoiceItem(it.description.trim(), it.quantity!!, it.unit.trim(), it.unitPrice!!, it.discount!!, it.vatRate)
        },
        external = external,
        paidDate = paidDate,
    )

    companion object {
        fun fromTemplate(template: Template, customer: Contact, seller: SellerConfig, number: String, issueDate: LocalDate): InvoiceDraft {
            val base = InvoiceFactory.fromTemplate(template, customer, seller, number, issueDate, template.items.map { BigDecimal.ZERO })
            val items = base.items.zip(template.items).map { (item, source) ->
                InvoiceItemDraft.from(item).copy(quantityText = source.defaultQuantity?.toPlainString().orEmpty())
            }
            return InvoiceDraft(
                number, formatDate(base.issueDate), formatDate(base.taxDate), formatDate(base.dueDate), customer,
                base.orderNumber.orEmpty(), base.constantSymbol, null, base.paymentMethod, items,
                external = false, dueDays = template.dueDays ?: seller.dueDays, autoDates = true, customerLocked = true,
                numberFormat = seller.numberFormat,
            )
        }

        /** "Bez šablony": own invoice, one blank line, customer picked in the dialog. */
        fun empty(seller: SellerConfig, number: String, issueDate: LocalDate): InvoiceDraft =
            blank(seller, number, issueDate, external = false)

        /** "Převzatá faktura": typed number, all dates editable, no PDF. */
        fun external(seller: SellerConfig, issueDate: LocalDate): InvoiceDraft =
            blank(seller, "", issueDate, external = true)

        private fun blank(seller: SellerConfig, number: String, issueDate: LocalDate, external: Boolean) = InvoiceDraft(
            number, formatDate(issueDate), formatDate(issueDate), formatDate(issueDate.plusDays(seller.dueDays.toLong())), null,
            "", seller.constantSymbol, null, Messages.get("payment.transfer"), listOf(InvoiceItemDraft.blank()),
            external = external, dueDays = seller.dueDays, autoDates = !external, customerLocked = false,
            numberFormat = if (external) null else seller.numberFormat,
        )

        /** Edit of a stored invoice: number fixed, dates independent; the customer can be picked again from the address book. */
        fun fromInvoice(invoice: IssuedInvoice): InvoiceDraft = InvoiceDraft(
            invoice.number, formatDate(invoice.issueDate), formatDate(invoice.taxDate), formatDate(invoice.dueDate),
            invoice.customer, invoice.orderNumber.orEmpty(), invoice.constantSymbol, invoice.variableSymbol,
            invoice.paymentMethod, invoice.items.map { InvoiceItemDraft.from(it) },
            external = invoice.external, dueDays = 0, autoDates = false, customerLocked = false,
            paidDate = invoice.paidDate,
        )
    }
}
