package io.github.rawritude.drydock.data

import io.github.rawritude.drydock.data.db.DownloadDao
import io.github.rawritude.drydock.data.db.DownloadEntity
import io.github.rawritude.drydock.data.db.DownloadState
import kotlinx.coroutines.flow.Flow

class DownloadRepository(private val dao: DownloadDao) {

    fun observeAll(): Flow<List<DownloadEntity>> = dao.observeAll()

    suspend fun byId(id: Long): DownloadEntity? = dao.byId(id)

    suspend fun insert(entity: DownloadEntity): Long = dao.insert(entity)

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun nextQueued(): DownloadEntity? = dao.firstWithState(DownloadState.QUEUED)

    suspend fun setState(id: Long, state: DownloadState, error: String?) =
        dao.setState(id, state, error, System.currentTimeMillis())

    suspend fun setProgress(id: Long, bytes: Long) =
        dao.setProgress(id, bytes, System.currentTimeMillis())

    suspend fun setValidated(id: Long, totalBytes: Long?, mimeType: String?, fileName: String) =
        dao.setValidated(id, DownloadState.DOWNLOADING, totalBytes, mimeType, fileName, System.currentTimeMillis())

    suspend fun setCompleted(id: Long, savedUri: String, fileName: String, totalBytes: Long) =
        dao.setCompleted(id, DownloadState.COMPLETED, savedUri, fileName, totalBytes, System.currentTimeMillis())

    suspend fun markInterruptedAsPaused() =
        dao.updateStates(DownloadState.DOWNLOADING, DownloadState.PAUSED, System.currentTimeMillis())

    suspend fun pauseQueued() =
        dao.updateStates(DownloadState.QUEUED, DownloadState.PAUSED, System.currentTimeMillis())

    suspend fun resumeAllPaused() =
        dao.updateStates(DownloadState.PAUSED, DownloadState.QUEUED, System.currentTimeMillis())
}
