package dev.eddux.clashcapture

import org.junit.Assert.*
import org.junit.Test
import javax.imageio.ImageIO

/** Las imágenes de prueba son capturas reales de la app reducidas a 216 px de ancho. */
class GameplayDetectorTest {
    private fun peak(resource: String): Int {
        val image = ImageIO.read(checkNotNull(javaClass.classLoader?.getResource(resource)))
        val width = image.width
        val top = (image.height * 0.85).toInt()
        val rows = image.height - top
        val pixels = image.getRGB(0, top, width, rows, null, 0, width)
        return GameplayDetector.magentaPeak(pixels, width, rows)
    }

    @Test fun detectaPartidaEnCurso() {
        assertTrue(peak("partida.jpg") >= GameplayDetector.MIN_MAGENTA)
    }

    @Test fun omitePantallaVs() {
        assertTrue(peak("pantalla_vs.jpg") < GameplayDetector.MIN_MAGENTA)
    }

    @Test fun omiteCofre() {
        assertTrue(peak("cofre.jpg") < GameplayDetector.MIN_MAGENTA)
    }

    @Test fun omiteAnimacionDeInicioSinCartas() {
        assertTrue(peak("inicio_sin_hud.jpg") < GameplayDetector.MIN_MAGENTA)
    }

    @Test fun conCeroElixirLaGotaBastaParaContarComoPartida() {
        // Con 0 de elixir solo queda la gota: unos 15 píxeles magenta en una fila.
        val width = 216
        val rows = 10
        val pixels = IntArray(width * rows) { 0xFF2050A0.toInt() } // azul del panel
        for (x in 40 until 55) pixels[4 * width + x] = 0xFFE632D2.toInt() // magenta de la barra
        assertEquals(15, GameplayDetector.magentaPeak(pixels, width, rows))
        assertTrue(GameplayDetector.magentaPeak(pixels, width, rows) >= GameplayDetector.MIN_MAGENTA)
    }
}
