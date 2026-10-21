package com.example.fileopener.ui.viewer.common

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.fileopener.R
import com.example.fileopener.domain.model.ReaderSettings
import com.example.fileopener.domain.model.ReaderTheme
import com.example.fileopener.ui.theme.appColorScheme

/** Цвета «страницы» в теме чтения. */
@Immutable
data class ReaderPalette(
    val page: Color,
    val text: Color,
    val secondaryText: Color,
    /** Фон панелей инструментов: чуть темнее/светлее страницы. */
    val chrome: Color,
    val isDark: Boolean,
)

fun ReaderTheme.palette(): ReaderPalette = when (this) {
    ReaderTheme.LIGHT -> ReaderPalette(
        page = Color(0xFFFFFFFF),
        text = Color(0xFF1C1B1F),
        secondaryText = Color(0xFF5F5E66),
        chrome = Color(0xFFF2F1F6),
        isDark = false,
    )
    ReaderTheme.DARK -> ReaderPalette(
        page = Color(0xFF121212),
        text = Color(0xFFE3E2E6),
        secondaryText = Color(0xFFA3A2AA),
        chrome = Color(0xFF1F1F23),
        isDark = true,
    )
    ReaderTheme.SEPIA -> ReaderPalette(
        page = Color(0xFFF4ECD8),
        text = Color(0xFF5B4636),
        secondaryText = Color(0xFF8A7560),
        chrome = Color(0xFFE9DEC5),
        isDark = false,
    )
}

@get:StringRes
val ReaderTheme.titleRes: Int
    get() = when (this) {
        ReaderTheme.LIGHT -> R.string.reader_theme_light
        ReaderTheme.DARK -> R.string.reader_theme_dark
        ReaderTheme.SEPIA -> R.string.reader_theme_sepia
    }

/** Тема, которую видит пользователь: выбранная явно или светлая/тёмная по системе. */
@Composable
fun ReaderSettings.effectiveTheme(): ReaderTheme =
    theme ?: if (isSystemInDarkTheme()) ReaderTheme.DARK else ReaderTheme.LIGHT

val LocalReaderPalette = staticCompositionLocalOf { ReaderTheme.LIGHT.palette() }

/**
 * Переопределяет MaterialTheme для экрана чтения: фон и текст берутся из палитры темы,
 * акцентные цвета остаются от приложения (в том числе dynamic color на Android 12+).
 */
@Composable
fun ReaderThemeProvider(theme: ReaderTheme, content: @Composable () -> Unit) {
    val palette = theme.palette()
    val colorScheme = appColorScheme(darkTheme = palette.isDark).copy(
        background = palette.page,
        onBackground = palette.text,
        surface = palette.chrome,
        onSurface = palette.text,
        onSurfaceVariant = palette.secondaryText,
        surfaceContainer = palette.chrome,
        surfaceContainerLow = palette.chrome,
        surfaceContainerHigh = palette.chrome,
    )
    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(LocalReaderPalette provides palette, content = content)
    }
}
