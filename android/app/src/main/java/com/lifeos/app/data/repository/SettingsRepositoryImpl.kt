package com.lifeos.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lifeos.app.data.remote.DynamicBaseUrlInterceptor
import com.lifeos.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val interceptor: DynamicBaseUrlInterceptor,
    private val appScope: CoroutineScope,
) : SettingsRepository {

    private val baseUrlKey = stringPreferencesKey("api_base_url")
    private val lastSyncKey = stringPreferencesKey("last_sync_day")
    private val defaultBaseUrl = "http://10.0.2.2:8000/"

    init {
        // Keep the network interceptor in sync with the stored base URL.
        appScope.launch {
            apiBaseUrl.onEach { interceptor.setBaseUrl(it) }.collect {}
        }
    }

    override val apiBaseUrl: Flow<String> =
        dataStore.data.map { it[baseUrlKey] ?: defaultBaseUrl }

    override suspend fun setApiBaseUrl(url: String) {
        dataStore.edit { it[baseUrlKey] = url }
        interceptor.setBaseUrl(url)
    }

    override val lastSyncDay: Flow<LocalDate?> =
        dataStore.data.map { prefs -> prefs[lastSyncKey]?.let(LocalDate::parse) }

    override suspend fun setLastSyncDay(day: LocalDate) {
        dataStore.edit { it[lastSyncKey] = day.toString() }
    }

    suspend fun currentBaseUrl(): String = apiBaseUrl.first()
}
