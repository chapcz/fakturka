package cz.chap.fakturka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.AppSettings
import cz.chap.fakturka.storage.DataRepository
import cz.chap.fakturka.ui.state.DataFolder
import cz.chap.fakturka.ui.state.UpdateState
import cz.chap.fakturka.ui.state.dataFolder
import cz.chap.fakturka.ui.state.runCatchingUi
import cz.chap.fakturka.ui.state.uiMessage
import java.nio.file.Path
import javax.swing.JFileChooser

private fun defaultDataDir(): Path = Path.of(System.getProperty("user.home"), "Documents", "invoicing")

private fun chooseDirectory(start: String): String? {
    val chooser = JFileChooser(start).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = Messages.get("ui.firstRun.choose")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}

@Composable
fun App(settings: AppSettings) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            var folder by remember { mutableStateOf(dataFolder(settings)) }
            when (val current = folder) {
                is DataFolder.Ready -> {
                    val repo = remember(current.path) { DataRepository(current.path) }
                    MainShell(repo, settings) { folder = DataFolder.NotChosen }
                }
                else -> {
                    val missing = (current as? DataFolder.Missing)?.path
                    FirstRunScreen(missing) { chosen ->
                        // The user confirmed this folder, so creating it here is intended (unlike a missing saved one).
                        runCatchingUi {
                            val path = Path.of(chosen)
                            java.nio.file.Files.createDirectories(path)
                            settings.dataDir = path
                            path
                        }.onSuccess { folder = DataFolder.Ready(it) }.map { null }.let { r -> r.exceptionOrNull()?.uiMessage() }
                    }
                }
            }
        }
    }
}

@Composable
private fun FirstRunScreen(missing: Path?, onChosen: (String) -> String?) {
    var error by remember { mutableStateOf<String?>(null) }
    var path by remember(missing) { mutableStateOf((missing ?: defaultDataDir()).toString()) }
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(Messages.get("ui.firstRun.title"), style = MaterialTheme.typography.headlineMedium)
        if (missing != null) ErrorBanner(Messages.get("ui.firstRun.missing").format(missing))
        Text(Messages.get("ui.firstRun.text"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CompactTextField(path, { path = it }, Modifier.width(420.dp))
            OutlinedButton(onClick = { chooseDirectory(path)?.let { path = it } }) {
                Text(Messages.get("ui.firstRun.browse"))
            }
        }
        error?.let { ErrorBanner(it) }
        Button(onClick = { error = onChosen(path) }, enabled = path.isNotBlank()) { Text(Messages.get("ui.firstRun.confirm")) }
    }
}

@Composable
private fun MainShell(repo: DataRepository, settings: AppSettings, onChangeFolder: () -> Unit) {
    val loaded = remember(repo) { runCatchingUi { repo.loadConfig() } }
    loaded.exceptionOrNull()?.let { error ->
        Column(
            Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ErrorBanner(error.uiMessage())
            Button(onClick = onChangeFolder) { Text(Messages.get("ui.error.changeFolder")) }
        }
        return
    }
    var configured by remember(repo) { mutableStateOf(loaded.getOrNull() != null) }
    var screen by remember(repo) { mutableStateOf(if (configured) Screen.ISSUED else Screen.SETTINGS) }
    Row(Modifier.fillMaxSize()) {
        Sidebar(screen, configured) { screen = it }
        Column(Modifier.fillMaxSize()) {
            val updates = remember(settings) { UpdateState(settings) }
            UpdateBanner(updates)
            Box(Modifier.fillMaxSize()) {
                when (screen) {
                    Screen.SETTINGS -> SettingsScreen(repo, settings) { configured = true }
                    Screen.CONTACTS -> ContactsScreen(repo)
                    Screen.TEMPLATES -> TemplatesScreen(repo)
                    Screen.ISSUED -> IssuedScreen(repo)
                    Screen.RECEIVED -> ReceivedScreen(repo)
                    Screen.VAT -> VatExportScreen(repo)
                    Screen.STATS -> StatsScreen(repo)
                }
            }
        }
    }
}
