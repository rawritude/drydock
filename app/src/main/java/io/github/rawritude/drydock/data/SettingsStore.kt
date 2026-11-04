package io.github.rawritude.drydock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "drydock_settings")

class SettingsStore(context: Context) {

    private val store = context.applicationContext.settingsDataStore

    val wifiOnly: Flow<Boolean> = store.data.map { it[Keys.WIFI_ONLY] ?: false }

    val connectionsPerDownload: Flow<Int> = store.data.map {
        (it[Keys.CONNECTIONS] ?: DEFAULT_CONNECTIONS).coerceIn(1, MAX_CONNECTIONS)
    }

    val concurrentDownloads: Flow<Int> = store.data.map {
        (it[Keys.CONCURRENT] ?: DEFAULT_CONCURRENT).coerceIn(1, MAX_CONCURRENT)
    }

    val captureEnabled: Flow<Boolean> = store.data.map { it[Keys.CAPTURE_ENABLED] ?: false }

    val capturePatterns: Flow<String> = store.data.map { it[Keys.CAPTURE_PATTERNS] ?: "" }

    val captureAllowHttp: Flow<Boolean> = store.data.map { it[Keys.CAPTURE_ALLOW_HTTP] ?: false }

    suspend fun setWifiOnly(value: Boolean) = store.edit { it[Keys.WIFI_ONLY] = value }

    suspend fun setConnectionsPerDownload(value: Int) =
        store.edit { it[Keys.CONNECTIONS] = value.coerceIn(1, MAX_CONNECTIONS) }

    suspend fun setConcurrentDownloads(value: Int) =
        store.edit { it[Keys.CONCURRENT] = value.coerceIn(1, MAX_CONCURRENT) }

    suspend fun setCaptureEnabled(value: Boolean) = store.edit { it[Keys.CAPTURE_ENABLED] = value }

    suspend fun setCapturePatterns(value: String) = store.edit { it[Keys.CAPTURE_PATTERNS] = value }

    suspend fun setCaptureAllowHttp(value: Boolean) = store.edit { it[Keys.CAPTURE_ALLOW_HTTP] = value }

    private object Keys {
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val CONNECTIONS = intPreferencesKey("connections_per_download")
        val CONCURRENT = intPreferencesKey("concurrent_downloads")
        val CAPTURE_ENABLED = booleanPreferencesKey("link_capture_enabled")
        val CAPTURE_PATTERNS = stringPreferencesKey("link_capture_patterns")
        val CAPTURE_ALLOW_HTTP = booleanPreferencesKey("link_capture_allow_http")
    }

    companion object {
        const val DEFAULT_CONNECTIONS = 4
        const val MAX_CONNECTIONS = 8
        const val DEFAULT_CONCURRENT = 2
        const val MAX_CONCURRENT = 4
    }
}
