package dev.eddux.clashcapture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var url: EditText
    private lateinit var status: TextView
    private lateinit var localStart: Button
    private lateinit var gallery: Button
    private lateinit var start: Button
    private var awaitingConsent = false
    private val refresh = object : Runnable {
        override fun run() {
            val s = Session.status
            status.text = getString(R.string.session_status, s.message, s.session, s.captured,
                s.uploaded, s.pending, s.errors, s.dropped, s.timestamp, s.savedLocally, s.outsideMatch)
            start.isEnabled = !s.active && !awaitingConsent
            localStart.isEnabled = !s.active && !awaitingConsent
            gallery.isEnabled = !s.active && !awaitingConsent
            url.isEnabled = !s.active && !awaitingConsent
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingConsent = savedInstanceState?.getBoolean("consent") ?: false
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.rgb(244, 247, 244))
        }
        box.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                view.setPadding(28 + bars.left, 28 + bars.top, 28 + bars.right, 28 + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(28 + insets.systemWindowInsetLeft, 28 + insets.systemWindowInsetTop,
                    28 + insets.systemWindowInsetRight, 28 + insets.systemWindowInsetBottom)
            }
            insets
        }
        fun label(text: String, size: Float = 16f) = TextView(this).also {
            it.text = text; it.textSize = size; it.setPadding(0, 14, 0, 14); box.addView(it)
        }
        label("Captura Clash", 28f)
        label("Un JPEG por segundo · Subida a R2\n\nAutoriza cada sesión. Si Android ofrece compartir una app, selecciona Clash Royale. Puedes detener desde la notificación mientras juegas.")
        val advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        url = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            hint = "URL de subida"
            setText(prefs.getString("url", "https://cr-dataset.eddux.dev/api/ingest"))
        }.also(advanced::addView)
        Button(this).apply {
            setText(R.string.advanced_settings)
            setOnClickListener {
                advanced.visibility = if (advanced.visibility == android.view.View.GONE)
                    android.view.View.VISIBLE else android.view.View.GONE
            }
        }.also(box::addView)
        box.addView(advanced)
        start = Button(this).apply {
            setText(R.string.start_session)
            setOnClickListener {
                beginSession(localOnly = false)
            }
        }.also(box::addView)
        localStart = Button(this).apply {
            setText(R.string.local_test)
            setOnClickListener { beginSession(localOnly = true) }
        }.also(box::addView)
        label("La prueba local guarda hasta 30 JPEG en este teléfono y funciona sin conexión. Detén la sesión para ver las imágenes.")
        gallery = Button(this).apply {
            setText(R.string.view_captures)
            setOnClickListener { startActivity(Intent(this@MainActivity, LocalCapturesActivity::class.java)) }
        }.also(box::addView)
        Button(this).apply {
            setText(R.string.stop_session)
            setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)) }
        }.also(box::addView)
        status = label("", 15f)
        setContentView(ScrollView(this).apply { addView(box) })
    }

    private fun beginSession(localOnly: Boolean) {
        val endpoint = url.text.toString().trim()
        if (!localOnly && !Session.validUrl(endpoint)) {
            Toast.makeText(this, "Usa una URL HTTPS terminada en /api/ingest.", Toast.LENGTH_LONG).show()
            return
        }
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("notificationAsked", false)) {
            prefs.edit().putBoolean("notificationAsked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
            return
        }
        if (!localOnly) prefs.edit().putString("url", endpoint).apply()
        Session.config = UploadConfig(endpoint, localOnly)
        awaitingConsent = true
        start.isEnabled = false
        localStart.isEnabled = false
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 10)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 11) {
            Toast.makeText(this, "Pulsa de nuevo el botón de inicio que quieras usar. Activa las notificaciones para disponer del botón Detener mientras juegas.", Toast.LENGTH_LONG).show()
            // Si se deniega, Android aún permite detener desde los controles de apps activas.
        }
    }
    @Deprecated("API de Activity compatible con Android 8+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 10) return
        awaitingConsent = false
        if (resultCode == RESULT_OK && data != null && Session.config != null) {
            startForegroundService(Intent(this, CaptureService::class.java).apply {
                putExtra("permission", data); putExtra("result", resultCode)
            })
        } else Session.update { it.copy(message = "Captura no autorizada; vuelve a iniciar") }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("consent", awaitingConsent)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
