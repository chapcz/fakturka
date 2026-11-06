package cz.chap.fakturka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.chap.fakturka.domain.SellerConfig
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.AppSettings
import cz.chap.fakturka.storage.DataRepository
import cz.chap.fakturka.ui.state.SettingsState
import cz.chap.fakturka.ui.state.runCatchingUi
import cz.chap.fakturka.ui.state.uiMessage

@Composable
fun SettingsScreen(repo: DataRepository, appSettings: AppSettings, onSaved: () -> Unit) {
    val created = remember(repo) { runCatchingUi { SettingsState(repo) } }
    created.exceptionOrNull()?.let { error ->
        Column(Modifier.fillMaxSize().padding(24.dp)) { ErrorBanner(error.uiMessage()) }
        return
    }
    val state = created.getOrThrow()
    var draft by remember(state) { mutableStateOf(state.draft) }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    var saved by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var dueDays by remember(state) { mutableStateOf(state.effectiveDueDays) }
    var startYear by remember(state) { mutableStateOf(state.effectiveStartYear) }
    var startSequence by remember(state) { mutableStateOf(state.effectiveStartSequence) }
    fun update(block: (SellerConfig) -> SellerConfig) {
        draft = block(draft)
        state.draft = draft
        saved = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(Messages.get("ui.nav.settings"), style = MaterialTheme.typography.headlineMedium)
        val width = Modifier.widthIn(max = 760.dp).fillMaxWidth()

        SectionCard("ui.settings.section.seller", width) {
            FieldRow {
                Field("ui.settings.title", draft.title, Modifier.weight(1f)) { v -> update { it.copy(title = v) } }
                Field("ui.settings.firstName", draft.firstName, Modifier.weight(2f)) { v -> update { it.copy(firstName = v) } }
                Field("ui.settings.lastName", draft.lastName, Modifier.weight(2f)) { v -> update { it.copy(lastName = v) } }
                Field("ui.settings.titleAfter", draft.titleAfter, Modifier.weight(1f)) { v -> update { it.copy(titleAfter = v) } }
            }
            FieldRow(verticalAlignment = Alignment.CenterVertically) {
                Field(
                    "ui.settings.name", draft.name, Modifier.weight(1f),
                    hint = Messages.get("ui.settings.nameHint"),
                ) { v -> update { it.copy(name = v) } }
                TextButton(onClick = { update { it.copy(name = state.composedName()) } }) {
                    Text(Messages.get("ui.settings.fillName"))
                }
            }
            FieldRow {
                Field("ui.settings.ico", draft.ico, Modifier.weight(1f)) { v -> update { it.copy(ico = v) } }
                Field("ui.settings.dic", draft.dic, Modifier.weight(1f)) { v -> update { it.copy(dic = v) } }
                AresButton(draft.ico) { c ->
                    update { it.copy(name = c.name, ico = c.ico, dic = c.dic ?: it.dic, address = c.address) }
                }
            }
        }

        SectionCard("ui.settings.section.address", width) {
            FieldRow {
                Field("ui.settings.street", draft.address.street, Modifier.weight(3f)) { v -> update { it.copy(address = it.address.copy(street = v)) } }
                Field("ui.settings.houseNumber", draft.address.houseNumber, Modifier.weight(1f)) { v -> update { it.copy(address = it.address.copy(houseNumber = v)) } }
            }
            FieldRow {
                Field("ui.settings.postcode", draft.address.postcode, Modifier.weight(1f)) { v -> update { it.copy(address = it.address.copy(postcode = v)) } }
                Field("ui.settings.city", draft.address.city, Modifier.weight(3f)) { v -> update { it.copy(address = it.address.copy(city = v)) } }
                Field("ui.settings.country", draft.address.country, Modifier.weight(1f)) { v -> update { it.copy(address = it.address.copy(country = v)) } }
            }
        }

        SectionCard("ui.settings.section.contact", width) {
            FieldRow {
                Field("ui.settings.email", draft.email, Modifier.weight(1f)) { v -> update { it.copy(email = v) } }
                Field("ui.settings.phone", draft.phone.orEmpty(), Modifier.weight(1f)) { v -> update { it.copy(phone = v.ifBlank { null }) } }
                Field("ui.settings.web", draft.web.orEmpty(), Modifier.weight(1f)) { v -> update { it.copy(web = v.ifBlank { null }) } }
            }
        }

        SectionCard("ui.settings.section.bank", width) {
            FieldRow {
                Field("ui.settings.bankName", draft.bankName, Modifier.weight(1f)) { v -> update { it.copy(bankName = v) } }
                Field("ui.settings.accountNumber", draft.accountNumber, Modifier.weight(1f)) { v -> update { it.copy(accountNumber = v) } }
            }
            FieldRow {
                Field("ui.settings.iban", draft.iban, Modifier.weight(2f)) { v -> update { it.copy(iban = v) } }
                Field("ui.settings.swift", draft.swift, Modifier.weight(1f)) { v -> update { it.copy(swift = v) } }
            }
        }

        SectionCard("ui.settings.section.taxOffice", width, hint = Messages.get("ui.settings.taxOfficeHint")) {
            FieldRow {
                Field("ui.settings.taxOfficeCode", draft.taxOfficeCode, Modifier.weight(1f)) { v -> update { it.copy(taxOfficeCode = v) } }
                Field("ui.settings.taxOfficeBranch", draft.taxOfficeBranch, Modifier.weight(1f)) { v -> update { it.copy(taxOfficeBranch = v) } }
                Field("ui.settings.czNace", draft.czNace, Modifier.weight(1f)) { v -> update { it.copy(czNace = v) } }
            }
        }

        SectionCard("ui.settings.section.invoicing", width) {
            FieldRow {
                Field(
                    "ui.settings.numberFormat", draft.numberFormat, Modifier.weight(1f),
                    hint = Messages.get("ui.settings.numberFormatHint"),
                ) { v -> update { it.copy(numberFormat = v) } }
            }
            FieldRow {
                Field("ui.settings.constantSymbol", draft.constantSymbol, Modifier.weight(1f)) { v -> update { it.copy(constantSymbol = v) } }
                Field("ui.settings.dueDays", dueDays, Modifier.weight(1f)) { v -> dueDays = v; state.dueDaysText = v; saved = false }
            }
            FieldRow {
                Field(
                    "ui.settings.numberingStartYear", startYear, Modifier.weight(1f),
                    hint = Messages.get("ui.settings.numberingHint"),
                ) { v -> startYear = v; state.numberingStartYearText = v; saved = false }
                Field("ui.settings.numberingStartSequence", startSequence, Modifier.weight(1f)) { v ->
                    startSequence = v; state.numberingStartSequenceText = v; saved = false
                }
            }
            val preview = remember(draft, startYear, startSequence) { runCatchingUi { state.previewNumber() } }
            preview.onSuccess { number ->
                if (number != null) {
                    Text(
                        Messages.get("ui.settings.nextNumber").format(number, cz.chap.fakturka.domain.NumberFormat.variableSymbol(number)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }.onFailure { ErrorBanner(it.uiMessage()) }
        }

        SignatureSection(repo, width)
        AppSection(appSettings, width)

        Column(width, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            failure?.let { ErrorBanner(it) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = {
                    errors = state.validate()
                    failure = null
                    if (errors.isEmpty()) {
                        runCatchingUi { state.save() }
                            .onSuccess { draft = state.draft; saved = true; onSaved() }
                            .onFailure { failure = it.uiMessage() }
                    }
                }) { Text(Messages.get("ui.settings.save")) }
                if (saved) Text(Messages.get("ui.settings.saved"), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Per-computer settings (version, update check); they apply at once and are not part of the form. */
@Composable
private fun AppSection(settings: AppSettings, modifier: Modifier) {
    var checkUpdates by remember(settings) { mutableStateOf(runCatchingUi { settings.checkUpdates }.getOrDefault(true)) }
    var error by remember { mutableStateOf<String?>(null) }
    SectionCard("ui.settings.section.app", modifier) {
        Text(Messages.get("ui.settings.version").format(cz.chap.fakturka.AppInfo.VERSION), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checkUpdates, onCheckedChange = { value ->
                runCatchingUi { settings.checkUpdates = value }
                    .onSuccess { checkUpdates = value; error = null }
                    .onFailure { error = it.uiMessage() }
            })
            Text(Messages.get("ui.settings.checkUpdates"), style = MaterialTheme.typography.bodyMedium)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** The stamp and signature image takes effect at once; it is a file in the data folder, not part of the form. */
@Composable
private fun SignatureSection(repo: DataRepository, modifier: Modifier) {
    var current by remember(repo) { mutableStateOf(runCatchingUi { repo.signatureImage() }.getOrNull()) }
    var error by remember { mutableStateOf<String?>(null) }
    SectionCard("ui.settings.section.signature", modifier, hint = Messages.get("ui.settings.signature.hint")) {
        FieldRow(verticalAlignment = Alignment.CenterVertically) {
            Text(
                current?.let { Messages.get("ui.settings.signature.current").format(it.fileName) } ?: Messages.get("ui.settings.signature.none"),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = {
                chooseSignatureFile()?.let { file ->
                    runCatchingUi { repo.setSignatureImage(file.toPath()); repo.signatureImage() }
                        .onSuccess { current = it; error = null }
                        .onFailure { error = it.message ?: it.uiMessage() }
                }
            }) { Text(Messages.get("ui.settings.signature.choose")) }
            if (current != null) {
                TextButton(onClick = {
                    runCatchingUi { repo.removeSignatureImage() }
                        .onSuccess { current = null; error = null }
                        .onFailure { error = it.uiMessage() }
                }) { Text(Messages.get("ui.settings.signature.remove")) }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun chooseSignatureFile(): java.io.File? {
    val chooser = javax.swing.JFileChooser(System.getProperty("user.home")).apply {
        dialogTitle = Messages.get("ui.settings.signature.dialogTitle")
        fileFilter = javax.swing.filechooser.FileNameExtensionFilter("PNG, JPG", "png", "jpg", "jpeg")
    }
    return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

@Composable
private fun SectionCard(titleKey: String, modifier: Modifier, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Messages.get(titleKey), style = MaterialTheme.typography.titleMedium)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable
private fun FieldRow(
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = verticalAlignment, content = content)
}

@Composable
private fun Field(key: String, value: String, modifier: Modifier, hint: String? = null, onChange: (String) -> Unit) {
    CompactTextField(
        value = value,
        onValueChange = onChange,
        label = Messages.get(key),
        supportingText = hint,
        modifier = modifier,
    )
}
