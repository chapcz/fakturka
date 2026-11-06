package cz.chap.fakturka.storage

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.div

@JacksonXmlRootElement(localName = "settings")
data class SettingsFile(
    val dataDir: String? = null,
    /** Look for a newer release on GitHub at start (at most once a day). */
    val checkUpdates: Boolean = true,
    val lastUpdateCheck: LocalDate? = null,
    @param:JacksonXmlProperty(isAttribute = true) override val version: Int = 1,
) : VersionedFile

/** Per-computer settings in ~/.config/fakturka; the invoicing data itself lives in the data folder. */
class AppSettings(
    configDir: Path = Path.of(System.getProperty("user.home"), ".config", "fakturka"),
) {
    private val file = configDir / "settings.xml"
    private val store = XmlStore()

    private fun read(): SettingsFile = store.read(file, SettingsFile::class) ?: SettingsFile()
    private fun update(change: (SettingsFile) -> SettingsFile) = store.write(file, change(read()))

    var dataDir: Path?
        get() = read().dataDir?.let { Path.of(it) }
        set(value) = update { it.copy(dataDir = value?.toString()) }

    var checkUpdates: Boolean
        get() = read().checkUpdates
        set(value) = update { it.copy(checkUpdates = value) }

    var lastUpdateCheck: LocalDate?
        get() = read().lastUpdateCheck
        set(value) = update { it.copy(lastUpdateCheck = value) }
}
