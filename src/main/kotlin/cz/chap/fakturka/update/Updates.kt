package cz.chap.fakturka.update

import com.fasterxml.jackson.databind.ObjectMapper
import cz.chap.fakturka.AppInfo
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

/** A published GitHub release with the download links of its Linux packages. */
data class Release(val version: String, val pageUrl: String, val debUrl: String?, val appImageUrl: String?)

/** How this copy of the app was installed; it decides which package an update downloads. */
enum class InstallKind { DEB, APPIMAGE, OTHER }

object ReleaseParser {
    private val mapper = ObjectMapper()

    /** Parses the GitHub "latest release" JSON; drafts and pre-releases are not offered. */
    fun parse(json: String): Release? {
        val node = mapper.readTree(json)
        if (node.path("draft").asBoolean(false) || node.path("prerelease").asBoolean(false)) return null
        val tag = node.path("tag_name").asText("").removePrefix("v").trim()
        if (tag.isEmpty()) return null
        val assets = node.path("assets").map { it.path("name").asText("") to it.path("browser_download_url").asText("") }
        fun asset(suffix: String) = assets.firstOrNull { (name, url) -> name.endsWith(suffix) && url.isNotEmpty() }?.second
        return Release(tag, node.path("html_url").asText(""), asset(".deb"), asset(".AppImage"))
    }

    /** True when [candidate] is a higher version than [current]; versions compare by their numeric parts ("1.10.0" > "1.9.2"). */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = numbers(candidate)
        val b = numbers(current) ?: return a != null // a "dev" build is older than any release
        if (a == null) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String): List<Int>? =
        version.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
}

class UpdateException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Talks to GitHub; every call blocks, so run it off the UI thread. */
class UpdateClient(
    private val apiUrl: String = "https://api.github.com/repos/${AppInfo.REPOSITORY}/releases/latest",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build(),
) {
    /** The latest release when it is newer than [current], else null (also when nothing is published yet). */
    fun newerRelease(current: String = AppInfo.VERSION): Release? {
        val request = HttpRequest.newBuilder(URI.create(apiUrl))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Fakturka")
            .timeout(Duration.ofSeconds(15))
            .GET().build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw UpdateException(e.message ?: e.javaClass.simpleName, e)
        }
        return when (response.statusCode()) {
            200 -> ReleaseParser.parse(response.body())?.takeIf { ReleaseParser.isNewer(it.version, current) }
            404 -> null
            else -> throw UpdateException("GitHub HTTP ${response.statusCode()}")
        }
    }

    /** Downloads [url] to [target] through a temporary file next to it, so a failed download leaves no half file. */
    fun download(url: String, target: Path) {
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling(target.fileName.toString() + ".part")
        val request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "Fakturka").GET().build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofFile(partial))
        } catch (e: IOException) {
            Files.deleteIfExists(partial)
            throw UpdateException(e.message ?: e.javaClass.simpleName, e)
        }
        if (response.statusCode() != 200) {
            Files.deleteIfExists(partial)
            throw UpdateException("HTTP ${response.statusCode()}")
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

object Installation {
    /** An AppImage sets APPIMAGE to its own path; the .deb installs the launcher under /opt/fakturka. */
    fun detect(env: Map<String, String> = System.getenv(), javaHome: String = System.getProperty("java.home")): InstallKind = when {
        !env["APPIMAGE"].isNullOrBlank() -> InstallKind.APPIMAGE
        javaHome.startsWith("/opt/fakturka") -> InstallKind.DEB
        else -> InstallKind.OTHER
    }

    fun appImagePath(env: Map<String, String> = System.getenv()): Path? = env["APPIMAGE"]?.takeIf { it.isNotBlank() }?.let(Path::of)

    /** Replaces the running AppImage file with [downloaded]; the new version starts with the next launch. */
    fun replaceAppImage(downloaded: Path, current: Path) {
        Files.setPosixFilePermissions(downloaded, PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.move(downloaded, current, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** The user's download folder (XDG), or ~/Downloads. */
    fun downloadDir(): Path {
        val home = Path.of(System.getProperty("user.home"))
        val xdg = runCatching {
            ProcessBuilder("xdg-user-dir", "DOWNLOAD").redirectErrorStream(true).start().let { p ->
                p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
            }
        }.getOrNull()
        return xdg?.takeIf { it.isNotEmpty() && it != home.toString() }?.let(Path::of) ?: home.resolve("Downloads")
    }
}
