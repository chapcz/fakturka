package cz.chap.fakturka.ui.state

import cz.chap.fakturka.domain.Contact
import cz.chap.fakturka.domain.Template
import cz.chap.fakturka.domain.TemplateItem
import cz.chap.fakturka.domain.VatRate
import cz.chap.fakturka.domain.money
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.DataRepository

/** Item form values; numbers stay as text until validated. */
data class TemplateItemDraft(
    val description: String,
    val unit: String,
    val unitPriceText: String,
    val vatRate: VatRate,
    val quantityText: String,
) {
    fun toItem(): TemplateItem = TemplateItem(
        description.trim(),
        unit.trim(),
        parsePositiveDecimal(unitPriceText)!!.money(),
        vatRate,
        parsePositiveDecimal(quantityText),
    )

    companion object {
        fun from(i: TemplateItem) = TemplateItemDraft(
            i.description, i.unit, i.unitPrice.money().toPlainString(), i.vatRate,
            i.defaultQuantity?.toPlainString().orEmpty(),
        )
    }
}

data class TemplateDraft(
    val id: String,
    val name: String,
    val customerId: String,
    val orderNumber: String,
    val dueDaysText: String,
    val items: List<TemplateItemDraft>,
) {
    fun toTemplate(): Template = Template(
        id, name.trim(), customerId, orderNumber.trim().ifBlank { null },
        dueDaysText.trim().toIntOrNull(), items.map { it.toItem() },
    )

    companion object {
        fun from(t: Template) = TemplateDraft(
            t.id, t.name, t.customerId, t.orderNumber.orEmpty(), t.dueDays?.toString().orEmpty(),
            t.items.map { TemplateItemDraft.from(it) },
        )
    }
}

class TemplatesState(private val repo: DataRepository) {
    var templates: List<Template> = repo.templates()
        private set

    val customers: List<Contact> get() = repo.contacts()

    fun validate(d: TemplateDraft): List<String> = buildList {
        if (d.name.isBlank()) add(Messages.get("ui.templates.error.name"))
        if (customers.none { it.id == d.customerId }) add(Messages.get("ui.templates.error.customer"))
        if (d.items.isEmpty()) add(Messages.get("ui.templates.error.items"))
        val days = d.dueDaysText.trim()
        if (days.isNotEmpty() && (days.toIntOrNull()?.let { it < 1 } != false)) add(Messages.get("ui.templates.error.dueDays"))
        val rowLabel = Messages.get("ui.templates.error.itemRow")
        addAll(
            d.items.flatMapIndexed { index, i ->
                buildList {
                    if (i.description.isBlank()) add(Messages.get("ui.templates.error.item.description"))
                    if (i.unit.isBlank()) add(Messages.get("ui.templates.error.item.unit"))
                    if (parsePositiveDecimal(i.unitPriceText)?.money()?.signum()?.let { it > 0 } != true) add(Messages.get("ui.templates.error.item.unitPrice"))
                    if (i.quantityText.isNotBlank() && parsePositiveDecimal(i.quantityText) == null) {
                        add(Messages.get("ui.templates.error.item.quantity"))
                    }
                    // Templates are for domestic use; InvoiceFactory forces 0 % when an EU customer is invoiced.
                    if (i.vatRate == VatRate.ZERO) add(Messages.get("ui.templates.error.item.vatRate"))
                }.map { "$rowLabel ${index + 1}: $it" }
            },
        )
    }

    /** Writes the draft; call only after [validate] is empty. */
    fun save(d: TemplateDraft) {
        repo.saveTemplate(d.toTemplate())
        templates = repo.templates()
    }

    fun delete(id: String) {
        repo.deleteTemplate(id)
        templates = repo.templates()
    }
}
