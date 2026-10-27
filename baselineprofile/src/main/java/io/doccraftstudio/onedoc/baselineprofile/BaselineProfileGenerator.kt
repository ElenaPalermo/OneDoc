package io.doccraftstudio.onedoc.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Генерирует Baseline Profile: классы и методы, нужные для старта и открытия документов, ART
 * компилирует заранее при установке, а не по мере выполнения. Профиль кладётся в
 * app/src/release/generated/baselineProfiles/ (`./gradlew :app:generateBaselineProfile`).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
        startAndBrowseLibrary()
        openEveryFormat()
    }
}
