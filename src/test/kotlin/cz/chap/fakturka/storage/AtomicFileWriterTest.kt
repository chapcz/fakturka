package cz.chap.fakturka.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class AtomicFileWriterTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `writes new file`() {
        val target = dir.resolve("data.xml")
        AtomicFileWriter.write(target, "a".toByteArray())
        assertEquals("a", target.readText())
        assertFalse(Files.exists(dir.resolve("data.xml.bak")))
    }

    @Test
    fun `keeps previous version as bak`() {
        val target = dir.resolve("data.xml")
        AtomicFileWriter.write(target, "a".toByteArray())
        AtomicFileWriter.write(target, "b".toByteArray())
        assertEquals("b", target.readText())
        assertEquals("a", dir.resolve("data.xml.bak").readText())
    }

    @Test
    fun `leaves no temp files`() {
        val target = dir.resolve("data.xml")
        AtomicFileWriter.write(target, "a".toByteArray())
        AtomicFileWriter.write(target, "b".toByteArray())
        assertEquals(setOf("data.xml", "data.xml.bak"), dir.listDirectoryEntries().map { it.name }.toSet())
    }
}
