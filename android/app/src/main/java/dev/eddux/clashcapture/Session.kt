package dev.eddux.clashcapture

import java.net.URI

data class UploadConfig(val url: String, val localOnly: Boolean = false)

/** La configuración pública contiene únicamente la URL del servicio. */
object Session {
    var config: UploadConfig? = null
    data class Status(
        val active: Boolean = false, val session: String = "—", val captured: Int = 0,
        val uploaded: Int = 0, val errors: Int = 0, val dropped: Int = 0,
        val savedLocally: Int = 0, val pending: Int = 0, val timestamp: Long = 0, val message: String = "Lista para iniciar"
    )
    @Volatile var status = Status()
        private set
    @Synchronized fun update(change: (Status) -> Status) { status = change(status) }
    @Synchronized fun begin(id: String) { status = Status(active = true, session = id, message = "Capturando") }

    fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path == "/api/ingest"
    }.getOrDefault(false)
}
