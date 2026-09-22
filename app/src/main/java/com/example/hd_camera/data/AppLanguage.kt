package com.example.hd_camera.data

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import com.example.hd_camera.R

/**
 * The languages the app ships strings for, in the order the picker lists them. [tag] is a
 * BCP-47 tag, which is what both `AppCompatDelegate` and the `<locale-config>` speak.
 */
enum class AppLanguage(
    val tag: String,
    @StringRes val displayName: Int,
    @DrawableRes val flag: Int
) {
    HINDI("hi", R.string.language_name_hi, R.drawable.flag_hi),
    SPANISH("es", R.string.language_name_es, R.drawable.flag_es),
    PORTUGUESE_BR("pt-BR", R.string.language_name_pt_br, R.drawable.flag_pt_br),
    ENGLISH("en", R.string.language_name_en, R.drawable.flag_en),
    PORTUGUESE_PT("pt-PT", R.string.language_name_pt_pt, R.drawable.flag_pt_pt),
    FRENCH("fr", R.string.language_name_fr, R.drawable.flag_fr),
    ARABIC("ar", R.string.language_name_ar, R.drawable.flag_ar),
    BENGALI("bn", R.string.language_name_bn, R.drawable.flag_bn),
    RUSSIAN("ru", R.string.language_name_ru, R.drawable.flag_ru),
    GERMAN("de", R.string.language_name_de, R.drawable.flag_de),
    JAPANESE("ja", R.string.language_name_ja, R.drawable.flag_ja),
    TURKISH("tr", R.string.language_name_tr, R.drawable.flag_tr),
    KOREAN("ko", R.string.language_name_ko, R.drawable.flag_ko),
    INDONESIAN("id", R.string.language_name_id, R.drawable.flag_id),
    CHINESE_SIMPLIFIED("zh-Hans", R.string.language_name_zh_hans, R.drawable.flag_zh_hans),
    CHINESE_TRADITIONAL("zh-Hant", R.string.language_name_zh_hant, R.drawable.flag_zh_hant);

    companion object {
        val DEFAULT = ENGLISH

        /**
         * Matches a stored or system tag back onto the list. `pt-BR` has to beat `pt-PT`, and
         * a bare `pt` has to land somewhere, so an exact hit is tried before the language
         * subtag alone.
         */
        fun of(tag: String?): AppLanguage {
            if (tag.isNullOrBlank()) return DEFAULT
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }?.let { return it }
            val language = tag.substringBefore('-')
            return entries.firstOrNull {
                it.tag.substringBefore('-').equals(language, ignoreCase = true)
            } ?: DEFAULT
        }
    }
}

/**
 * Where the chosen language lives. AppCompat persists its own copy, but the app keeps one
 * too: it is what the Settings row reads, and what restores the choice on a device where
 * AppCompat's storage was cleared.
 */
object LocalePrefs {

    private const val FILE = "locale_prefs"
    private const val KEY_TAG = "app_language_tag"

    /** The language currently in force, from AppCompat first and the stored tag second. */
    fun current(context: Context): AppLanguage {
        val applied = AppCompatDelegate.getApplicationLocales()
        if (!applied.isEmpty) return AppLanguage.of(applied[0]?.toLanguageTag())
        return AppLanguage.of(storedTag(context))
    }

    fun storedTag(context: Context): String? = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getString(KEY_TAG, null)

    /**
     * Applies [language] to the whole app. `setApplicationLocales` recreates the activity
     * itself on API 32 and below and delivers a configuration change above it, so callers
     * must not also recreate — that is what caused the flicker loop.
     */
    fun apply(context: Context, language: AppLanguage) {
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit { putString(KEY_TAG, language.tag) }
        val locales = LocaleListCompat.forLanguageTags(language.tag)
        if (AppCompatDelegate.getApplicationLocales() == locales) return
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /**
     * Re-applies the stored choice when AppCompat has forgotten it — a fresh install of the
     * same data, or storage the system cleared. Does nothing when a locale is already set,
     * so it cannot loop on start-up.
     */
    fun restore(context: Context) {
        if (!AppCompatDelegate.getApplicationLocales().isEmpty) return
        val tag = storedTag(context) ?: return
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}
