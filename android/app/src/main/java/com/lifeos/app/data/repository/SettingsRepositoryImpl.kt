package com.lifeos.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lifeos.app.data.remote.AuthInterceptor
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
    private val authInterceptor: AuthInterceptor,
    private val appScope: CoroutineScope,
) : SettingsRepository {

    private val baseUrlKey = stringPreferencesKey("api_base_url")
    private val apiTokenKey = stringPreferencesKey("api_token")
    private val lastSyncKey = stringPreferencesKey("last_sync_day")
    private val lastSyncAtKey = longPreferencesKey("last_sync_at")
    private val pulseReminderEnabledKey = booleanPreferencesKey("pulse_reminder_enabled")
    private val pulseReminderHourKey = intPreferencesKey("pulse_reminder_hour")
    private val cardDueRemindersEnabledKey = booleanPreferencesKey("card_due_reminders_enabled")
    private val reopenLastTabKey = booleanPreferencesKey("reopen_last_tab")
    private val lastUsedTabKey = stringPreferencesKey("last_used_tab")
    private val defaultBaseUrl = com.lifeos.app.BuildConfig.DEFAULT_API_BASE_URL
    private val defaultReminderHour = 21 // 9 PM
    private val defaultDestination = "money"
    private val validStartupDestinations = setOf(
        "money",
        "dashboard",
        "mood",
        "priority",
        "insights",
        "chat",
        "trips",
    )

    // Must be declared BEFORE init because init launches a coroutine that reads this Flow.
    override val apiBaseUrl: Flow<String> =
        dataStore.data.map { it[baseUrlKey] ?: defaultBaseUrl }

    override val apiToken: Flow<String> =
        dataStore.data.map { it[apiTokenKey] ?: "" }

    init {
        appScope.launch {
            apiBaseUrl.collect { url -> interceptor.setBaseUrl(url) }
        }
        appScope.launch {
            apiToken.collect { token -> authInterceptor.setToken(token) }
        }
    }

    override suspend fun setApiBaseUrl(url: String) {
        val normalized = url.trim().let { if (it.endsWith("/")) it else "$it/" }
        dataStore.edit { it[baseUrlKey] = normalized }
        interceptor.setBaseUrl(normalized)
    }

    override suspend fun setApiToken(token: String) {
        val trimmed = token.trim()
        dataStore.edit { it[apiTokenKey] = trimmed }
        authInterceptor.setToken(trimmed)
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

    override val cardDueRemindersEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[cardDueRemindersEnabledKey] ?: true }

    override suspend fun setCardDueRemindersEnabled(enabled: Boolean) {
        dataStore.edit { it[cardDueRemindersEnabledKey] = enabled }
    }

    override val reopenLastTab: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[reopenLastTabKey] ?: true }

    override suspend fun setReopenLastTab(enabled: Boolean) {
        dataStore.edit { it[reopenLastTabKey] = enabled }
    }

    override val lastUsedTab: Flow<String?> =
        dataStore.data.map { prefs -> prefs[lastUsedTabKey] }

    override suspend fun setLastUsedTab(route: String) {
        dataStore.edit { it[lastUsedTabKey] = route }
    }

    override suspend fun startupDestination(): String {
        val prefs = dataStore.data.first()
        val reopen = prefs[reopenLastTabKey] ?: true
        if (!reopen) return defaultDestination
        val saved = prefs[lastUsedTabKey]
        return if (saved != null && saved in validStartupDestinations) saved else defaultDestination
    }

    suspend fun currentBaseUrl(): String = apiBaseUrl.first()
}
