package cz.chap.fakturka.ui.state

import cz.chap.fakturka.AppInfo
import cz.chap.fakturka.storage.AppSettings
import cz.chap.fakturka.update.InstallKind
import cz.chap.fakturka.update.Installation
import cz.chap.fakturka.update.Release
import cz.chap.fakturka.update.UpdateClient
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate

/** What an update did: the AppImage was replaced (restart to use it), or the .deb was saved and handed to the installer. */
sealed interface UpdateOutcome {
    data object AppImageReplaced : UpdateOutcome
    data class PackageDownloaded(val file: Path) : UpdateOutcome
    data object PageOpened : UpdateOutcome
}

/** Daily update check and installation; every method that touches the network blocks. */
class UpdateState(
    private val settings: AppSettings,
    private val client: UpdateClient = UpdateClient(),
    private val clock: Clock = Clock.systemDefaultZone(),
    val kind: InstallKind = Installation.detect(),
    private val openFile: (Path) -> Unit = ::openInDesktop,
    private val openPage: (String) -> Unit = { java.awt.Desktop.getDesktop().browse(java.net.URI(it)) },
) {
    val currentVersion: String get() = AppInfo.VERSION

    /** True when checking is on and today's check has not run yet. */
    fun due(): Boolean = settings.checkUpdates && settings.lastUpdateCheck != LocalDate.now(clock)

    /** Asks GitHub for a newer release and remembers that today's check ran. */
    fun check(): Release? = client.newerRelease(currentVersion).also { settings.lastUpdateCheck = LocalDate.now(clock) }

    /** True when this installation can install [release] itself; otherwise only its page is offered. */
    fun canInstall(release: Release): Boolean = when (kind) {
        InstallKind.APPIMAGE -> release.appImageUrl != null && Installation.appImagePath() != null
        InstallKind.DEB -> release.debUrl != null
        InstallKind.OTHER -> false
    }

    fun install(release: Release): UpdateOutcome = when {
        kind == InstallKind.APPIMAGE && canInstall(release) -> {
            val current = Installation.appImagePath()!!
            val downloaded = current.resolveSibling(current.fileName.toString() + ".new")
            client.download(release.appImageUrl!!, downloaded)
            Installation.replaceAppImage(downloaded, current)
            UpdateOutcome.AppImageReplaced
        }
        kind == InstallKind.DEB && canInstall(release) -> {
            val url = release.debUrl!!
            val file = Installation.downloadDir().resolve(url.substringAfterLast('/'))
            client.download(url, file)
            openFile(file)
            UpdateOutcome.PackageDownloaded(file)
        }
        else -> {
            openPage(release.pageUrl)
            UpdateOutcome.PageOpened
        }
    }
}
