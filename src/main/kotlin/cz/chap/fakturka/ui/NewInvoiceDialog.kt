package cz.chap.fakturka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.chap.fakturka.domain.Contact
import cz.chap.fakturka.domain.ContactType
import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.domain.VatRate
import cz.chap.fakturka.domain.formatAmount
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.ui.state.InvoiceDraft
import cz.chap.fakturka.ui.state.InvoiceItemDraft
import cz.chap.fakturka.ui.state.IssuedState
import cz.chap.fakturka.ui.state.PdfFailedAfterSave
import cz.chap.fakturka.ui.state.SaveOutcome
import cz.chap.fakturka.ui.state.runCatchingUi
import cz.chap.fakturka.ui.state.uiMessage

/** [onDone] gets the outcome of a successful save, or null when the dialog was closed without saving. */
@Composable
fun NewInvoiceDialog(state: IssuedState, editing: IssuedInvoice?, onDone: (SaveOutcome?) -> Unit) {
    Dialog(onDismissRequest = { onDone(null) }, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 960.dp).heightIn(max = 760.dp)) {
            Column(Modifier.padding(24.dp)) {
                val initial = remember(editing) { editing?.let { runCatchingUi { state.edit(it) } } }
                var draft by remember(editing) { mutableStateOf(initial?.getOrNull()) }
                var failure by remember(editing) { mutableStateOf(initial?.exceptionOrNull()?.uiMessage()) }
                Text(
                    Messages.get(if (editing == null) "ui.invoice.title.new" else "ui.invoice.title.edit"),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                val current = draft
                if (current == null) {
                    TemplateChooser(state, failure, onFailure = { failure = it }, onChosen = { draft = it; failure = null }, onCancel = { onDone(null) })
                } else {
                    InvoiceForm(state, current, editing?.number, { draft = it }, onDone)
                }
            }
        }
    }
}

@Composable
private fun TemplateChooser(
    state: IssuedState,
    failure: String?,
    onFailure: (String) -> Unit,
    onChosen: (InvoiceDraft) -> Unit,
    onCancel: () -> Unit,
) {
    val templates = remember { runCatchingUi { state.templates } }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Messages.get("ui.invoice.choose"), style = MaterialTheme.typography.titleMedium)
        failure?.let { ErrorBanner(it) }
        templates.exceptionOrNull()?.let { ErrorBanner(it.uiMessage()) }
        val list = templates.getOrDefault(emptyList())
        if (list.isEmpty()) Text(Messages.get("ui.invoice.noTemplates"))
        fun choose(block: () -> InvoiceDraft) = runCatchingUi(block).onSuccess(onChosen).onFailure { onFailure(it.uiMessage()) }
        list.forEach { t ->
            Button(onClick = { choose { state.newFromTemplate(t) } }, modifier = Modifier.fillMaxWidth()) { Text(t.name) }
        }
        OutlinedButton(onClick = { choose { state.newEmpty() } }, modifier = Modifier.fillMaxWidth()) { Text(Messages.get("ui.invoice.noTemplate")) }
        OutlinedButton(onClick = { choose { state.newExternal() } }, modifier = Modifier.fillMaxWidth()) { Text(Messages.get("ui.invoice.externalInvoice")) }
        OutlinedButton(onClick = onCancel) { Text(Messages.get("ui.common.cancel")) }
    }
}

@Composable
private fun ColumnScope.InvoiceForm(
    state: IssuedState,
    draft: InvoiceDraft,
    editingNumber: String?,
    onChange: (InvoiceDraft) -> Unit,
    onDone: (SaveOutcome?) -> Unit,
) {
    var errors by remember { mutableStateOf(emptyList<String>()) }
    var failure by remember { mutableStateOf<String?>(null) }
    // Set after a failure that happened once the invoice was already stored (e.g. PDF render): saves become edits.
    var replacing by remember(editingNumber) { mutableStateOf(editingNumber) }
    val totals = draft.totals

    fun updateItem(index: Int, block: (InvoiceItemDraft) -> InvoiceItemDraft) =
        onChange(draft.copy(items = draft.items.mapIndexed { i, item -> if (i == index) block(item) else item }))

    // Top padding keeps the floating labels of the first row inside the scroll area.
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (draft.external && editingNumber == null) {
                FormField("ui.invoice.number", draft.number, Modifier.weight(1f)) { onChange(draft.copy(number = it)) }
            } else {
                CompactTextField(
                    draft.number, {}, Modifier.weight(1f), readOnly = true,
                    label = Messages.get("ui.invoice.number"),
                )
            }
            FormField("ui.invoice.issueDate", draft.issueDate, Modifier.weight(1f), Messages.get("ui.common.dateFormat")) { text ->
                runCatchingUi { state.withIssueDate(draft, text) }
                    .onSuccess { onChange(it); failure = null }
                    .onFailure { onChange(draft.withIssueDate(text)); failure = it.uiMessage() }
            }
            FormField("ui.invoice.taxDate", draft.taxDate, Modifier.weight(1f), Messages.get("ui.common.dateFormat")) { onChange(draft.withTaxDate(it)) }
            FormField("ui.invoice.dueDate", draft.dueDate, Modifier.weight(1f), Messages.get("ui.common.dateFormat")) { onChange(draft.withDueDate(it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CustomerPicker(state, draft, Modifier.weight(2f)) { onChange(draft.withCustomer(it)) }
            FormField("ui.invoice.orderNumber", draft.orderNumber, Modifier.weight(1f)) { onChange(draft.copy(orderNumber = it)) }
        }
        Text(Messages.get("ui.invoice.items"), style = MaterialTheme.typography.titleMedium)
        draft.items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FormField("ui.invoice.item.description", item.description, Modifier.weight(3f)) { v -> updateItem(index) { it.copy(description = v) } }
                FormField("ui.invoice.item.quantity", item.quantityText, Modifier.weight(1.2f)) { v -> updateItem(index) { it.copy(quantityText = v) } }
                FormField("ui.invoice.item.unit", item.unit, Modifier.weight(0.8f)) { v -> updateItem(index) { it.copy(unit = v) } }
                FormField("ui.invoice.item.unitPrice", item.unitPriceText, Modifier.weight(1.4f)) { v -> updateItem(index) { it.copy(unitPriceText = v) } }
                FormField("ui.invoice.item.discount", item.discountText, Modifier.weight(1f)) { v -> updateItem(index) { it.copy(discountText = v) } }
                if (draft.customer?.type == ContactType.EU) {
                    // EU customers are invoiced at a fixed 0 % (reverse charge).
                    Text(Messages.get("ui.common.percent").format(VatRate.ZERO.percent))
                } else {
                    VatRatePicker(item.vatRate) { r -> updateItem(index) { it.copy(vatRate = r) } }
                }
                TextButton(onClick = { onChange(draft.copy(items = draft.items.filterIndexed { i, _ -> i != index })) }) {
                    Text(Messages.get("ui.common.remove"))
                }
            }
        }
        OutlinedButton(onClick = { onChange(draft.copy(items = draft.items + InvoiceItemDraft.blank().copy(vatRate = draft.items.lastOrNull()?.vatRate ?: VatRate.STANDARD))) }) {
            Text(Messages.get("ui.invoice.addItem"))
        }
        HorizontalDivider()
        Text("${Messages.get("ui.invoice.total.base")}: ${formatAmount(totals.base)}")
        Text("${Messages.get("ui.invoice.total.vat")}: ${formatAmount(totals.vat)}")
        Text("${Messages.get("ui.invoice.total.total")}: ${formatAmount(totals.total)}", style = MaterialTheme.typography.titleMedium)
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        failure?.let { ErrorBanner(it) }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            failure = null
            errors = draft.validate()
            if (errors.isEmpty()) {
                runCatchingUi { state.save(draft, replacing) }
                    .onSuccess { onDone(it) }
                    .onFailure { error ->
                        failure = error.uiMessage()
                        if (error is PdfFailedAfterSave) replacing = error.invoice.number
                    }
            }
        }) { Text(Messages.get(if (draft.external) "ui.common.save" else "ui.invoice.save")) }
        OutlinedButton(onClick = { onDone(null) }) { Text(Messages.get("ui.common.cancel")) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerPicker(state: IssuedState, draft: InvoiceDraft, modifier: Modifier, onPick: (Contact) -> Unit) {
    val contacts = remember { runCatchingUi { state.contacts } }
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open && !draft.customerLocked, onExpandedChange = { open = it && !draft.customerLocked }, modifier = modifier) {
        CompactTextField(
            value = draft.customer?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = Messages.get("ui.invoice.customer"),
            trailingIcon = { if (!draft.customerLocked) ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open && !draft.customerLocked, onDismissRequest = { open = false }) {
            contacts.getOrDefault(emptyList()).forEach { c ->
                DropdownMenuItem(text = { Text(c.name) }, onClick = { onPick(c); open = false })
            }
        }
    }
}

@Composable
private fun VatRatePicker(selected: VatRate, onSelect: (VatRate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(Messages.get("ui.common.percent").format(selected.percent)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(VatRate.STANDARD, VatRate.REDUCED).forEach { r ->
                DropdownMenuItem(text = { Text(Messages.get("ui.common.percent").format(r.percent)) }, onClick = { onSelect(r); open = false })
            }
        }
    }
}
