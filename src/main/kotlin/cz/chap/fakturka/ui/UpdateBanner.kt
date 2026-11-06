package cz.chap.fakturka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.ui.state.UpdateOutcome
import cz.chap.fakturka.ui.state.UpdateState
import cz.chap.fakturka.ui.state.runCatchingUi
import cz.chap.fakturka.update.Release
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Checks for a newer release once a day at start and offers it in a strip above the screen. */
@Composable
fun UpdateBanner(state: UpdateState) {
    var release by remember { mutableStateOf<Release?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state) {
        if (state.due()) {
            // A failed check (offline, GitHub down) is not worth an error on start; it is retried tomorrow.
            release = withContext(Dispatchers.IO) { runCatching { state.check() }.getOrNull() }
        }
    }

    val offered = release ?: return
    if (dismissed) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                message ?: Messages.get("ui.update.available").format(offered.version, state.currentVersion),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else if (message == null) {
                Button(onClick = {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatchingUi { state.install(offered) } }
                        busy = false
                        message = result.fold(
                            onSuccess = {
                                when (it) {
                                    UpdateOutcome.AppImageReplaced -> Messages.get("ui.update.appImageReady")
                                    is UpdateOutcome.PackageDownloaded -> Messages.get("ui.update.debDownloaded").format(it.file)
                                    UpdateOutcome.PageOpened -> null
                                }
                            },
                            onFailure = { Messages.get("ui.update.failed").format(it.message ?: it.javaClass.simpleName) },
                        )
                        if (message == null) dismissed = true
                    }
                }) { Text(Messages.get(if (state.canInstall(offered)) "ui.update.install" else "ui.update.openPage")) }
            }
            TextButton(onClick = { dismissed = true }, enabled = !busy) { Text(Messages.get("ui.update.dismiss")) }
        }
    }
}
