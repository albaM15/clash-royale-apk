package dev.eddux.clashcapture

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.*
import java.io.File

class LocalCapturesActivity : Activity() {
    private var bitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session.status.active) { finish(); return }
        val files = LocalCaptureStore(File(filesDir, "local-captures")).list()
        var index = 0
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 64, 24, 48)
        }
        box.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(24, 24 + insets.systemWindowInsetTop, 24, 24 + insets.systemWindowInsetBottom)
            }
            insets
        }
        val info = TextView(this).apply { textSize = 15f }.also(box::addView)
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "JPEG de la captura local"
        }.also { box.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        val previous = Button(this).apply { setText(R.string.previous_capture) }.also(box::addView)
        val next = Button(this).apply { setText(R.string.next_capture) }.also(box::addView)
        fun timestamp(file: File) = file.nameWithoutExtension.substringAfterLast('_').toLongOrNull()
        fun show() {
            image.setImageDrawable(null)
            bitmap?.recycle(); bitmap = null
            if (files.isEmpty()) {
                info.text = "Todavía no hay imágenes. Inicia una prueba local, abre el juego y detén la sesión para ver los JPEG."
            } else {
                val file = files[index]
                bitmap = BitmapFactory.decodeFile(file.absolutePath)
                image.setImageBitmap(bitmap)
                val delta = files.getOrNull(index + 1)?.let { older ->
                    val a = timestamp(file); val b = timestamp(older)
                    if (a != null && b != null && file.name.substringBeforeLast('_') == older.name.substringBeforeLast('_')) "\nIntervalo con la anterior: ${a - b} ms" else ""
                }.orEmpty()
                info.text = "${index + 1} / ${files.size}\n${file.name}\n${file.length()} bytes$delta" +
                    if (bitmap == null) "\nNo se pudo abrir este JPEG" else ""
            }
            previous.isEnabled = index < files.lastIndex
            next.isEnabled = index > 0
        }
        previous.setOnClickListener { index++; show() }
        next.setOnClickListener { index--; show() }
        Button(this).apply { setText(R.string.back_to_home); setOnClickListener { finish() } }.also(box::addView)
        setContentView(box)
        show()
    }
    override fun onDestroy() { bitmap?.recycle(); bitmap = null; super.onDestroy() }
}
