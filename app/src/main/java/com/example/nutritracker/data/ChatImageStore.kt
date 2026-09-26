package com.example.nutritracker.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Owns durable chat image files so chat UI, picker URIs, and backup storage stay separate. */
@Singleton
class ChatImageStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val directory: File get() = File(context.filesDir, DIRECTORY_NAME)

    suspend fun persist(uri: Uri): String? = withContext(Dispatchers.IO) {
        val folder = directory
        if (!folder.exists() && !folder.mkdirs()) return@withContext null
        val source = context.contentResolver.openInputStream(uri) ?: return@withContext null
        val destination = File(folder, "${UUID.randomUUID()}.img")
        try {
            source.use { input -> destination.outputStream().use(input::copyTo) }
            destination.absolutePath
        } catch (_: Exception) {
            destination.delete()
            null
        }
    }

    /** 逐张持久化，跳过失败项；返回成功落盘的绝对路径（顺序与入参一致）。 */
    suspend fun persistAll(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        uris.mapNotNull { persist(it) }
    }

    companion object {
        const val DIRECTORY_NAME = "chat_images"
    }
}
