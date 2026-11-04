package io.github.rawritude.drydock.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun byId(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state = :state ORDER BY createdAt ASC LIMIT 1")
    suspend fun firstWithState(state: DownloadState): DownloadEntity?

    @Query("SELECT COUNT(*) FROM downloads WHERE state IN (:states)")
    suspend fun countWithStates(states: List<DownloadState>): Int

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE downloads SET state = :state, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun setState(id: Long, state: DownloadState, error: String?, now: Long)

    @Query("UPDATE downloads SET downloadedBytes = :bytes, updatedAt = :now WHERE id = :id")
    suspend fun setProgress(id: Long, bytes: Long, now: Long)

    @Query(
        "UPDATE downloads SET state = :state, totalBytes = :totalBytes, mimeType = :mimeType, " +
            "fileName = :fileName, updatedAt = :now WHERE id = :id",
    )
    suspend fun setValidated(
        id: Long,
        state: DownloadState,
        totalBytes: Long?,
        mimeType: String?,
        fileName: String,
        now: Long,
    )

    @Query(
        "UPDATE downloads SET state = :state, savedUri = :savedUri, fileName = :fileName, " +
            "totalBytes = :totalBytes, downloadedBytes = :totalBytes, completedAt = :now, " +
            "updatedAt = :now, error = NULL WHERE id = :id",
    )
    suspend fun setCompleted(
        id: Long,
        state: DownloadState,
        savedUri: String,
        fileName: String,
        totalBytes: Long,
        now: Long,
    )

    @Query("UPDATE downloads SET state = :newState, updatedAt = :now WHERE state = :oldState")
    suspend fun updateStates(oldState: DownloadState, newState: DownloadState, now: Long)
}
