package dev.eddux.clashcapture

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalCaptureStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun preservesOriginalTimestampAndBytes() {
        val root = temporary.newFolder()
        val store = LocalCaptureStore(root)
        val jpeg = byteArrayOf(-1, -40, -1, -39)
        val first = store.save("local-session", 1000, jpeg)
        val second = store.save("local-session", 2000, jpeg)
        assertEquals("local-session_1000.jpg", first.name)
        assertEquals("local-session_2000.jpg", second.name)
        assertArrayEquals(jpeg, second.readBytes())
        assertFalse(File(root, "capture.tmp").exists())
    }
    @Test fun keepsEveryCaptureOfAFullMatch() {
        // Una partida con tiempo extra dura unos 300 s: deben quedar todas las capturas.
        val store = LocalCaptureStore(temporary.newFolder())
        store.startSession("match")
        for (i in 1..300) store.save("match", i.toLong(), byteArrayOf(i.toByte()))
        assertEquals(300, store.list().size)
    }
    @Test fun newSessionRemovesPreviousSessions() {
        val store = LocalCaptureStore(temporary.newFolder())
        store.startSession("old")
        for (i in 1..5) store.save("old", i.toLong(), byteArrayOf(1))
        store.startSession("new")
        store.save("new", 10, byteArrayOf(2))
        assertEquals(listOf("new_10.jpg"), store.list().map { it.name })
    }
    @Test fun safetyCapKeepsTheMostRecentImages() {
        val store = LocalCaptureStore(temporary.newFolder(), maxFiles = 50)
        store.startSession("long")
        for (i in 1..60) {
            val file = store.save("long", i.toLong(), byteArrayOf(i.toByte()))
            assertTrue(file.setLastModified(i * 1000L))
        }
        val kept = store.list().map { it.nameWithoutExtension.substringAfterLast('_').toInt() }.toSet()
        assertEquals((11..60).toSet(), kept)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPathsOutsidePrivateDirectory() {
        LocalCaptureStore(temporary.newFolder()).save("../escape", 1000, byteArrayOf())
    }
}
