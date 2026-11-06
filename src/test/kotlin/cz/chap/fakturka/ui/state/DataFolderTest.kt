package cz.chap.fakturka.ui.state

import cz.chap.fakturka.storage.AppSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class DataFolderTest {
    @TempDir
    lateinit var dir: Path

    private fun settings() = AppSettings(dir.resolve("config"))

    @Test fun `no saved folder is a first run`() = assertEquals(DataFolder.NotChosen, dataFolder(settings()))

    @Test fun `existing saved folder is ready`() {
        val data = Files.createDirectories(dir.resolve("data"))
        settings().dataDir = data
        assertEquals(DataFolder.Ready(data), dataFolder(settings()))
    }

    @Test fun `saved folder that no longer exists is reported missing and not re-created`() {
        val data = dir.resolve("unmounted/invoicing")
        settings().dataDir = data
        assertEquals(DataFolder.Missing(data), dataFolder(settings()))
        assertFalse(data.exists())
    }
}
