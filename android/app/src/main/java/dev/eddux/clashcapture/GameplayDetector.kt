package dev.eddux.clashcapture

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Decide si una captura pertenece a una partida en curso.
 *
 * Usa la misma regla que `calibrate_ui_layout()` del notebook 02 del repo del dataset:
 * durante la batalla, la barra de elixir (magenta) siempre está en el 15 % inferior de la
 * pantalla. Con 0 de elixir queda al menos la gota magenta. En menús, cofres, pantalla de
 * carga, "VS" y animaciones de inicio/fin esa barra no existe.
 */
object GameplayDetector {
    /** Ancho al que se reduce la captura antes de analizarla (mismo que usa el modelo). */
    const val WIDTH = 216

    /** Mínimo de píxeles magenta en la mejor fila. Partida: 15–140. Fuera de partida: 0. */
    const val MIN_MAGENTA = 8

    fun isInMatch(bitmap: Bitmap): Boolean {
        val height = (bitmap.height * WIDTH.toFloat() / bitmap.width).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, WIDTH, height, false)
        try {
            val top = (height * 0.85).toInt()
            val rows = height - top
            if (rows <= 0) return false
            val pixels = IntArray(WIDTH * rows)
            small.getPixels(pixels, 0, WIDTH, 0, top, WIDTH, rows)
            return magentaPeak(pixels, WIDTH, rows) >= MIN_MAGENTA
        } finally {
            if (small !== bitmap) small.recycle()
        }
    }

    /** Mayor cantidad de píxeles magenta en una sola fila. Píxeles en formato ARGB. */
    fun magentaPeak(pixels: IntArray, width: Int, rows: Int): Int {
        var best = 0
        for (y in 0 until rows) {
            var count = 0
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r > 165 && b > 155 && g < 95 && r - g > 60) count++
            }
            if (count > best) best = count
        }
        return best
    }
}
