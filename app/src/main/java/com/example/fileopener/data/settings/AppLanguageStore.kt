package com.example.fileopener.data.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.example.fileopener.domain.model.AppLanguage

/**
 * Язык интерфейса, выбранный в приложении.
 *
 * Android 13+ хранит его сам ([LocaleManager]): выбор виден и в системных настройках приложения,
 * а Activity пересоздаётся системой. На Android 8–12 язык хранится в [android.content.SharedPreferences]
 * (его нужно прочитать синхронно до создания интерфейса), подставляется в контекст Activity
 * через [wrap], а Activity пересоздаётся вручную.
 */
object AppLanguageStore {
    private const val PREFS = "app_language"
    private const val KEY_TAG = "tag"

    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.localeManager().applicationLocales
            AppLanguage.fromTag(if (locales.isEmpty) null else locales[0].toLanguageTag())
        } else {
            AppLanguage.fromTag(context.prefs().getString(KEY_TAG, null))
        }

    /** Применяет язык; Activity пересоздаётся, состояние экранов и ViewModel сохраняются. */
    fun apply(activity: Activity, language: AppLanguage) {
        if (language == current(activity)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.localeManager().applicationLocales = language.toLocaleList()
        } else {
            // commit, а не apply: пересозданная Activity сразу читает значение в attachBaseContext.
            activity.prefs().edit(commit = true) { putString(KEY_TAG, language.tag) }
            activity.recreate()
        }
    }

    /**
     * Контекст с выбранным языком для `attachBaseContext` (только Android 8–12; на 13+ возвращает [base]).
     * Заодно меняет локаль по умолчанию: от неё зависят формат дат в списке и значения ячеек таблиц.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val language = AppLanguage.fromTag(base.prefs().getString(KEY_TAG, null))
        val locales = if (language == AppLanguage.SYSTEM) Resources.getSystem().configuration.locales else language.toLocaleList()
        LocaleList.setDefault(locales)
        val config = Configuration(base.resources.configuration).apply { setLocales(locales) }
        return base.createConfigurationContext(config)
    }

    private fun AppLanguage.toLocaleList(): LocaleList =
        if (this == AppLanguage.SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun Context.localeManager(): LocaleManager = getSystemService(LocaleManager::class.java)
}
