package io.github.rawritude.drydock.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    /** Display name; resolved from user input, Content-Disposition, or the URL. */
    val fileName: String,
    /** Set when the user explicitly chose a name at add time. */
    val customFileName: String? = null,
    val mimeType: String? = null,
    val totalBytes: Long? = null,
    val downloadedBytes: Long = 0,
    val state: DownloadState = DownloadState.QUEUED,
    val error: String? = null,
    val expectedSha256: String? = null,
    val allowInsecureHttp: Boolean = false,
    val savedUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)
