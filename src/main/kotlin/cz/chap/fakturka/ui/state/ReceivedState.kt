package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.Contact
import cz.chap.fakturka.domain.Quarter
import cz.chap.fakturka.domain.ReceivedInvoice
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.DataRepository
import cz.chap.fakturka.tax.ExportService
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

/** KH B.2 threshold: total including VAT strictly greater than this needs DIC and document number. */
private val KH_THRESHOLD = BigDecimal("10000.00")

/** Rules that need several fields at once; the supplier name is checked by the caller. */
internal fun receivedRules(r: ReceivedInvoice): List<String> = buildList {
    if (r.amounts.values.none { it.base.signum() != 0 }) add(Messages.get("ui.received.error.noAmount"))
    if (r.claimPeriod.let { it.year * 4 + it.quarter } < Quarter.of(r.taxDate).let { it.year * 4 + it.quarter }) {
        add(Messages.get("ui.received.error.claimBeforeTaxDate"))
    }
    if (r.total > KH_THRESHOLD) {
        if (r.supplierDic.isNullOrBlank()) add(Messages.get("ui.received.error.dicRequired"))
        if (r.documentNumber.isBlank()) add(Messages.get("ui.received.error.numberRequired"))
    }
}

class ReceivedState(
    private val repo: DataRepository,
    private val exportService: ExportService,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    val currentYear: Int get() = LocalDate.now(clock).year

    /** Claim-period year shown in the list (received invoices are stored by claim year). */
    var year: Int = currentYear
        set(value) {
            // Load first: when the repository fails, year and invoices both stay as they were.
            invoices = load(value)
            field = value
        }

    var invoices: List<ReceivedInvoice> = load(year)
        private set

    fun reload() {
        invoices = load(year)
    }

    private fun load(year: Int) = repo.received(year).sortedWith(compareByDescending<ReceivedInvoice> { it.taxDate }.thenBy { it.documentNumber })

    val contacts: List<Contact> get() = repo.contacts()

    fun newDraft(): ReceivedDraft = ReceivedDraft.new(LocalDate.now(clock))

    fun edit(r: ReceivedInvoice): ReceivedDraft = ReceivedDraft.from(r)

    /** Localized messages; empty when [r] may be saved. */
    fun validate(r: ReceivedInvoice): List<String> = buildList {
        if (r.supplierName.isBlank()) add(Messages.get("ui.received.error.supplier"))
        addAll(receivedRules(r))
    }

    /** The claim quarter of [r] when its VAT export already exists (and would go stale), else null. */
    fun exportedQuarter(r: ReceivedInvoice): Quarter? = r.claimPeriod.takeIf { exportService.isExported(it) }

    /**
     * Call only when [validate] is empty; switches [year] to the claim year. Returns a quarter whose existing export
     * must be regenerated, if any.
     */
    fun save(r: ReceivedInvoice): Quarter? {
        val original = listOf(year, r.claimPeriod.year).distinct()
            .firstNotNullOfOrNull { y -> repo.received(y).firstOrNull { it.id == r.id } }
        repo.saveReceived(r)
        // Show the claim year the invoice is stored in, which may differ from the one selected.
        year = r.claimPeriod.year
        return listOfNotNull(r, original).firstNotNullOfOrNull { exportedQuarter(it) }
    }

    /** Deletes [id] from the selected year; returns the quarter whose existing export is now stale, if any. */
    fun delete(id: String): Quarter? {
        val stale = invoices.firstOrNull { it.id == id }?.let { exportedQuarter(it) }
        repo.deleteReceived(year, id)
        reload()
        return stale
    }

    fun openFolder() = openInDesktop(repo.receivedDir(year))
}
