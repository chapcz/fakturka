package cz.chap.fakturka.domain

import cz.chap.fakturka.i18n.Messages
import java.math.BigDecimal
import java.time.LocalDate

object InvoiceFactory {
    fun fromTemplate(
        template: Template,
        customer: Contact,
        seller: SellerConfig,
        number: String,
        issueDate: LocalDate,
        quantities: List<BigDecimal>,
    ): IssuedInvoice {
        require(quantities.size == template.items.size) {
            "Expected ${template.items.size} quantities, got ${quantities.size}"
        }
        val items = template.items.zip(quantities).map { (item, quantity) ->
            InvoiceItem(
                description = item.description,
                quantity = quantity,
                unit = item.unit,
                unitPrice = item.unitPrice.money(),
                discount = BigDecimal.ZERO.money(),
                vatRate = if (customer.type == ContactType.EU) VatRate.ZERO else item.vatRate,
            )
        }
        return IssuedInvoice(
            number = number,
            issueDate = issueDate,
            taxDate = issueDate,
            dueDate = issueDate.plusDays((template.dueDays ?: seller.dueDays).toLong()),
            customer = customer,
            orderNumber = template.orderNumber,
            constantSymbol = seller.constantSymbol,
            variableSymbol = NumberFormat.variableSymbol(number),
            paymentMethod = Messages.get("payment.transfer"),
            items = items,
        )
    }
}
