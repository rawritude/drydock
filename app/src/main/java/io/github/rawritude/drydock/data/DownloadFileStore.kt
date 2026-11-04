package io.github.rawritude.drydock.data

import android.content.Context
import io.github.rawritude.drydock.engine.ControlFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * App-private storage for partial downloads and their control files.
 * Nothing here is user-visible; finished files are published to the shared
 * Downloads collection via [MediaStorePublisher].
 */
class DownloadFileStore(context: Context) {

    private val partsDir = File(context.filesDir, "parts").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    fun partFile(id: Long): File = File(partsDir, "$id.part")

    private fun controlFile(id: Long): File = File(partsDir, "$id.json")

    private fun controlTempFile(id: Long): File = File(partsDir, "$id.json.tmp")

    fun readControl(id: Long): ControlFile? {
        val file = controlFile(id)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<ControlFile>(file.readText()) }.getOrNull()
    }

    suspend fun writeControl(id: Long, control: ControlFile) = withContext(Dispatchers.IO) {
        runCatching {
            val temp = controlTempFile(id)
            temp.writeText(json.encodeToString(control))
            val target = controlFile(id)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    fun deleteFiles(id: Long) {
        partFile(id).delete()
        controlFile(id).delete()
        controlTempFile(id).delete()
    }
}
