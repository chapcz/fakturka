package cz.chap.fakturka.domain

import java.math.BigDecimal
import java.math.BigDecimal.ZERO
import java.time.LocalDate

enum class VatRate(val percent: Int) { STANDARD(21), REDUCED(12), ZERO(0) }

enum class ContactType { DOMESTIC, EU }

/** VAT ID as the tax forms expect it: no whitespace, upper case ("cz 1122 3344" -> "CZ11223344"). */
fun normalizeVatId(dic: String): String = dic.filterNot(Char::isWhitespace).uppercase()

data class Address(
    val street: String,
    val houseNumber: String,
    val city: String,
    val postcode: String,
    val country: String,
)

data class Contact(
    val id: String,
    val name: String,
    val address: Address,
    val ico: String?,
    val dic: String?,
    val email: String?,
    val deliveryAddress: Address?,
    val type: ContactType,
) {
    val hasCzVatId: Boolean get() = dic?.let(::normalizeVatId)?.startsWith("CZ") == true

    fun dicWithoutPrefix(): String? =
        dic?.let(::normalizeVatId)?.let { if (it.length >= 2 && it.take(2).all(Char::isLetter)) it.substring(2) else it }
}

data class InvoiceItem(
    val description: String,
    val quantity: BigDecimal,
    val unit: String,
    val unitPrice: BigDecimal,
    val discount: BigDecimal = BigDecimal("0.00"),
    val vatRate: VatRate,
)

data class IssuedInvoice(
    val number: String,
    val issueDate: LocalDate,
    val taxDate: LocalDate,
    val dueDate: LocalDate,
    val customer: Contact,
    val orderNumber: String?,
    val constantSymbol: String,
    val variableSymbol: String,
    val paymentMethod: String,
    val items: List<InvoiceItem>,
    val external: Boolean = false,
    /** Day the customer paid; null while unpaid. */
    val paidDate: LocalDate? = null,
) {
    /** Unpaid and past its due date. */
    fun isOverdue(today: LocalDate): Boolean = paidDate == null && dueDate.isBefore(today)
}

data class VatAmount(val base: BigDecimal, val vat: BigDecimal)

data class Quarter(val year: Int, val quarter: Int) {
    val months: IntRange get() = (quarter - 1) * 3 + 1..quarter * 3

    operator fun contains(date: LocalDate): Boolean = date.year == year && date.monthValue in months

    companion object {
        fun of(date: LocalDate): Quarter = Quarter(date.year, (date.monthValue - 1) / 3 + 1)
    }
}

data class ReceivedInvoice(
    val id: String,
    val supplierName: String,
    val supplierDic: String?,
    val documentNumber: String,
    val taxDate: LocalDate,
    val claimPeriod: Quarter,
    val amounts: Map<VatRate, VatAmount>,
) {
    val total: BigDecimal get() = amounts.values.fold(ZERO) { acc, a -> acc + a.base + a.vat }
}

data class TemplateItem(
    val description: String,
    val unit: String,
    val unitPrice: BigDecimal,
    val vatRate: VatRate,
    val defaultQuantity: BigDecimal?,
)

data class Template(
    val id: String,
    val name: String,
    val customerId: String,
    val orderNumber: String?,
    val dueDays: Int?,
    val items: List<TemplateItem>,
)

data class SellerConfig(
    val name: String,
    val firstName: String,
    val lastName: String,
    val title: String,
    val address: Address,
    val ico: String,
    val dic: String,
    val email: String,
    val phone: String?,
    val web: String?,
    val bankName: String,
    val accountNumber: String,
    val iban: String,
    val swift: String,
    val taxOfficeCode: String, // c_ufo
    val taxOfficeBranch: String, // c_pracufo
    val czNace: String,
    val constantSymbol: String = "0308",
    val dueDays: Int = 14,
    val numberingStartYear: Int? = null,
    val numberingStartSequence: Int? = null,
    /** Academic title written after the name (Ph.D.); [title] is the one before it. */
    val titleAfter: String = "",
    /** Pattern of own invoice numbers, see [NumberFormat]. */
    val numberFormat: String = NumberFormat.DEFAULT,
) {
    /** Both titles as one string, e.g. "Ing. Ph.D." — the form EPO expects in `titul`. */
    val titles: String get() = listOf(title, titleAfter).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}
