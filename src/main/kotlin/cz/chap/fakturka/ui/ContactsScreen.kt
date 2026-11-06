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
import cz.chap.fakturka.domain.Address
import cz.chap.fakturka.domain.Contact
import cz.chap.fakturka.domain.ContactType
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.DataRepository
import cz.chap.fakturka.ui.state.ContactsState
import cz.chap.fakturka.ui.state.runCatchingUi
import cz.chap.fakturka.ui.state.uiMessage
import java.util.UUID

private val EMPTY_ADDRESS = Address("", "", "", "", "CZ")

@Composable
fun ContactsScreen(repo: DataRepository) {
    val loaded = remember(repo) { runCatchingUi { ContactsState(repo) } }
    val state = loaded.getOrNull()
    if (state == null) {
        ErrorBanner(loaded.exceptionOrNull()!!.uiMessage(), Modifier.padding(24.dp))
        return
    }
    var contacts by remember(state) { mutableStateOf(state.contacts) }
    var editing by remember(state) { mutableStateOf<Contact?>(null) }
    var failure by remember(state) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        editing?.let { current ->
            ContactForm(
                state, current,
                onDone = { contacts = state.contacts; editing = null },
            )
            return@Column
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(Messages.get("ui.nav.contacts"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Button(onClick = {
                failure = null
                editing = Contact(UUID.randomUUID().toString(), "", EMPTY_ADDRESS, null, null, null, null, ContactType.DOMESTIC)
            }) { Text(Messages.get("ui.contacts.new")) }
        }
        failure?.let { ErrorBanner(it) }
        if (contacts.isEmpty()) Text(Messages.get("ui.common.empty"))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(contacts, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(c.ico, c.dic).joinToString(" · ").ifBlank { c.address.city },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(Messages.get(if (c.type == ContactType.EU) "ui.contacts.type.eu" else "ui.contacts.type.domestic"))
                    EditAction { failure = null; editing = c }
                    DeleteAction(onClick = {
                        failure = null
                        runCatchingUi { state.delete(c.id) }
                            .onSuccess { refusal -> failure = refusal; contacts = state.contacts }
                            .onFailure { failure = it.uiMessage() }
                    })
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ContactForm(state: ContactsState, initial: Contact, onDone: () -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var showDelivery by remember(initial) { mutableStateOf(initial.deliveryAddress != null) }
    var errors by remember(initial) { mutableStateOf(emptyList<String>()) }
    var failure by remember(initial) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(draft.name.ifBlank { Messages.get("ui.contacts.new") }, style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Messages.get("ui.contacts.type"))
            ContactType.entries.forEach { t ->
                FilterChip(
                    selected = draft.type == t,
                    onClick = { draft = draft.copy(type = t) },
                    label = { Text(Messages.get(if (t == ContactType.EU) "ui.contacts.type.eu" else "ui.contacts.type.domestic")) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            FormField("ui.contacts.ico", draft.ico.orEmpty(), Modifier.widthIn(max = 240.dp)) { draft = draft.copy(ico = it.ifBlank { null }) }
            if (draft.type == ContactType.DOMESTIC) {
                AresButton(draft.ico.orEmpty()) { c ->
                    draft = draft.copy(name = c.name, ico = c.ico, dic = c.dic, address = c.address)
                }
            }
        }
        FormField("ui.contacts.name", draft.name) { draft = draft.copy(name = it) }
        AddressFields(draft.address) { draft = draft.copy(address = it) }
        FormField("ui.contacts.dic", draft.dic.orEmpty()) { draft = draft.copy(dic = it.ifBlank { null }) }
        FormField("ui.contacts.email", draft.email.orEmpty()) { draft = draft.copy(email = it.ifBlank { null }) }

        TextButton(onClick = {
            showDelivery = !showDelivery
            draft = draft.copy(deliveryAddress = if (showDelivery) (draft.deliveryAddress ?: draft.address) else null)
        }) { Text(Messages.get(if (showDelivery) "ui.contacts.delivery.hide" else "ui.contacts.delivery.show")) }
        if (showDelivery) {
            Text(Messages.get("ui.contacts.delivery"), style = MaterialTheme.typography.titleMedium)
            AddressFields(draft.deliveryAddress ?: draft.address) { draft = draft.copy(deliveryAddress = it) }
        }

        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        failure?.let { ErrorBanner(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                failure = null
                errors = state.validate(draft)
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
private fun AddressFields(a: Address, onChange: (Address) -> Unit) {
    FormField("ui.contacts.street", a.street) { onChange(a.copy(street = it)) }
    FormField("ui.contacts.houseNumber", a.houseNumber) { onChange(a.copy(houseNumber = it)) }
    FormField("ui.contacts.city", a.city) { onChange(a.copy(city = it)) }
    FormField("ui.contacts.postcode", a.postcode) { onChange(a.copy(postcode = it)) }
    FormField("ui.contacts.country", a.country) { onChange(a.copy(country = it)) }
}

@Composable
internal fun FormField(key: String, value: String, modifier: Modifier = Modifier, placeholder: String? = null, onChange: (String) -> Unit) {
    CompactTextField(
        value = value,
        onValueChange = onChange,
        label = Messages.get(key),
        placeholder = placeholder,
        modifier = modifier.widthIn(max = 420.dp).fillMaxWidth(),
    )
}
