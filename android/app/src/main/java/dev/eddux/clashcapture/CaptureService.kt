package dev.eddux.clashcapture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class CaptureService : Service() {
    private data class Ticket(val sessionId: String, val token: String, val expiresAtElapsed: Long)
    private data class Frame(val session: String, val timestamp: Long, val jpeg: ByteArray)
    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("JPEG capture")
    private lateinit var capture: Handler
    private val network = Executors.newSingleThreadExecutor()
    private val queue = ArrayBlockingQueue<Frame>(8)
    @Volatile private var connection: HttpURLConnection? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null
    private var cachedJpeg: ByteArray? = null
    private var hasFrame = false
    private var dirty = false
    private var width = 0
    private var height = 0
    private var nextTick = 0L
    private var session = ""
    private var localOnly = false
    private lateinit var localStore: LocalCaptureStore
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { finish("Android terminó la captura") }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (running.get()) runCatching { resize(width, height) }
                .onFailure { finish("No se pudo adaptar la captura al tamaño compartido") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        localStore = LocalCaptureStore(java.io.File(filesDir, "local-captures"))
        captureThread.start()
        capture = Handler(captureThread.looper)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("capture", "Sesión de captura", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { finish("Sesión detenida"); return START_NOT_STICKY }
        if (running.get()) return START_NOT_STICKY
        val config = Session.config
        @Suppress("DEPRECATION")
        val permission = intent?.getParcelableExtra<Intent>("permission")
        if (config == null || permission == null) {
            finish("Inicia una nueva sesión y autoriza la captura")
            return START_NOT_STICKY
        }
        try {
            val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, "capture")
                .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Captura Clash activa")
                .setContentText("Un JPEG por segundo · Pulsa Detener para finalizar")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Detener", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(1, notification)
            localOnly = config.localOnly
            Session.begin("Preparando sesión")
            Session.update { it.copy(message = if (localOnly) "Preparando prueba local" else "Conectando con el servidor") }
            running.set(true)
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra("result", Activity.RESULT_OK), permission)
            projection!!.registerCallback(callback, capture)
            if (localOnly) {
                session = java.util.UUID.randomUUID().toString()
                localStore.startSession(session)
                Session.begin(session)
                Session.update { it.copy(message = "Prueba local · sin subidas") }
                startCapture()
            } else network.submit {
                try {
                    val ticket = requestTicket(config)
                    synchronized(queue) {
                        if (!running.get()) return@submit
                        session = ticket.sessionId
                        Session.begin(session)
                    }
                    startCapture()
                    uploadLoop(config, ticket)
                } catch (_: Exception) {
                    if (running.get()) finish("No se pudo conectar con el servidor; comprueba la conexión y vuelve a iniciar")
                }
            }
        } catch (_: Exception) { finish("No se pudo iniciar el servicio de captura") }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        capture.post {
            if (!running.get()) return@post
            try {
                val size = screenSize()
                resize(size.first, size.second)
                display = projection!!.createVirtualDisplay("ClashCapture", width, height,
                    resources.configuration.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader!!.surface, null, capture)
                nextTick = SystemClock.uptimeMillis() + 1000
                capture.postAtTime(tick, nextTick)
            } catch (_: Exception) { finish("No se pudo iniciar la captura; autoriza una nueva sesión") }
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    /** Cambia la Surface, sin reutilizar el consentimiento para crear otro display. */
    private fun resize(sourceWidth: Int, sourceHeight: Int) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val scale = minOf(1.0, 1280.0 / maxOf(sourceWidth, sourceHeight))
        val w = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val h = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        if (reader != null && w == width && h == height) return
        val replacement = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        replacement.setOnImageAvailableListener({ source ->
            if (running.get() && source === reader) {
                try {
                    source.acquireLatestImage()?.let { image ->
                        latest?.close(); latest = image; dirty = true
                    }
                } catch (_: IllegalStateException) { /* Cierre o cambio de superficie. */ }
            }
        }, capture)
        display?.surface = null
        latest?.close(); latest = null
        reader?.close()
        cachedJpeg = null; hasFrame = false; dirty = false
        width = w; height = h; reader = replacement
        display?.resize(w, h, resources.configuration.densityDpi)
        display?.surface = replacement.surface
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < 34) capture.post {
            if (running.get()) {
                val size = screenSize()
                runCatching { resize(size.first, size.second) }
                    .onFailure { finish("No se pudo adaptar la captura a la rotación") }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val timestamp = System.currentTimeMillis()
            try {
                if (dirty && latest != null) {
                    // null = la imagen no es de partida: no se comprime ni se envía.
                    cachedJpeg = encode(latest!!)
                    hasFrame = true
                    dirty = false
                }
                synchronized(queue) {
                    if (!running.get()) return
                    val bytes = cachedJpeg
                    if (!hasFrame) {
                        Session.update { it.copy(dropped = it.dropped + 1, message = "Esperando imagen de la app compartida") }
                    } else if (bytes == null) {
                        Session.update { it.copy(outsideMatch = it.outsideMatch + 1, message = "Fuera de partida · captura omitida") }
                    } else if (localOnly) {
                        localStore.save(session, timestamp, bytes)
                        Session.update { it.copy(captured = it.captured + 1, savedLocally = it.savedLocally + 1,
                            timestamp = timestamp, message = "Prueba local · JPEG guardado en el teléfono") }
                    } else {
                        // Publicar el contador antes que el elemento evita carreras con el consumidor.
                        Session.update { it.copy(captured = it.captured + 1, timestamp = timestamp, pending = it.pending + 1) }
                        if (!queue.offer(Frame(session, timestamp, bytes))) {
                            Session.update { it.copy(pending = it.pending - 1, dropped = it.dropped + 1, message = "Cola llena: captura descartada") }
                        }
                    }
                }
            } catch (_: Exception) {
                Session.update { if (it.active && it.session == session) it.copy(dropped = it.dropped + 1, message = "No se pudo convertir o guardar una captura JPEG") else it }
            }
            // Reloj monotónico: la red y los cambios del reloj civil no alteran la cadencia.
            nextTick += 1000
            if (nextTick <= SystemClock.uptimeMillis()) nextTick = SystemClock.uptimeMillis() + 1000
            capture.postAtTime(this, nextTick)
        }
    }

    /** Devuelve el JPEG de la captura, o null si no pertenece a una partida en curso. */
    private fun encode(image: Image): ByteArray? {
        val plane = image.planes[0]
        check(plane.pixelStride == 4)
        val packed = java.nio.ByteBuffer.allocate(image.width * image.height * 4)
        val row = ByteArray(image.width * 4)
        val buffer = plane.buffer
        for (y in 0 until image.height) {
            buffer.position(y * plane.rowStride)
            buffer.get(row)
            packed.put(row)
        }
        packed.rewind()
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(packed)
            if (!GameplayDetector.isInMatch(bitmap)) return null
            return ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out))
                out.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun readResponse(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            check(output.size() + count <= 4096)
            output.write(chunk, 0, count)
        }
        return output.toString("UTF-8")
    }

    /** Credencial efímera emitida por el servidor; nunca se pide ni se guarda en el teléfono. */
    private fun requestTicket(config: UploadConfig, previous: Ticket? = null): Ticket {
        val endpoint = URI(config.url).resolve("/api/ingest-session").toURL()
        val http = endpoint.openConnection() as HttpURLConnection
        connection = http
        try {
            check(running.get())
            http.requestMethod = "POST"
            http.instanceFollowRedirects = false
            http.connectTimeout = 8000; http.readTimeout = 8000
            http.doOutput = true
            http.setFixedLengthStreamingMode(0)
            previous?.let { http.setRequestProperty("Authorization", "Bearer ${it.token}") }
            http.outputStream.close()
            check(http.responseCode == 201)
            val body = JSONObject(http.inputStream.use { readResponse(it) })
            val id = body.getString("sessionId")
            val token = body.getString("token")
            val lifetime = body.getLong("expiresIn")
            check(lifetime in 61..3600)
            val expiry = SystemClock.elapsedRealtime() + lifetime * 1000
            check(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            check(token.length in 1..2048 && token.all { it > ' ' && it <= '~' })
            check(previous == null || id == previous.sessionId)
            return Ticket(id, token, expiry)
        } finally { http.disconnect(); connection = null }
    }

    private fun uploadLoop(config: UploadConfig, initialTicket: Ticket) {
        var ticket = initialTicket
        try {
            while (running.get()) {
                val frame = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                var sent = false
                for (attempt in 0..2) {
                    if (!running.get()) break
                    var retry = true
                    var authFailure = false
                    try {
                        if (SystemClock.elapsedRealtime() >= ticket.expiresAtElapsed - 60000) {
                            ticket = requestTicket(config, ticket)
                        }
                        val http = URI(config.url).toURL().openConnection() as HttpURLConnection
                        connection = http
                        if (!running.get()) { http.disconnect(); break }
                        http.requestMethod = "POST"
                        http.instanceFollowRedirects = false
                        http.connectTimeout = 8000; http.readTimeout = 8000
                        http.doOutput = true
                        http.setRequestProperty("Content-Type", "image/jpeg")
                        http.setRequestProperty("Authorization", "Bearer ${ticket.token}")
                        http.setRequestProperty("X-Session-Id", frame.session)
                        http.setRequestProperty("X-Capture-Timestamp", frame.timestamp.toString())
                        http.setFixedLengthStreamingMode(frame.jpeg.size)
                        http.outputStream.use { it.write(frame.jpeg) }
                        val code = http.responseCode
                        if (code == 201) {
                            val response = http.inputStream.use { readResponse(it) }
                            val expected = "ingest/${frame.session}/${frame.timestamp}.jpg"
                            check(JSONObject(response).optString("key") == expected)
                            sent = true
                            Session.update { if (it.active && it.session == frame.session) it.copy(uploaded = it.uploaded + 1, message = "Subida confirmada") else it }
                        } else {
                            authFailure = code == 401 || code == 403
                            retry = code == 408 || code == 429 || code >= 500
                            Session.update { if (it.active && it.session == frame.session) it.copy(errors = it.errors + 1, message = "Error HTTP $code al subir") else it }
                        }
                    } catch (_: Exception) {
                        if (running.get()) Session.update { if (it.active && it.session == frame.session) it.copy(errors = it.errors + 1, message = "Error de red o respuesta inválida") else it }
                    } finally { connection?.disconnect(); connection = null }
                    if (authFailure && running.get()) { finish("No se pudo autorizar la sesión; vuelve a iniciar"); break }
                    if (sent || !retry) break
                    if (attempt < 2 && running.get()) Thread.sleep(1000L shl attempt)
                }
                Session.update { if (it.active && it.session == frame.session) it.copy(pending = (it.pending - 1).coerceAtLeast(0), dropped = it.dropped + if (sent) 0 else 1) else it }
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun finish(message: String) {
        main.post {
            if (session.isNotEmpty() && Session.status.session != session) return@post
            Session.update { it.copy(message = message) }
            stopSelf()
        }
    }

    override fun onDestroy() {
        synchronized(queue) {
            running.set(false)
            queue.clear()
            Session.update { it.copy(active = false, pending = 0, dropped = it.dropped + it.pending,
                message = if (it.message.startsWith("No se pudo") || it.message.startsWith("Android terminó")) it.message else "Sesión detenida") }
        }
        connection?.disconnect()
        network.shutdownNow()
        capture.removeCallbacksAndMessages(null)
        capture.post {
            latest?.close(); latest = null
            display?.release(); display = null
            reader?.close(); reader = null
            cachedJpeg = null; hasFrame = false
            projection?.unregisterCallback(callback)
            projection?.stop(); projection = null
            captureThread.quitSafely()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
