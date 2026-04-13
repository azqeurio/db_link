package dev.dblink.core.localization

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.core.content.edit
import java.util.Locale

object AppLanguageManager {
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_KOREAN = "ko"

    private const val PREFS_NAME = "app_language_prefs"
    private const val KEY_LANGUAGE = "selected_language"

    fun currentLanguageTag(context: Context): String {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
            ?.takeIf { it == LANGUAGE_ENGLISH || it == LANGUAGE_KOREAN }
        if (stored != null) {
            return stored
        }
        val systemTag = Resources.getSystem().configuration.locales[0]?.language
        return if (systemTag.equals(LANGUAGE_KOREAN, ignoreCase = true)) {
            LANGUAGE_KOREAN
        } else {
            LANGUAGE_ENGLISH
        }
    }

    fun persistLanguage(context: Context, languageTag: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_LANGUAGE, languageTag)
            }
    }

    fun wrapContext(base: Context): Context {
        val locale = Locale.forLanguageTag(currentLanguageTag(base))
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }
}
