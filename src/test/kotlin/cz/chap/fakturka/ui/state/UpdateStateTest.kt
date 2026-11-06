package cz.chap.fakturka.ui.state

import cz.chap.fakturka.storage.AppSettings
import cz.chap.fakturka.update.InstallKind
import cz.chap.fakturka.update.Release
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class UpdateStateTest {
    @TempDir
    lateinit var dir: Path

    private val clock = Clock.fixed(Instant.parse("2026-11-09T08:00:00Z"), ZoneOffset.UTC)
    private val release = Release("1.2.0", "https://github.com/chapcz/fakturka/releases/tag/v1.2.0", "https://x/f.deb", null)

    @Test fun `settings keep the data folder when the update fields change`() {
        val settings = AppSettings(dir)
        settings.dataDir = dir.resolve("data")
        settings.checkUpdates = false
        settings.lastUpdateCheck = LocalDate.of(2026, 11, 8)
        val again = AppSettings(dir)
        assertEquals(dir.resolve("data"), again.dataDir)
        assertFalse(again.checkUpdates)
        assertEquals(LocalDate.of(2026, 11, 8), again.lastUpdateCheck)
    }

    @Test fun `check runs once a day and only when enabled`() {
        val settings = AppSettings(dir)
        val state = UpdateState(settings, clock = clock, kind = InstallKind.OTHER)
        assertTrue(state.due())
        settings.lastUpdateCheck = LocalDate.of(2026, 11, 9)
        assertFalse(state.due())
        settings.lastUpdateCheck = LocalDate.of(2026, 11, 8)
        settings.checkUpdates = false
        assertFalse(state.due())
    }

    @Test fun `a build not installed from a package only opens the release page`() {
        var opened: String? = null
        val state = UpdateState(AppSettings(dir), clock = clock, kind = InstallKind.OTHER, openPage = { opened = it })
        assertFalse(state.canInstall(release))
        assertEquals(UpdateOutcome.PageOpened, state.install(release))
        assertEquals(release.pageUrl, opened)
        assertTrue(UpdateState(AppSettings(dir), clock = clock, kind = InstallKind.DEB).canInstall(release))
        assertFalse(UpdateState(AppSettings(dir), clock = clock, kind = InstallKind.DEB).canInstall(release.copy(debUrl = null)))
    }
}
