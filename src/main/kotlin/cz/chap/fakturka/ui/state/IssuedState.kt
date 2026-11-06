package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.Contact
import cz.chap.fakturka.domain.InvoiceNumbering
import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.domain.NumberFormat
import cz.chap.fakturka.domain.Quarter
import cz.chap.fakturka.domain.SellerConfig
import cz.chap.fakturka.domain.Template
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.pdf.InvoicePdfRenderer
import cz.chap.fakturka.storage.DataRepository
import cz.chap.fakturka.storage.RepositoryException
import cz.chap.fakturka.tax.ExportService
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate

/** The invoice is stored but its PDF could not be written; a retry must replace the stored invoice. */
class PdfFailedAfterSave(val invoice: IssuedInvoice, cause: Throwable) : RuntimeException(cause.message ?: cause.javaClass.simpleName, cause)

/** [pdf] is null for taken-over invoices; [exportedQuarterWarning] names a quarter whose export must be regenerated. */
data class SaveOutcome(val pdf: Path?, val exportedQuarterWarning: Quarter?)

class IssuedState(
    private val repo: DataRepository,
    private val exportService: ExportService,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val renderer: (IssuedInvoice, SellerConfig, Path) -> Unit =
        { invoice, seller, target -> InvoicePdfRenderer.render(invoice, seller, target, repo.signatureImage()) },
) {
    val currentYear: Int get() = LocalDate.now(clock).year

    var year: Int = currentYear
        set(value) {
            // Load first: when the repository fails, year and invoices both stay as they were.
            invoices = load(value)
            field = value
        }

    var invoices: List<IssuedInvoice> = load(year)
        private set

    fun reload() {
        invoices = load(year)
    }

    private fun load(year: Int) = repo.issued(year).sortedByDescending { it.number }

    val templates: List<Template> get() = repo.templates()

    val contacts: List<Contact> get() = repo.contacts()

    fun pdfPath(invoice: IssuedInvoice): Path = repo.pdfPath(invoice)

    val today: LocalDate get() = LocalDate.now(clock)

    /** Marks [invoice] paid today, or unpaid when [paid] is false; nothing else changes, the PDF stays as it is. */
    fun setPaid(invoice: IssuedInvoice, paid: Boolean) {
        repo.saveIssued(invoice.copy(paidDate = if (paid) today else null), replacing = invoice.number)
        reload()
    }

    /** Renders the PDF of any stored invoice (taken-over ones get none on save) and returns its path. */
    fun renderPdf(invoice: IssuedInvoice): Path {
        val target = repo.pdfPath(invoice)
        renderer(invoice, seller(), target)
        return target
    }

    /** The repository allows deleting taken-over invoices any time, own ones only when they carry the highest number. */
    fun canDelete(invoice: IssuedInvoice): Boolean {
        if (invoice.external) return true
        val format = NumberFormat.of(seller().numberFormat)
        val sequence = { inv: IssuedInvoice -> InvoiceNumbering.sequenceOf(inv.number, year, format) }
        return sequence(invoice) != null && sequence(invoice) == invoices.filter { !it.external }.mapNotNull(sequence).maxOrNull()
    }

    fun newFromTemplate(template: Template): InvoiceDraft {
        val customer = repo.contacts().firstOrNull { it.id == template.customerId }
            ?: throw RepositoryException(Messages.get("ui.invoice.error.customerMissing"))
        val today = LocalDate.now(clock)
        return InvoiceDraft.fromTemplate(template, customer, seller(), repo.nextNumber(today), today)
    }

    fun newEmpty(): InvoiceDraft {
        val today = LocalDate.now(clock)
        return InvoiceDraft.empty(seller(), repo.nextNumber(today), today)
    }

    fun newExternal(): InvoiceDraft = InvoiceDraft.external(seller(), LocalDate.now(clock))

    fun edit(invoice: IssuedInvoice): InvoiceDraft = InvoiceDraft.fromInvoice(invoice)

    /** Own new invoices take their number from the issue date: a new year (or month, when the pattern has it) recomputes it. */
    fun withIssueDate(draft: InvoiceDraft, text: String): InvoiceDraft {
        val moved = draft.withIssueDate(text)
        val before = parseDate(draft.issueDate)
        val after = parseDate(text)
        if (!draft.autoDates || draft.external || after == null) return moved
        val usesMonth = NumberFormat.of(seller().numberFormat).usesMonth
        val same = before != null && before.year == after.year && (!usesMonth || before.monthValue == after.monthValue)
        return if (same) moved else moved.copy(number = repo.nextNumber(after))
    }

    /** The quarter of [invoice] when its VAT export already exists (and would go stale), else null. */
    fun exportedQuarter(invoice: IssuedInvoice): Quarter? =
        Quarter.of(invoice.taxDate).takeIf { exportService.isExported(it) }

    /**
     * Call only when [InvoiceDraft.validate] is empty; switches [year] to the year the invoice is stored in.
     * Throws [PdfFailedAfterSave] when only the PDF failed.
     */
    fun save(draft: InvoiceDraft, replacing: String?): SaveOutcome {
        val invoice = draft.toInvoice()
        // An edit is started from the list of the selected year, so the original lives there or in the new year.
        val original = replacing?.let { number ->
            listOf(year, invoice.issueDate.year).distinct().firstNotNullOfOrNull { y -> repo.issued(y).firstOrNull { it.number == number } }
        }
        val seller = if (invoice.external) null else seller()
        repo.saveIssued(invoice, replacing)
        // Show the year the invoice is stored in (by issue date), which may differ from the one selected.
        year = invoice.issueDate.year
        val pdf = seller?.let { s ->
            val target = repo.pdfPath(invoice)
            try {
                renderer(invoice, s, target)
            } catch (e: Exception) {
                throw PdfFailedAfterSave(invoice, e)
            }
            target
        }
        val warning = listOfNotNull(invoice, original).firstNotNullOfOrNull { exportedQuarter(it) }
        return SaveOutcome(pdf, warning)
    }

    /** Deletes [number]; returns the quarter whose existing export is now stale, if any. */
    fun delete(number: String): Quarter? {
        val stale = invoices.firstOrNull { it.number == number }?.let { exportedQuarter(it) }
        repo.deleteIssued(year, number)
        reload()
        return stale
    }

    private fun seller(): SellerConfig = repo.loadConfig() ?: throw RepositoryException(Messages.get("ui.error.sellerMissing"))
}
