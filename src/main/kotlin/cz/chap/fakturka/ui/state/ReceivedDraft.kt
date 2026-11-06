package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.Contact
import cz.chap.fakturka.domain.Quarter
import cz.chap.fakturka.domain.ReceivedInvoice
import cz.chap.fakturka.domain.VatAmount
import cz.chap.fakturka.domain.VatRate
import cz.chap.fakturka.domain.money
import cz.chap.fakturka.i18n.Messages
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Rates a received invoice can carry (0 % has no VAT to deduct). */
val RECEIVED_RATES: List<VatRate> = listOf(VatRate.STANDARD, VatRate.REDUCED)

/** Base and VAT as typed; [vatEdited] stops the VAT following the base. */
data class ReceivedAmountDraft(val baseText: String = "", val vatText: String = "", val vatEdited: Boolean = false)

/**
 * Editable state of the received-invoice form. Dates and amounts are text parsed in [validate]. The claim period
 * follows the quarter of the DUZP until the user sets it ([claimEdited]); VAT follows base × rate until typed.
 */
data class ReceivedDraft(
    val id: String,
    val supplierName: String,
    val supplierDic: String,
    val documentNumber: String,
    val taxDate: String,
    val claimYear: String,
    val claimQuarter: Int,
    val claimEdited: Boolean,
    val amounts: Map<VatRate, ReceivedAmountDraft>,
) {
    fun amount(rate: VatRate): ReceivedAmountDraft = amounts[rate] ?: ReceivedAmountDraft()

    fun withSupplier(c: Contact) = copy(supplierName = c.name, supplierDic = c.dic.orEmpty())

    fun withTaxDate(text: String): ReceivedDraft {
        val moved = copy(taxDate = text)
        val date = parseDate(text)
        if (claimEdited || date == null) return moved
        val q = Quarter.of(date)
        return moved.copy(claimYear = q.year.toString(), claimQuarter = q.quarter)
    }

    fun withClaim(year: String, quarter: Int) = copy(claimYear = year, claimQuarter = quarter, claimEdited = true)

    fun withBase(rate: VatRate, text: String): ReceivedDraft {
        val old = amount(rate)
        val vat = if (old.vatEdited) old.vatText else parseNonNegative(text)?.let { prefill(it, rate) }.orEmpty()
        return copy(amounts = amounts + (rate to old.copy(baseText = text, vatText = vat)))
    }

    /**
     * Typing a VAT keeps it. Clearing the field leaves it blank (not refilled at once, so the user can type a new value)
     * and hands it back to the base × rate prefill from the next base change.
     */
    fun withVat(rate: VatRate, text: String): ReceivedDraft =
        copy(amounts = amounts + (rate to amount(rate).copy(vatText = text, vatEdited = text.isNotBlank())))

    /** Messages for everything that stops the draft from being saved; empty when [toInvoice] is safe. */
    fun validate(): List<String> = buildList {
        if (supplierName.isBlank()) add(Messages.get("ui.received.error.supplier"))
        val date = parseDate(taxDate)
        if (date == null) add(Messages.get("ui.received.error.taxDate"))
        if (claim() == null) add(Messages.get("ui.received.error.claim"))
        if (RECEIVED_RATES.any { amount(it).base() == null || amount(it).vat() == null }) add(Messages.get("ui.received.error.amount"))
        if (isEmpty()) addAll(receivedRules(toInvoice()))
    }

    /** Call only when [validate] is empty. */
    fun toInvoice() = ReceivedInvoice(
        id = id,
        supplierName = supplierName.trim(),
        supplierDic = supplierDic.trim().ifBlank { null },
        documentNumber = documentNumber.trim(),
        taxDate = parseDate(taxDate)!!,
        claimPeriod = claim()!!,
        amounts = RECEIVED_RATES.associateWith { VatAmount(amount(it).base()!!, amount(it).vat()!!) }
            .filterValues { it.base.signum() != 0 || it.vat.signum() != 0 },
    )

    private fun claim(): Quarter? {
        val year = claimYear.trim().toIntOrNull()?.takeIf { it in 1900..2999 } ?: return null
        return claimQuarter.takeIf { it in 1..4 }?.let { Quarter(year, it) }
    }

    companion object {
        private fun ReceivedAmountDraft.base() = if (baseText.isBlank()) BigDecimal.ZERO.money() else parseMoney(baseText)
        private fun ReceivedAmountDraft.vat() = if (vatText.isBlank()) BigDecimal.ZERO.money() else parseMoney(vatText)

        private fun prefill(base: BigDecimal, rate: VatRate): String =
            (base * BigDecimal(rate.percent) / BigDecimal(100)).money().toPlainString()

        /** Blank form; the claim period starts at the quarter of [today]. */
        fun new(today: LocalDate): ReceivedDraft {
            val q = Quarter.of(today)
            return ReceivedDraft(
                UUID.randomUUID().toString(), "", "", "", formatDate(today), q.year.toString(), q.quarter,
                claimEdited = false, amounts = emptyMap(),
            )
        }

        /** Edit of a stored invoice: the claim period is kept as stored, VAT as stored. */
        fun from(r: ReceivedInvoice) = ReceivedDraft(
            r.id, r.supplierName, r.supplierDic.orEmpty(), r.documentNumber, formatDate(r.taxDate),
            r.claimPeriod.year.toString(), r.claimPeriod.quarter, claimEdited = true,
            amounts = r.amounts.mapValues { (_, a) ->
                ReceivedAmountDraft(a.base.money().toPlainString(), a.vat.money().toPlainString(), vatEdited = true)
            },
        )
    }
}
