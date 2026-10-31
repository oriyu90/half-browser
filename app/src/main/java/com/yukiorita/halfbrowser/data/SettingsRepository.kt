package com.yukiorita.halfbrowser.data

import android.content.Context
import com.yukiorita.halfbrowser.core.AppSettings
import com.yukiorita.halfbrowser.core.Codecs
import com.yukiorita.halfbrowser.core.LanguageMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _settings = MutableStateFlow(
        Codecs.decodeSettings(prefs.getString(KEY_SETTINGS, null)),
    )
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        synchronized(lock) {
            val updated = Codecs.sanitize(transform(_settings.value))
            if (updated == _settings.value) return updated
            prefs.edit()
                .putString(KEY_SETTINGS, Codecs.encodeSettings(updated))
                // Also stored plainly so attachBaseContext can pick the locale before
                // the rest of the app (and the JSON blob) is available.
                .putString(KEY_LANGUAGE, updated.language.tag)
                .apply()
            _settings.value = updated
            return updated
        }
    }

    fun current(): AppSettings = _settings.value

    fun setThemeMode(mode: com.yukiorita.halfbrowser.core.ThemeMode) =
        update { it.copy(themeMode = mode) }

    companion object {
        private const val PREFS_NAME = "halfbrowser_settings"
        private const val KEY_SETTINGS = "settings_json"
        private const val KEY_LANGUAGE = "ui_language"

        /** Read without instantiating the repository (used by attachBaseContext). */
        fun readLanguageTag(context: Context): String =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, "") ?: ""

        fun readLanguage(context: Context): LanguageMode =
            LanguageMode.entries.firstOrNull { it.tag == readLanguageTag(context) } ?: LanguageMode.SYSTEM
    }
}
