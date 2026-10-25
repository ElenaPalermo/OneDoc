package io.doccraftstudio.onedoc.ui.home

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.doccraftstudio.onedoc.MainActivity
import io.doccraftstudio.onedoc.R
import io.doccraftstudio.onedoc.data.settings.AppLanguageStore
import io.doccraftstudio.onedoc.domain.model.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Выбор языка через меню главного экрана: интерфейс сразу переходит на выбранный язык,
 * а новая Activity (как после перезапуска) открывается на нём же. В конце возвращается исходный язык.
 */
@RunWith(AndroidJUnit4::class)
class AppLanguageSwitchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val app = instrumentation.targetContext.applicationContext
    private val original = AppLanguageStore.current(app)

    @Test
    fun selectedLanguage_appliesAndSurvivesRestart() {
        launch()
        selectLanguage(from = original, to = AppLanguage.ENGLISH)
        assertTrue("интерфейс не перешёл на английский", device.wait(Until.hasObject(By.text("Library")), TIMEOUT_MS))
        assertEquals(AppLanguage.ENGLISH, AppLanguageStore.current(app))

        launch()
        assertTrue("после перезапуска язык не сохранился", device.wait(Until.hasObject(By.text("Library")), TIMEOUT_MS))

        selectLanguage(from = AppLanguage.ENGLISH, to = AppLanguage.ITALIAN)
        assertTrue("интерфейс не перешёл на итальянский", device.wait(Until.hasObject(By.text("Libreria")), TIMEOUT_MS))
        assertEquals(AppLanguage.ITALIAN, AppLanguageStore.current(app))
    }

    @After
    fun restoreLanguage() {
        val current = AppLanguageStore.current(app)
        if (current == original) return
        launch()
        selectLanguage(from = current, to = original)
        device.wait(Until.hasObject(By.text(strings(original).getString(R.string.tab_library))), TIMEOUT_MS)
    }

    private fun launch() {
        app.startActivity(
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    /** Меню ⋮ → «Язык» → вариант; подписи ищутся на языке [from], который сейчас в интерфейсе. */
    private fun selectLanguage(from: AppLanguage, to: AppLanguage) {
        val strings = strings(from)
        val more = device.wait(Until.findObject(By.desc(strings.getString(R.string.action_more))), TIMEOUT_MS)
        assertNotNull("нет кнопки «${strings.getString(R.string.action_more)}»", more)
        more.click()
        device.wait(Until.findObject(By.text(strings.getString(R.string.action_language))), TIMEOUT_MS).click()
        val label = if (to == AppLanguage.SYSTEM) strings.getString(R.string.language_system) else strings(to).getString(to.labelRes())
        device.wait(Until.findObject(By.text(label)), TIMEOUT_MS).click()
    }

    private fun strings(language: AppLanguage): Context {
        val locales = if (language == AppLanguage.SYSTEM) Resources.getSystem().configuration.locales else LocaleList.forLanguageTags(language.tag)
        return app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocales(locales) })
    }

    private fun AppLanguage.labelRes(): Int = when (this) {
        AppLanguage.SYSTEM -> R.string.language_system
        AppLanguage.RUSSIAN -> R.string.language_russian
        AppLanguage.ENGLISH -> R.string.language_english
        AppLanguage.ITALIAN -> R.string.language_italian
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
