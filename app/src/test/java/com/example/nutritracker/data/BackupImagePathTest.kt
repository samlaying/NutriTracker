package com.example.nutritracker.data

import java.io.File
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
}
