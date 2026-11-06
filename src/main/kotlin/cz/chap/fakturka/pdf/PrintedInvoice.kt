package cz.chap.fakturka.pdf

import cz.chap.fakturka.domain.ContactType
import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.domain.VatRate

/** The invoice as printed and paid: an EU customer is invoiced under reverse charge, so every item has 0 % VAT. */
internal fun IssuedInvoice.asPrinted(): IssuedInvoice =
    if (customer.type == ContactType.EU && items.any { it.vatRate != VatRate.ZERO }) {
        copy(items = items.map { it.copy(vatRate = VatRate.ZERO) })
    } else {
        this
    }
