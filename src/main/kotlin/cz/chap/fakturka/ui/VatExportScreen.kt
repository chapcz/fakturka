package cz.chap.fakturka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.chap.fakturka.domain.Quarter
import cz.chap.fakturka.domain.formatAmount
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.DataRepository
import cz.chap.fakturka.tax.ExportService
import cz.chap.fakturka.ui.state.VatExportState
import cz.chap.fakturka.ui.state.openInDesktop
import cz.chap.fakturka.ui.state.runCatchingUi
import cz.chap.fakturka.ui.state.uiMessage
import java.math.BigDecimal
import java.nio.file.Path

private fun quarterLabel(q: Quarter) = Messages.get("ui.issued.quarter").format(q.year, q.quarter)

@Composable
fun VatExportScreen(repo: DataRepository) {
    val loaded = remember(repo) { runCatchingUi { VatExportState(ExportService(repo)) } }
    val state = loaded.getOrNull()
    if (state == null) {
        ErrorBanner(loaded.exceptionOrNull()!!.uiMessage(), Modifier.padding(24.dp))
        return
    }
    var quarter by remember(state) { mutableStateOf(state.quarter) }
    var summary by remember(state) { mutableStateOf(state.summary) }
    var exported by remember(state) { mutableStateOf(state.alreadyExported) }
    var files by remember(state) { mutableStateOf<List<Path>>(emptyList()) }
    var errors by remember(state) { mutableStateOf<List<String>>(emptyList()) }
    var failure by remember(state) { mutableStateOf<String?>(null) }
    var confirmOverwrite by remember(state) { mutableStateOf(false) }

    fun changeQuarter(value: Quarter) {
        failure = null
        errors = emptyList()
        files = emptyList()
        runCatchingUi { state.quarter = value }
            .onSuccess { quarter = state.quarter; summary = state.summary; exported = state.alreadyExported }
            .onFailure { failure = it.uiMessage() }
    }

    fun create() {
        failure = null
        errors = emptyList()
        files = emptyList()
        runCatchingUi { state.export() }
            .onSuccess { result ->
                files = result.files
                errors = result.errors
                exported = state.alreadyExported
            }
            .onFailure { failure = it.uiMessage() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Messages.get("ui.nav.vat"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Text(Messages.get("ui.issued.year"))
            OutlinedButton(onClick = { changeQuarter(quarter.copy(year = quarter.year - 1)) }) { Text(Messages.get("ui.issued.prevYear")) }
            Text(quarter.year.toString(), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { changeQuarter(quarter.copy(year = quarter.year + 1)) }) { Text(Messages.get("ui.issued.nextYear")) }
            Spacer(Modifier.width(16.dp))
            Text(Messages.get("ui.vat.quarter"))
            (1..4).forEach { n ->
                FilterChip(
                    selected = quarter.quarter == n,
                    onClick = { changeQuarter(quarter.copy(quarter = n)) },
                    label = { Text(Messages.get("ui.received.quarterOption").format(n)) },
                )
            }
        }
        failure?.let { ErrorBanner(it) }

        Text(Messages.get("ui.vat.summary"), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.widthIn(max = 560.dp)) {
            SummaryRow("ui.vat.a4Count", summary.a4Count.toString())
            SummaryRow("ui.vat.a5Base", money(summary.a5Base))
            SummaryRow("ui.vat.b2Count", summary.b2Count.toString())
            SummaryRow("ui.vat.b3Base", money(summary.b3Base))
            HorizontalDivider()
            SummaryRow("ui.vat.outputVat", money(summary.outputVat))
            SummaryRow("ui.vat.inputVat", money(summary.inputVat))
            SummaryRow("ui.vat.toPay", money(summary.toPay), bold = true)
            HorizontalDivider()
            SummaryRow("ui.vat.euCount", summary.euCount.toString())
        }

        if (exported) Text(Messages.get("ui.vat.exported"), color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (exported) confirmOverwrite = true else create() }) { Text(Messages.get("ui.vat.create")) }
            OutlinedButton(
                enabled = exported,
                onClick = { failure = null; runCatchingUi { openInDesktop(repo.exportDir(quarter)) }.onFailure { failure = it.uiMessage() } },
            ) { Text(Messages.get("ui.vat.openFolder")) }
        }

        if (errors.isNotEmpty()) {
            ErrorBanner(Messages.get("ui.vat.errors") + "\n" + errors.joinToString("\n"))
        }
        if (files.isNotEmpty()) {
            Text(Messages.get("ui.vat.created"), style = MaterialTheme.typography.titleMedium)
            files.forEach { Text(it.toString()) }
        }
    }

    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            text = { Text(Messages.get("ui.vat.overwrite.confirm").format(quarterLabel(quarter))) },
            confirmButton = { TextButton(onClick = { confirmOverwrite = false; create() }) { Text(Messages.get("ui.vat.overwrite.yes")) } },
            dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text(Messages.get("ui.common.cancel")) } },
        )
    }
}

private fun money(v: BigDecimal) = formatAmount(v)

@Composable
private fun SummaryRow(key: String, value: String, bold: Boolean = false) {
    val style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(Messages.get(key), Modifier.weight(1f), style = style)
        Text(value, style = style, textAlign = TextAlign.End)
    }
}
