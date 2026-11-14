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
    @Test fun boundsStorageAcrossSessionsToThirtyImages() {
        val store = LocalCaptureStore(temporary.newFolder())
        for (i in 1..35) {
            val file = store.save("session-${i % 2}", i.toLong(), byteArrayOf(i.toByte()))
            assertTrue(file.setLastModified(i.toLong()))
        }
        val files = store.list()
        assertEquals(30, files.size)
        assertEquals((6..35).toSet(), files.map { it.nameWithoutExtension.substringAfterLast('_').toInt() }.toSet())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPathsOutsidePrivateDirectory() {
        LocalCaptureStore(temporary.newFolder()).save("../escape", 1000, byteArrayOf())
    }
}
