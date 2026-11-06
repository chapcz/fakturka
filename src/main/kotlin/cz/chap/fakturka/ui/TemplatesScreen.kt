package cz.chap.fakturka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.chap.fakturka.domain.VatRate
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.DataRepository
import cz.chap.fakturka.ui.state.TemplateDraft
import cz.chap.fakturka.ui.state.TemplateItemDraft
import cz.chap.fakturka.ui.state.TemplatesState
import cz.chap.fakturka.ui.state.runCatchingUi
import cz.chap.fakturka.ui.state.uiMessage
import java.util.UUID

private fun emptyItem() = TemplateItemDraft("", "h", "", VatRate.STANDARD, "")

@Composable
fun TemplatesScreen(repo: DataRepository) {
    val loaded = remember(repo) { runCatchingUi { TemplatesState(repo) } }
    val state = loaded.getOrNull()
    if (state == null) {
        ErrorBanner(loaded.exceptionOrNull()!!.uiMessage(), Modifier.padding(24.dp))
        return
    }
    var templates by remember(state) { mutableStateOf(state.templates) }
    var editing by remember(state) { mutableStateOf<TemplateDraft?>(null) }
    var failure by remember(state) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        editing?.let { current ->
            TemplateForm(state, current, onDone = { templates = state.templates; editing = null })
            return@Column
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(Messages.get("ui.nav.templates"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Button(onClick = {
                failure = null
                editing = TemplateDraft(UUID.randomUUID().toString(), "", "", "", "", listOf(emptyItem()))
            }) { Text(Messages.get("ui.templates.new")) }
        }
        failure?.let { ErrorBanner(it) }
        if (templates.isEmpty()) Text(Messages.get("ui.common.empty"))
        val customersResult = remember(templates) { runCatchingUi { state.customers.associate { it.id to it.name } } }
        customersResult.exceptionOrNull()?.let { ErrorBanner(it.uiMessage()) }
        val customerNames = customersResult.getOrDefault(emptyMap())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(templates, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = MaterialTheme.typography.titleMedium)
                        Text(customerNames[t.customerId].orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                    EditAction { failure = null; editing = TemplateDraft.from(t) }
                    DeleteAction(onClick = {
                        failure = null
                        runCatchingUi { state.delete(t.id) }
                            .onSuccess { templates = state.templates }
                            .onFailure { failure = it.uiMessage() }
                    })
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateForm(state: TemplatesState, initial: TemplateDraft, onDone: () -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var errors by remember(initial) { mutableStateOf(emptyList<String>()) }
    var failure by remember(initial) { mutableStateOf<String?>(null) }
    val customersResult = remember(initial) { runCatchingUi { state.customers } }
    val customers = customersResult.getOrDefault(emptyList())
    var pickerOpen by remember(initial) { mutableStateOf(false) }

    fun updateItem(index: Int, block: (TemplateItemDraft) -> TemplateItemDraft) {
        draft = draft.copy(items = draft.items.mapIndexed { i, item -> if (i == index) block(item) else item })
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(draft.name.ifBlank { Messages.get("ui.templates.new") }, style = MaterialTheme.typography.headlineMedium)
        customersResult.exceptionOrNull()?.let { ErrorBanner(it.uiMessage()) }
        FormField("ui.templates.name", draft.name) { draft = draft.copy(name = it) }

        ExposedDropdownMenuBox(expanded = pickerOpen, onExpandedChange = { pickerOpen = it }) {
            CompactTextField(
                value = customers.firstOrNull { it.id == draft.customerId }?.name.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = Messages.get("ui.templates.customer"),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(pickerOpen) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).widthIn(max = 420.dp).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                customers.forEach { c ->
                    DropdownMenuItem(text = { Text(c.name) }, onClick = { draft = draft.copy(customerId = c.id); pickerOpen = false })
                }
            }
        }
        FormField("ui.templates.orderNumber", draft.orderNumber) { draft = draft.copy(orderNumber = it) }
        FormField("ui.templates.dueDays", draft.dueDaysText) { draft = draft.copy(dueDaysText = it) }

        Text(Messages.get("ui.templates.items"), style = MaterialTheme.typography.titleMedium)
        draft.items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FormField("ui.templates.item.description", item.description, Modifier.weight(3f)) { v -> updateItem(index) { it.copy(description = v) } }
                FormField("ui.templates.item.unit", item.unit, Modifier.weight(1f)) { v -> updateItem(index) { it.copy(unit = v) } }
                FormField("ui.templates.item.unitPrice", item.unitPriceText, Modifier.weight(1.5f)) { v -> updateItem(index) { it.copy(unitPriceText = v) } }
                FormField("ui.templates.item.quantity", item.quantityText, Modifier.weight(1.5f)) { v -> updateItem(index) { it.copy(quantityText = v) } }
                VatRatePicker(item.vatRate) { r -> updateItem(index) { it.copy(vatRate = r) } }
                TextButton(onClick = { draft = draft.copy(items = draft.items.filterIndexed { i, _ -> i != index }) }) {
                    Text(Messages.get("ui.common.remove"))
                }
            }
        }
        OutlinedButton(onClick = { draft = draft.copy(items = draft.items + emptyItem()) }) { Text(Messages.get("ui.templates.addItem")) }

        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        failure?.let { ErrorBanner(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                failure = null
                errors = runCatchingUi { state.validate(draft) }.getOrElse { failure = it.uiMessage(); return@Button }
                if (errors.isEmpty()) {
                    runCatchingUi { state.save(draft) }
                        .onSuccess { onDone() }
                        .onFailure { failure = it.uiMessage() }
                }
            }) { Text(Messages.get("ui.common.save")) }
            OutlinedButton(onClick = onDone) { Text(Messages.get("ui.common.cancel")) }
        }
    }
}

@Composable
private fun VatRatePicker(selected: VatRate, onSelect: (VatRate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("${selected.percent} %") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Templates are domestic; an EU invoice gets 0 % from InvoiceFactory.
            listOf(VatRate.STANDARD, VatRate.REDUCED).forEach { r ->
                DropdownMenuItem(text = { Text("${r.percent} %") }, onClick = { onSelect(r); open = false })
            }
        }
    }
}
