package dev.eddux.clashcapture

import java.io.File

/** Almacenamiento privado de prueba, acotado a los últimos 30 JPEG entre todas las sesiones. */
class LocalCaptureStore(private val root: File) {
    @Synchronized fun save(session: String, timestamp: Long, jpeg: ByteArray): File {
        require(session.matches(Regex("[A-Za-z0-9_-]{1,128}")) && timestamp > 0)
        check(root.isDirectory || root.mkdirs())
        val destination = File(root, "${session}_$timestamp.jpg")
        val temporary = File(root, "capture.tmp")
        try {
            temporary.writeBytes(jpeg)
            check(temporary.renameTo(destination))
        } finally { temporary.delete() }
        list().drop(30).forEach { check(it.delete()) }
        return destination
    }

    @Synchronized fun list(): List<File> = root.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "jpg" }
        .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
}
