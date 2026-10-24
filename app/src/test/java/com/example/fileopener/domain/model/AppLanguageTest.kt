package com.example.fileopener.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun fromTag_matchesLanguageIgnoringRegion() {
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromTag("ru"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.ITALIAN, AppLanguage.fromTag("it_IT"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("EN"))
    }

    @Test
    fun fromTag_unknownOrEmpty_isSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("de-DE"))
    }

    @Test
    fun tags_roundTrip() {
        for (language in AppLanguage.entries) assertEquals(language, AppLanguage.fromTag(language.tag))
    }
}
