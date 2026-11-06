package cz.chap.fakturka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.chap.fakturka.domain.IssuedInvoice
import cz.chap.fakturka.domain.Quarter
import cz.chap.fakturka.domain.formatAmount
import cz.chap.fakturka.domain.totals
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.importing.ImportPlan
import cz.chap.fakturka.importing.InvoiceImporter
import cz.chap.fakturka.importing.ImportFormats
import cz.chap.fakturka.storage.DataRepository
import cz.chap.fakturka.tax.ExportService
import cz.chap.fakturka.ui.state.IssuedState
import cz.chap.fakturka.ui.state.SaveOutcome
import cz.chap.fakturka.ui.state.formatDate
import cz.chap.fakturka.ui.state.openInDesktop
import cz.chap.fakturka.ui.state.runCatchingUi
import cz.chap.fakturka.ui.state.uiMessage
import java.nio.file.Files
import java.nio.file.Path

private fun openPdf(path: Path) {
    if (!Files.exists(path)) throw IllegalStateException(Messages.get("ui.issued.error.noPdf"))
    openInDesktop(path)
}

@Composable
fun IssuedScreen(repo: DataRepository) {
    val loaded = remember(repo) { runCatchingUi { IssuedState(repo, ExportService(repo)) } }
    val state = loaded.getOrNull()
    if (state == null) {
        ErrorBanner(loaded.exceptionOrNull()!!.uiMessage(), Modifier.padding(24.dp))
        return
    }
    var invoices by remember(state) { mutableStateOf(state.invoices) }
    var year by remember(state) { mutableStateOf(state.year) }
    var failure by remember(state) { mutableStateOf<String?>(null) }
    var info by remember(state) { mutableStateOf<String?>(null) }
    var pendingDelete by remember(state) { mutableStateOf<PendingDelete?>(null) }
    var dialog by remember(state) { mutableStateOf<DialogRequest?>(null) }
    var importPlan by remember(state) { mutableStateOf<ImportPlan?>(null) }
    val importer = remember(repo) { InvoiceImporter(repo) }

    fun startImport() {
        failure = null
        info = null
        val file = chooseImportFile() ?: return
        runCatchingUi { importer.plan(ImportFormats.parse(file.toPath())) }
            .onSuccess { importPlan = it }
            .onFailure { failure = it.uiMessage() }
    }

    fun changeYear(value: Int) {
        failure = null
        info = null
        runCatchingUi { state.year = value }
            .onSuccess { year = state.year; invoices = state.invoices }
            .onFailure { failure = it.uiMessage() }
    }

    fun saved(outcome: SaveOutcome) {
        year = state.year
        invoices = state.invoices
        info = Messages.get("ui.issued.saved") + (outcome.exportedQuarterWarning?.let {
            " " + staleWarning(it)
        } ?: "")
        outcome.pdf?.let { pdf -> runCatchingUi { openPdf(pdf) }.onFailure { failure = it.uiMessage() } }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Messages.get("ui.nav.issued"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Text(Messages.get("ui.issued.year"))
            OutlinedButton(onClick = { changeYear(year - 1) }) { Text(Messages.get("ui.issued.prevYear")) }
            Text(year.toString(), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { changeYear(year + 1) }) { Text(Messages.get("ui.issued.nextYear")) }
            OutlinedButton(onClick = ::startImport) { Text(Messages.get("ui.import.button")) }
            Button(onClick = { failure = null; info = null; dialog = DialogRequest(null) }) { Text(Messages.get("ui.issued.new")) }
        }
        importPlan?.let { plan ->
            ImportDialog(
                plan,
                onConfirm = {
                    importPlan = null
                    runCatchingUi { importer.apply(plan); state.reload() }
                        .onSuccess { invoices = state.invoices; info = Messages.get("ui.import.done").format(plan.toImport.size) }
                        .onFailure { failure = it.uiMessage() }
                },
                onDismiss = { importPlan = null },
            )
        }
        failure?.let { ErrorBanner(it) }
        info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (invoices.isEmpty()) Text(Messages.get("ui.common.empty"))
        Row(Modifier.fillMaxWidth()) {
            HeaderCell("ui.issued.col.number", 1.2f)
            HeaderCell("ui.issued.col.customer", 3f)
            HeaderCell("ui.issued.col.taxDate", 1.3f)
            HeaderCell("ui.issued.col.dueDate", 1.3f)
            HeaderCell("ui.issued.col.base", 1.5f, TextAlign.End)
            HeaderCell("ui.issued.col.total", 1.5f, TextAlign.End)
            HeaderCell("ui.issued.col.paid", 1.3f, TextAlign.End)
            Spacer(Modifier.weight(2.2f))
        }
        HorizontalDivider()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(invoices, key = { it.number }) { inv ->
                InvoiceRow(
                    inv, state.canDelete(inv), inv.isOverdue(state.today),
                    onTogglePaid = {
                        failure = null
                        info = null
                        runCatchingUi { state.setPaid(inv, inv.paidDate == null) }
                            .onSuccess { invoices = state.invoices }
                            .onFailure { failure = it.uiMessage() }
                    },
                    onOpen = { failure = null; runCatchingUi { openPdf(state.pdfPath(inv)) }.onFailure { failure = it.uiMessage() } },
                    onCreatePdf = { failure = null; runCatchingUi { openPdf(state.renderPdf(inv)) }.onFailure { failure = it.uiMessage() } },
                    onEdit = { failure = null; info = null; dialog = DialogRequest(inv) },
                    onDelete = {
                        failure = null
                        info = null
                        val stale = runCatchingUi { state.exportedQuarter(inv) }
                        stale.exceptionOrNull()?.let { failure = it.uiMessage() }
                        pendingDelete = PendingDelete(inv, stale.getOrNull())
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
                    Text(Messages.get("ui.issued.delete.confirm").format(pending.invoice.number))
                    pending.staleQuarter?.let { Text(Messages.get("ui.issued.delete.stale").format(quarterLabel(it))) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    runCatchingUi { state.delete(pending.invoice.number) }
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
        NewInvoiceDialog(
            state, request.editing,
            onDone = { outcome -> dialog = null; if (outcome != null) saved(outcome) else { year = state.year; invoices = state.invoices } },
        )
    }
}

private class PendingDelete(val invoice: IssuedInvoice, val staleQuarter: Quarter?)

private fun quarterLabel(q: Quarter) = Messages.get("ui.issued.quarter").format(q.year, q.quarter)

private fun staleWarning(q: Quarter) = Messages.get("ui.issued.exportWarning").format(quarterLabel(q))

private class DialogRequest(val editing: IssuedInvoice?)

@Composable
private fun RowScope.HeaderCell(key: String, weight: Float, align: TextAlign = TextAlign.Start) {
    Text(Messages.get(key), Modifier.weight(weight), style = MaterialTheme.typography.labelLarge, textAlign = align)
}

@Composable
private fun InvoiceRow(
    inv: IssuedInvoice,
    canDelete: Boolean,
    overdue: Boolean,
    onTogglePaid: () -> Unit,
    onOpen: () -> Unit,
    onCreatePdf: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val totals = inv.totals
    // Unpaid after the due date: a light red row.
    val background = if (overdue) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f) else Color.Transparent
    Row(Modifier.fillMaxWidth().background(background).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(inv.number, Modifier.weight(1.2f))
        Text(inv.customer.name, Modifier.weight(3f))
        Text(formatDate(inv.taxDate), Modifier.weight(1.3f))
        Text(formatDate(inv.dueDate), Modifier.weight(1.3f), color = if (overdue) MaterialTheme.colorScheme.error else Color.Unspecified)
        Text(formatAmount(totals.base), Modifier.weight(1.5f), textAlign = TextAlign.End)
        Text(formatAmount(totals.total), Modifier.weight(1.5f), textAlign = TextAlign.End)
        Text(inv.paidDate?.let(::formatDate).orEmpty(), Modifier.weight(1.3f), textAlign = TextAlign.End)
        Row(Modifier.weight(2.2f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (inv.paidDate == null) RowAction(Icons.Outlined.CheckCircle, "ui.issued.markPaid", onTogglePaid)
            else RowAction(Icons.Filled.CheckCircle, "ui.issued.markUnpaid", onTogglePaid, tint = MaterialTheme.colorScheme.primary)
            if (inv.external) {
                Text(Messages.get("ui.issued.external"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RowAction(PdfIcon, "ui.issued.createPdf", onCreatePdf)
            } else RowAction(PdfIcon, "ui.issued.openPdf", onOpen)
            EditAction(onEdit)
            DeleteAction(onDelete, enabled = canDelete)
        }
    }
}
