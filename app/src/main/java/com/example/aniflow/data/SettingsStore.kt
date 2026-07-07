package com.example.aniflow.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings_preferences")

class SettingsStore(private val context: Context) {
    private val qualityKey = stringPreferencesKey("quality_preference")
    private val languageKey = stringPreferencesKey("language_preference")
    private val providerKey = stringPreferencesKey("provider_preference")
    private val autoSkipIntroKey = booleanPreferencesKey("auto_skip_intro")
    private val themeModeKey = stringPreferencesKey("theme_mode")

    val qualityPreference: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[qualityKey] ?: "auto"
    }

    val languagePreference: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[languageKey] ?: "sub"
    }

    val providerPreference: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[providerKey] ?: "anilight"
    }

    val autoSkipIntro: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[autoSkipIntroKey] ?: false
    }

    val themeMode: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[themeModeKey] ?: "system"
    }

    suspend fun setQuality(quality: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[qualityKey] = quality
        }
    }

    suspend fun setLanguage(lang: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[languageKey] = lang
        }
    }

    suspend fun setProvider(provider: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[providerKey] = provider
        }
    }

    suspend fun setAutoSkipIntro(skip: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[autoSkipIntroKey] = skip
        }
    }

    suspend fun setThemeMode(mode: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[themeModeKey] = mode
        }
    }
}
