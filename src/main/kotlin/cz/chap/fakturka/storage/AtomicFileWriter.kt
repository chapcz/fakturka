package cz.chap.fakturka.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class StorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object AtomicFileWriter {
    fun write(target: Path, bytes: ByteArray) {
        val dir = target.toAbsolutePath().parent
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, "${target.fileName}.", ".tmp")
        try {
            Files.write(temp, bytes)
            if (Files.exists(target)) {
                Files.copy(target, dir.resolve("${target.fileName}.bak"), REPLACE_EXISTING)
            }
            Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
