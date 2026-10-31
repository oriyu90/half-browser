package com.yukiorita.halfbrowser.core

import android.content.Context
import android.content.res.Configuration
import com.yukiorita.halfbrowser.data.SettingsRepository
import java.util.Locale

/**
 * In-app language override without AppCompat.
 *
 * `attachBaseContext` wraps the base context with a locale-overridden
 * configuration; the system default is left alone for LanguageMode.SYSTEM.
 * Changing the language recreates the activity (see SettingsScreen).
 */
object LocaleHelper {

    fun wrap(base: Context): Context {
        return when (SettingsRepository.readLanguage(base)) {
            LanguageMode.SYSTEM -> base
            LanguageMode.JAPANESE -> wrapWith(base, Locale.JAPANESE)
            LanguageMode.ENGLISH -> wrapWith(base, Locale.ENGLISH)
        }
    }

    private fun wrapWith(base: Context, locale: Locale): Context = runCatching {
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        base.createConfigurationContext(configuration)
    }.getOrDefault(base)
}
