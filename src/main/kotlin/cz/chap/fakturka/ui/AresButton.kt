package cz.chap.fakturka.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.registry.AresClient
import cz.chap.fakturka.registry.AresCompany
import cz.chap.fakturka.ui.state.uiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ares = AresClient()

/** "Načíst z ARES" button: looks up [ico] off the UI thread and hands the result to [onFound]. */
@Composable
fun AresButton(ico: String, onFound: (AresCompany) -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(enabled = !loading && ico.isNotBlank(), onClick = {
            loading = true
            message = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { ares.lookup(ico) } }
                loading = false
                result.onSuccess { company ->
                    if (company == null) {
                        isError = true; message = Messages.get("ui.ares.notFound").format(ico.trim())
                    } else {
                        isError = false; message = Messages.get("ui.ares.loaded"); onFound(company)
                    }
                }.onFailure { isError = true; message = it.message ?: it.uiMessage() }
            }
        }) { Text(Messages.get(if (loading) "ui.ares.loading" else "ui.ares.load")) }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}
