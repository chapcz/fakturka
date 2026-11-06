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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.chap.fakturka.domain.Quarter
import cz.chap.fakturka.domain.ReceivedInvoice
import cz.chap.fakturka.domain.VatRate
import cz.chap.fakturka.domain.formatAmount
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.DataRepository
import cz.chap.fakturka.tax.ExportService
import cz.chap.fakturka.ui.state.RECEIVED_RATES
import cz.chap.fakturka.ui.state.ReceivedDraft
import cz.chap.fakturka.ui.state.ReceivedState
import cz.chap.fakturka.ui.state.formatDate
import cz.chap.fakturka.ui.state.parseMoney
import cz.chap.fakturka.ui.state.runCatchingUi
import cz.chap.fakturka.ui.state.uiMessage
import java.math.BigDecimal

private class PendingReceivedDelete(val invoice: ReceivedInvoice, val staleQuarter: Quarter?)

private class ReceivedDialogRequest(val editing: ReceivedInvoice?)

private fun quarterLabel(q: Quarter) = Messages.get("ui.issued.quarter").format(q.year, q.quarter)

private fun staleWarning(q: Quarter) = Messages.get("ui.issued.exportWarning").format(quarterLabel(q))

@Composable
fun ReceivedScreen(repo: DataRepository) {
    val loaded = remember(repo) { runCatchingUi { ReceivedState(repo, ExportService(repo)) } }
    val state = loaded.getOrNull()
    if (state == null) {
        ErrorBanner(loaded.exceptionOrNull()!!.uiMessage(), Modifier.padding(24.dp))
        return
    }
    var invoices by remember(state) { mutableStateOf(state.invoices) }
    var year by remember(state) { mutableStateOf(state.year) }
    var failure by remember(state) { mutableStateOf<String?>(null) }
    var info by remember(state) { mutableStateOf<String?>(null) }
    var pendingDelete by remember(state) { mutableStateOf<PendingReceivedDelete?>(null) }
    var dialog by remember(state) { mutableStateOf<ReceivedDialogRequest?>(null) }

    fun changeYear(value: Int) {
        failure = null
        info = null
        runCatchingUi { state.year = value }
            .onSuccess { year = state.year; invoices = state.invoices }
            .onFailure { failure = it.uiMessage() }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Messages.get("ui.nav.received"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Text(Messages.get("ui.issued.year"))
            OutlinedButton(onClick = { changeYear(year - 1) }) { Text(Messages.get("ui.issued.prevYear")) }
            Text(year.toString(), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { changeYear(year + 1) }) { Text(Messages.get("ui.issued.nextYear")) }
            OutlinedButton(onClick = { failure = null; runCatchingUi { state.openFolder() }.onFailure { failure = it.uiMessage() } }) {
                Text(Messages.get("ui.received.openFolder"))
            }
            Button(onClick = { failure = null; info = null; dialog = ReceivedDialogRequest(null) }) { Text(Messages.get("ui.received.new")) }
        }
        failure?.let { ErrorBanner(it) }
        info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (invoices.isEmpty()) Text(Messages.get("ui.common.empty"))
        Row(Modifier.fillMaxWidth()) {
            HeaderCell("ui.received.col.supplier", 3f)
            HeaderCell("ui.received.col.number", 1.6f)
            HeaderCell("ui.received.col.taxDate", 1.3f)
            HeaderCell("ui.received.col.claim", 1.1f)
            HeaderCell("ui.received.col.base", 1.4f, TextAlign.End)
            HeaderCell("ui.received.col.vat", 1.4f, TextAlign.End)
            HeaderCell("ui.received.col.total", 1.4f, TextAlign.End)
            Spacer(Modifier.weight(1f))
        }
        HorizontalDivider()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(invoices, key = { it.id }) { inv ->
                ReceivedRow(
                    inv,
                    onEdit = { failure = null; info = null; dialog = ReceivedDialogRequest(inv) },
                    onDelete = {
                        failure = null
                        info = null
                        val stale = runCatchingUi { state.exportedQuarter(inv) }
                        stale.exceptionOrNull()?.let { failure = it.uiMessage() }
                        pendingDelete = PendingReceivedDelete(inv, stale.getOrNull())
                    },
                )
                HorizontalDivider()
            }
        }
    }

    pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Messages.get("ui.received.delete.confirm").format(pending.invoice.documentNumber.ifBlank { pending.invoice.supplierName }))
                    pending.staleQuarter?.let { Text(Messages.get("ui.issued.delete.stale").format(quarterLabel(it))) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    runCatchingUi { state.delete(pending.invoice.id) }
                        .onSuccess { stale ->
                            invoices = state.invoices
                            stale?.let { info = staleWarning(it) }
                        }
                        .onFailure { failure = it.uiMessage() }
                }) { Text(Messages.get("ui.common.delete")) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(Messages.get("ui.common.cancel")) } },
        )
    }

    dialog?.let { request ->
        ReceivedDialog(
            state, request.editing,
            onDone = { saved ->
                dialog = null
                invoices = state.invoices
                year = state.year
                if (saved != null) info = Messages.get("ui.received.saved") + (saved.second?.let { " " + staleWarning(it) } ?: "")
            },
        )
    }
}

@Composable
private fun RowScope.HeaderCell(key: String, weight: Float, align: TextAlign = TextAlign.Start) {
    Text(Messages.get(key), Modifier.weight(weight), style = MaterialTheme.typography.labelLarge, textAlign = align)
}

@Composable
private fun ReceivedRow(inv: ReceivedInvoice, onEdit: () -> Unit, onDelete: () -> Unit) {
    val base = inv.amounts.values.fold(BigDecimal.ZERO) { acc, a -> acc + a.base }
    val vat = inv.amounts.values.fold(BigDecimal.ZERO) { acc, a -> acc + a.vat }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(inv.supplierName, Modifier.weight(3f))
        Text(inv.documentNumber, Modifier.weight(1.6f))
        Text(formatDate(inv.taxDate), Modifier.weight(1.3f))
        Text(quarterLabel(inv.claimPeriod), Modifier.weight(1.1f))
        Text(formatAmount(base), Modifier.weight(1.4f), textAlign = TextAlign.End)
        Text(formatAmount(vat), Modifier.weight(1.4f), textAlign = TextAlign.End)
        Text(formatAmount(inv.total), Modifier.weight(1.4f), textAlign = TextAlign.End)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            EditAction(onEdit)
            DeleteAction(onDelete)
        }
    }
}

/** [onDone] gets the saved invoice and its stale-export quarter, or null when closed without saving. */
@Composable
private fun ReceivedDialog(state: ReceivedState, editing: ReceivedInvoice?, onDone: (Pair<ReceivedInvoice, Quarter?>?) -> Unit) {
    Dialog(onDismissRequest = { onDone(null) }, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(0.9f).widthIn(max = 760.dp).heightIn(max = 700.dp)) {
            Column(Modifier.padding(24.dp)) {
                val initial = remember(editing) {
                    runCatchingUi { if (editing == null) state.newDraft() else state.edit(editing) }
                }
                var draft by remember(editing) { mutableStateOf(initial.getOrNull()) }
                val contacts = remember(state) { runCatchingUi { state.contacts } }
                var errors by remember(editing) { mutableStateOf(emptyList<String>()) }
                var failure by remember(editing) { mutableStateOf(initial.exceptionOrNull()?.uiMessage()) }
                Text(
                    Messages.get(if (editing == null) "ui.received.title.new" else "ui.received.title.edit"),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                val current = draft
                if (current == null) {
                    failure?.let { ErrorBanner(it) }
                    OutlinedButton(onClick = { onDone(null) }) { Text(Messages.get("ui.common.cancel")) }
                } else {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        errors.forEach { ErrorBanner(it) }
                        contacts.exceptionOrNull()?.let { ErrorBanner(it.uiMessage()) }
                        failure?.let { ErrorBanner(it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FormField("ui.received.supplier", current.supplierName, Modifier.weight(2f)) { draft = current.copy(supplierName = it) }
                            ContactPicker(contacts.getOrDefault(emptyList()), onPick = { draft = current.withSupplier(it) })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FormField("ui.received.supplierDic", current.supplierDic, Modifier.weight(1f)) { draft = current.copy(supplierDic = it) }
                            FormField("ui.received.number", current.documentNumber, Modifier.weight(1f)) { draft = current.copy(documentNumber = it) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FormField("ui.received.taxDate", current.taxDate, Modifier.weight(1f), Messages.get("ui.common.dateFormat")) { draft = current.withTaxDate(it) }
                            FormField("ui.received.claimYear", current.claimYear, Modifier.weight(1f)) { draft = current.withClaim(it, current.claimQuarter) }
                            QuarterPicker(current.claimQuarter) { draft = current.withClaim(current.claimYear, it) }
                        }
                        RECEIVED_RATES.forEach { rate -> AmountRow(current, rate) { draft = it } }
                        Text(Messages.get("ui.received.total") + ": " + formatAmount(liveTotal(current)), style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            failure = null
                            val found = current.validate()
                            errors = found
                            if (found.isEmpty()) {
                                val invoice = current.toInvoice()
                                runCatchingUi { invoice to state.save(invoice) }
                                    .onSuccess { onDone(it) }
                                    .onFailure { failure = it.uiMessage() }
                            }
                        }) { Text(Messages.get("ui.common.save")) }
                        OutlinedButton(onClick = { onDone(null) }) { Text(Messages.get("ui.common.cancel")) }
                    }
                }
            }
        }
    }
}

private fun liveTotal(d: ReceivedDraft): BigDecimal = RECEIVED_RATES.fold(BigDecimal.ZERO) { acc, r ->
    val a = d.amount(r)
    acc + (parseMoney(a.baseText) ?: BigDecimal.ZERO) + (parseMoney(a.vatText) ?: BigDecimal.ZERO)
}

@Composable
private fun AmountRow(draft: ReceivedDraft, rate: VatRate, onChange: (ReceivedDraft) -> Unit) {
    val amount = draft.amount(rate)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactTextField(
            amount.baseText, { onChange(draft.withBase(rate, it)) }, Modifier.weight(1f),
            label = Messages.get("ui.received.base").format(rate.percent),
        )
        CompactTextField(
            amount.vatText, { onChange(draft.withVat(rate, it)) }, Modifier.weight(1f),
            label = Messages.get("ui.received.vat").format(rate.percent),
        )
    }
}

@Composable
private fun ContactPicker(contacts: List<cz.chap.fakturka.domain.Contact>, onPick: (cz.chap.fakturka.domain.Contact) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = contacts.isNotEmpty()) {
            Text(Messages.get("ui.received.pickContact"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            contacts.forEach { c ->
                DropdownMenuItem(text = { Text(c.name) }, onClick = { onPick(c); open = false })
            }
        }
    }
}

@Composable
private fun QuarterPicker(selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(Messages.get("ui.received.claimQuarter"), style = MaterialTheme.typography.labelSmall)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(Messages.get("ui.received.quarterOption").format(selected)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                (1..4).forEach { q ->
                    DropdownMenuItem(text = { Text(Messages.get("ui.received.quarterOption").format(q)) }, onClick = { onSelect(q); open = false })
                }
            }
        }
    }
}
