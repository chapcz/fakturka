package cz.chap.fakturka

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.storage.AppSettings
import cz.chap.fakturka.ui.App
import java.nio.file.Path

fun main() {
    val settings = System.getProperty("fakturka.configDir")?.let { AppSettings(Path.of(it)) } ?: AppSettings()
    application {
        Window(onCloseRequest = ::exitApplication, title = Messages.get("app.title")) {
            App(settings)
        }
    }
}
