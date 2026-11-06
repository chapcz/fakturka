package cz.chap.fakturka.update

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class UpdatesTest {
    @TempDir
    lateinit var dir: Path

    private val json = """
        {"tag_name":"v1.2.0","html_url":"https://github.com/chapcz/fakturka/releases/tag/v1.2.0","draft":false,"prerelease":false,
         "assets":[
           {"name":"fakturka_1.2.0_amd64.deb","browser_download_url":"https://example.com/fakturka_1.2.0_amd64.deb"},
           {"name":"Fakturka-1.2.0-x86_64.AppImage","browser_download_url":"https://example.com/Fakturka-1.2.0-x86_64.AppImage"},
           {"name":"SHA256SUMS","browser_download_url":"https://example.com/SHA256SUMS"}]}
    """.trimIndent()

    @Test fun `release json gives the version and both packages`() {
        val r = ReleaseParser.parse(json)!!
        assertEquals("1.2.0", r.version)
        assertEquals("https://example.com/fakturka_1.2.0_amd64.deb", r.debUrl)
        assertEquals("https://example.com/Fakturka-1.2.0-x86_64.AppImage", r.appImageUrl)
        assertEquals("https://github.com/chapcz/fakturka/releases/tag/v1.2.0", r.pageUrl)
    }

    @Test fun `pre-releases and drafts are not offered`() {
        assertNull(ReleaseParser.parse(json.replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(ReleaseParser.parse(json.replace("\"draft\":false", "\"draft\":true")))
    }

    @Test fun `versions compare by numbers`() {
        assertTrue(ReleaseParser.isNewer("1.2.0", "1.1.9"))
        assertTrue(ReleaseParser.isNewer("1.10.0", "1.9.2"))
        assertTrue(ReleaseParser.isNewer("v2.0", "1.9.9"))
        assertFalse(ReleaseParser.isNewer("1.2.0", "1.2.0"))
        assertFalse(ReleaseParser.isNewer("1.2", "1.2.0"))
        assertFalse(ReleaseParser.isNewer("1.1.0", "1.2.0"))
        assertTrue(ReleaseParser.isNewer("1.0.0", "dev"))
        assertFalse(ReleaseParser.isNewer("nightly", "1.0.0"))
    }

    @Test fun `installation kind comes from the environment`() {
        assertEquals(InstallKind.APPIMAGE, Installation.detect(mapOf("APPIMAGE" to "/home/u/Fakturka.AppImage"), "/tmp/.mount_x/lib/runtime"))
        assertEquals(InstallKind.DEB, Installation.detect(emptyMap(), "/opt/fakturka/lib/runtime"))
        assertEquals(InstallKind.OTHER, Installation.detect(emptyMap(), "/usr/lib/jvm/java-21"))
    }

    @Test fun `replacing the AppImage swaps the file and makes it executable`() {
        val current = Files.writeString(dir.resolve("Fakturka.AppImage"), "old")
        val downloaded = Files.writeString(dir.resolve("Fakturka.AppImage.new"), "new")
        Installation.replaceAppImage(downloaded, current)
        assertEquals("new", Files.readString(current))
        assertTrue(Files.isExecutable(current))
        assertFalse(Files.exists(downloaded))
    }
}
