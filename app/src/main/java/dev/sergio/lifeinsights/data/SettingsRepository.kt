package dev.sergio.lifeinsights.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = 21,
    val reminderMinute: Int = 0,
    val middayReminderEnabled: Boolean = false,
    val middayReminderHour: Int = 13,
    /** Screen time after this local hour counts as late-night usage. */
    val lateNightHour: Int = 23,
) {
    val reminderTime: LocalTime get() = LocalTime.of(reminderHour, reminderMinute)
}

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            reminderEnabled = prefs[REMINDER_ENABLED] ?: true,
            reminderHour = prefs[REMINDER_HOUR] ?: 21,
            reminderMinute = prefs[REMINDER_MINUTE] ?: 0,
            middayReminderEnabled = prefs[MIDDAY_ENABLED] ?: false,
            middayReminderHour = prefs[MIDDAY_HOUR] ?: 13,
            lateNightHour = prefs[LATE_NIGHT_HOUR] ?: 23,
        )
    }

    suspend fun setReminderEnabled(enabled: Boolean) =
        edit { it[REMINDER_ENABLED] = enabled }

    suspend fun setReminderTime(hour: Int, minute: Int) = edit {
        it[REMINDER_HOUR] = hour
        it[REMINDER_MINUTE] = minute
    }

    suspend fun setMiddayReminderEnabled(enabled: Boolean) =
        edit { it[MIDDAY_ENABLED] = enabled }

    suspend fun setLateNightHour(hour: Int) = edit { it[LATE_NIGHT_HOUR] = hour }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val REMINDER_HOUR = intPreferencesKey("reminder_hour")
        val REMINDER_MINUTE = intPreferencesKey("reminder_minute")
        val MIDDAY_ENABLED = booleanPreferencesKey("midday_enabled")
        val MIDDAY_HOUR = intPreferencesKey("midday_hour")
        val LATE_NIGHT_HOUR = intPreferencesKey("late_night_hour")
    }
}
