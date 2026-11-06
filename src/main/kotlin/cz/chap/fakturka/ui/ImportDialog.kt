package cz.chap.fakturka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.chap.fakturka.domain.formatAmount
import cz.chap.fakturka.domain.totals
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.importing.ImportPlan
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Asks for a ProFact XML export; null when the user cancels. */
fun chooseImportFile(): File? {
    val chooser = JFileChooser(System.getProperty("user.home")).apply {
        dialogTitle = Messages.get("ui.import.choose")
        fileFilter = FileNameExtensionFilter("XML", "xml")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** Preview of a ProFact import: what will be imported and what is skipped, before anything is written. */
@Composable
fun ImportDialog(plan: ImportPlan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Messages.get("ui.import.title")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(Messages.get("ui.import.ready").format(plan.toImport.size), style = MaterialTheme.typography.titleSmall)
                plan.toImport.forEach { inv ->
                    Text("${inv.number} · ${inv.customer.name} · ${formatAmount(inv.totals.total)}", style = MaterialTheme.typography.bodySmall)
                }
                if (plan.newContacts.isNotEmpty()) {
                    Text(Messages.get("ui.import.newContacts").format(plan.newContacts.joinToString { it.name }), style = MaterialTheme.typography.bodySmall)
                }
                if (plan.skipped.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(Messages.get("ui.import.skipped").format(plan.skipped.size), style = MaterialTheme.typography.titleSmall)
                    plan.skipped.forEach { s ->
                        Text("${s.number.ifBlank { "?" }}: ${s.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(Messages.get("ui.import.note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = plan.toImport.isNotEmpty()) {
                Text(Messages.get("ui.import.confirm").format(plan.toImport.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Messages.get("ui.common.cancel")) } },
    )
}
