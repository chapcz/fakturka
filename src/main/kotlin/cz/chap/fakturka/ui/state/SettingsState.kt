package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.Address
import cz.chap.fakturka.domain.InvoiceNumbering
import cz.chap.fakturka.domain.NumberFormat
import cz.chap.fakturka.domain.SellerConfig
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.DataRepository
import java.time.Clock
import java.time.LocalDate

/** Loads the seller configuration on creation; throws when config.xml is unreadable. */
class SettingsState(private val repo: DataRepository, private val clock: Clock = Clock.systemDefaultZone()) {
    var draft: SellerConfig = repo.loadConfig() ?: EMPTY

    /** Raw text of numeric fields; null means "not edited, use the draft value". */
    var dueDaysText: String? = null
    var numberingStartYearText: String? = null
    var numberingStartSequenceText: String? = null

    val effectiveDueDays: String get() = dueDaysText ?: draft.dueDays.toString()
    val effectiveStartYear: String get() = numberingStartYearText ?: draft.numberingStartYear?.toString().orEmpty()
    val effectiveStartSequence: String get() = numberingStartSequenceText ?: draft.numberingStartSequence?.toString().orEmpty()

    private fun optionalInt(text: String): Int? = text.trim().toIntOrNull()
    private fun validOptionalInt(text: String) = text.isBlank() || text.trim().toIntOrNull() != null

    /** Localized validation messages; empty when the draft may be saved. */
    fun validate(): List<String> = buildList {
        // These appear on the PDF and in the EPO VetaP.
        if (draft.name.isBlank()) add(Messages.get("ui.settings.error.name"))
        if (draft.firstName.isBlank()) add(Messages.get("ui.settings.error.firstName"))
        if (draft.lastName.isBlank()) add(Messages.get("ui.settings.error.lastName"))
        if (draft.titles.length > MAX_TITLES_LENGTH) add(Messages.get("ui.settings.error.titles"))
        if (draft.address.street.isBlank()) add(Messages.get("ui.settings.error.street"))
        if (draft.address.city.isBlank()) add(Messages.get("ui.settings.error.city"))
        if (draft.address.postcode.isBlank()) add(Messages.get("ui.settings.error.postcode"))
        NumberFormat.parse(draft.numberFormat).onFailure { add(Messages.get(it.message!!)) }
        if (draft.ico.isBlank()) add(Messages.get("ui.settings.error.ico"))
        if (!draft.dic.startsWith("CZ")) add(Messages.get("ui.settings.error.dic"))
        if (draft.iban.isBlank()) add(Messages.get("ui.settings.error.iban"))
        val days = optionalInt(effectiveDueDays)
        if (days == null || days < 1) add(Messages.get("ui.settings.error.dueDays"))
        if (!validOptionalInt(effectiveStartYear)) add(Messages.get("ui.settings.error.numberingStartYear"))
        if (!validOptionalInt(effectiveStartSequence)) add(Messages.get("ui.settings.error.numberingStartSequence"))
        // The start sequence applies only in the start year, so one without the other has no effect.
        if (effectiveStartYear.isBlank() != effectiveStartSequence.isBlank()) add(Messages.get("ui.settings.error.numberingStartPair"))
    }

    /** Next own invoice number for today from the saved configuration; null before the first save. */
    fun nextNumber(): String? =
        if (repo.loadConfig() == null) null else repo.nextNumber(LocalDate.now(clock))

    /** Next number as the unsaved draft would give it, or null when the pattern or the start fields are invalid. */
    fun previewNumber(): String? {
        if (NumberFormat.parse(draft.numberFormat).isFailure) return null
        if (!validOptionalInt(effectiveStartYear) || !validOptionalInt(effectiveStartSequence)) return null
        val today = LocalDate.now(clock)
        val seller = draft.copy(numberingStartYear = optionalInt(effectiveStartYear), numberingStartSequence = optionalInt(effectiveStartSequence))
        return runCatching { InvoiceNumbering.next(today, repo.issued(today.year), seller) }.getOrNull()
    }

    /** Applies the numeric texts to the draft and writes it; call only after [validate] is empty. */
    fun save() {
        val updated = draft.copy(
            dueDays = optionalInt(effectiveDueDays) ?: draft.dueDays,
            numberingStartYear = optionalInt(effectiveStartYear),
            numberingStartSequence = optionalInt(effectiveStartSequence),
        )
        repo.saveConfig(updated)
        draft = updated
        dueDaysText = null
        numberingStartYearText = null
        numberingStartSequenceText = null
    }

    /** Full printed name composed from the titles and name, e.g. "Ing. Jan Novák, Ph.D.". */
    fun composedName(): String {
        val core = listOf(draft.title, draft.firstName, draft.lastName).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val after = draft.titleAfter.trim()
        return if (after.isEmpty()) core else "$core, $after"
    }

    companion object {
        /** EPO `titul` attribute maxLength in DPHDP3/DPHKH1/DPHSHV. */
        const val MAX_TITLES_LENGTH = 10

        val EMPTY = SellerConfig(
            "", "", "", "", Address("", "", "", "", "CZ"), "", "", "", null, null,
            "", "", "", "", "", "", "",
        )
    }
}
