package dev.eddux.clashcapture

import java.io.File

/**
 * Almacenamiento privado de la prueba local. Guarda todas las capturas de partida de la
 * sesión actual; al iniciar una sesión nueva se borran las de sesiones anteriores.
 * [maxFiles] es solo un tope de seguridad para no llenar el teléfono si la sesión se
 * deja abierta durante horas (1200 capturas ≈ 20 min de partida, unos 180 MB).
 */
class LocalCaptureStore(private val root: File, private val maxFiles: Int = 1200) {
    /** Deja el almacenamiento listo para una sesión nueva, borrando las anteriores. */
    @Synchronized fun startSession(session: String) {
        require(validSession(session))
        root.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "jpg" && !it.name.startsWith("${session}_") }
            .forEach { check(it.delete()) }
    }

    @Synchronized fun save(session: String, timestamp: Long, jpeg: ByteArray): File {
        require(validSession(session) && timestamp > 0)
        check(root.isDirectory || root.mkdirs())
        val destination = File(root, "${session}_$timestamp.jpg")
        val temporary = File(root, "capture.tmp")
        try {
            temporary.writeBytes(jpeg)
            check(temporary.renameTo(destination))
        } finally { temporary.delete() }
        val files = list()
        if (files.size > maxFiles) files.drop(maxFiles).forEach { check(it.delete()) }
        return destination
    }

    @Synchronized fun list(): List<File> = root.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "jpg" }
        .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })

    private fun validSession(session: String) = session.matches(Regex("[A-Za-z0-9_-]{1,128}"))
}
