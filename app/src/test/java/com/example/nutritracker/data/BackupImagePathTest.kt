package com.example.nutritracker.data

import com.google.gson.JsonParser
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupImagePathTest {
    private val filesDir = File("/app/private")

    @Test
    fun mapsMealAndChatImagesIntoTheirOwnPrivateDirectories() {
        assertEquals(
            File(filesDir, "meal_thumbnails/meal.jpg").canonicalFile,
            backupImageTarget("images/meals/meal.jpg", filesDir)
        )
        assertEquals(
            File(filesDir, "chat_images/chat.jpg").canonicalFile,
            backupImageTarget("images/chat/chat.jpg", filesDir)
        )
        assertEquals(
            File(filesDir, "meal_thumbnails/legacy.jpg").canonicalFile,
            backupImageTarget("images/legacy.jpg", filesDir)
        )
    }

    @Test
    fun rejectsTraversalAndUnexpectedArchivePaths() {
        assertNull(backupImageTarget("images/chat/../secrets.xml", filesDir))
        assertNull(backupImageTarget("images/../../shared_prefs/settings.xml", filesDir))
        assertNull(backupImageTarget("images/chat/nested/photo.jpg", filesDir))
        assertNull(backupImageTarget("images/", filesDir))
    }

    @Test
    fun rewritesChatImagePayloadPathsToRestoredLocations() {
        val dir = createTempDirectory("backup-payload-test").toFile()
        try {
            val kept = File(File(dir, "chat_images").apply { mkdirs() }, "a.img").apply { writeText("x") }
            val payload = """{"card":"photo","images":["/old/a.img","/old/missing.img"]}"""

            val rewritten = restoredChatImagesPayload(payload, dir)

            val obj = JsonParser.parseString(rewritten).asJsonObject
            assertEquals("photo", obj.get("card").asString)
            val images = obj.getAsJsonArray("images")
            assertEquals(1, images.size())
            assertEquals(kept.canonicalFile.absolutePath, images[0].asString)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun leavesPayloadWithoutImagesUntouched() {
        assertEquals("""{"meal_id":42}""", restoredChatImagesPayload("""{"meal_id":42}""", filesDir))
        assertNull(restoredChatImagesPayload(null, filesDir))
    }
}
