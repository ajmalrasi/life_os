package com.lifeos.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lifeos.app.data.remote.DynamicBaseUrlInterceptor
import com.lifeos.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val lastSyncAtKey = longPreferencesKey("last_sync_at")
    private val pulseReminderEnabledKey = booleanPreferencesKey("pulse_reminder_enabled")
    private val pulseReminderHourKey = intPreferencesKey("pulse_reminder_hour")
    private val defaultBaseUrl = com.lifeos.app.BuildConfig.DEFAULT_API_BASE_URL
    private val defaultReminderHour = 21 // 9 PM

    // Must be declared BEFORE init — init launches a coroutine that reads this Flow.
    override val apiBaseUrl: Flow<String> =
        dataStore.data.map { it[baseUrlKey] ?: defaultBaseUrl }

    init {
        appScope.launch {
            apiBaseUrl.collect { url -> interceptor.setBaseUrl(url) }
        }
    }

    override suspend fun setApiBaseUrl(url: String) {
        val normalized = url.trim().let { if (it.endsWith("/")) it else "$it/" }
        dataStore.edit { it[baseUrlKey] = normalized }
        interceptor.setBaseUrl(normalized)
    }

    override val lastSyncDay: Flow<LocalDate?> =
        dataStore.data.map { prefs -> prefs[lastSyncKey]?.let(LocalDate::parse) }

    override suspend fun setLastSyncDay(day: LocalDate) {
        dataStore.edit { it[lastSyncKey] = day.toString() }
    }

    override val lastSyncAt: Flow<Long?> =
        dataStore.data.map { prefs -> prefs[lastSyncAtKey] }

    override suspend fun setLastSyncAt(epochMillis: Long) {
        dataStore.edit { it[lastSyncAtKey] = epochMillis }
    }

    override val pulseReminderEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[pulseReminderEnabledKey] ?: false }

    override suspend fun setPulseReminderEnabled(enabled: Boolean) {
        dataStore.edit { it[pulseReminderEnabledKey] = enabled }
    }

    override val pulseReminderHour: Flow<Int> =
        dataStore.data.map { prefs -> prefs[pulseReminderHourKey] ?: defaultReminderHour }

    override suspend fun setPulseReminderHour(hour: Int) {
        dataStore.edit { it[pulseReminderHourKey] = hour.coerceIn(0, 23) }
    }

    suspend fun currentBaseUrl(): String = apiBaseUrl.first()
}
