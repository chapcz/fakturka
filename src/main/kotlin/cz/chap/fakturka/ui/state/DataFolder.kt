package cz.chap.fakturka.ui.state

import cz.chap.fakturka.storage.AppSettings
import java.nio.file.Files
import java.nio.file.Path

/** Where the saved data folder stands at startup. */
sealed interface DataFolder {
    /** First run: no folder chosen yet. */
    data object NotChosen : DataFolder

    /** A folder was chosen before but is gone (moved or unmounted); it must not be silently re-created. */
    data class Missing(val path: Path) : DataFolder

    data class Ready(val path: Path) : DataFolder
}

fun dataFolder(settings: AppSettings): DataFolder {
    val path = settings.dataDir ?: return DataFolder.NotChosen
    return if (Files.isDirectory(path)) DataFolder.Ready(path) else DataFolder.Missing(path)
}
