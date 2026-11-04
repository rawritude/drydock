package io.github.rawritude.drydock.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.rawritude.drydock.engine.EngineException
import io.github.rawritude.drydock.util.FileNames
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publishes a finished part file into the shared Downloads collection.
 *
 * The copy is a single streaming pass that also computes SHA-256, so presence
 * and integrity are checked before the entry becomes visible (IS_PENDING).
 * On any failure the half-written media store row is removed.
 */
class MediaStorePublisher(context: Context) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    data class Published(val uri: Uri, val displayName: String, val sha256: String)

    suspend fun publish(
        partFile: File,
        displayName: String,
        mimeType: String?,
        expectedSha256: String?,
    ): Published = withContext(Dispatchers.IO) {
        require(partFile.isFile) { "Part file is missing" }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uniqueName = uniqueDisplayName(displayName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create a Downloads entry")

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Could not open the Downloads entry for writing")
            output.use { out ->
                partFile.inputStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
                out.flush()
                if (out is FileOutputStream) out.fd.sync()
            }

            val actualSha256 = digest.digest().toHex()
            if (expectedSha256 != null && !expectedSha256.equals(actualSha256, ignoreCase = true)) {
                resolver.delete(uri, null, null)
                throw EngineException.ChecksumMismatch(expectedSha256.lowercase(), actualSha256)
            }

            val visible = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, visible, null, null)
            Published(uri, uniqueName, actualSha256)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun uniqueDisplayName(base: String): String {
        var candidate = base
        var index = 0
        while (exists(candidate)) {
            index++
            candidate = FileNames.withSuffix(base, index)
        }
        return candidate
    }

    private fun exists(name: String): Boolean {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        resolver.query(collection, projection, selection, arrayOf(name), null)?.use { cursor ->
            val relativePathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val path = cursor.getString(relativePathColumn) ?: continue
                if (path.startsWith(Environment.DIRECTORY_DOWNLOADS)) return true
            }
        }
        return false
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 128 * 1024
    }
}
