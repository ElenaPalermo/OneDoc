package com.example.fileopener.ui.viewer.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.fileopener.R
import com.example.fileopener.domain.model.ReaderFont
import com.example.fileopener.domain.model.ReaderSettings
import com.example.fileopener.domain.model.ReaderTheme
import com.example.fileopener.ui.viewer.flow.toFontFamily
import androidx.compose.ui.platform.LocalLocale
import kotlin.math.roundToInt

private const val MIN_SLIDER_FONT = 12f
private const val MAX_SLIDER_FONT = 32f
private const val MIN_SLIDER_SPACING = 1f
private const val MAX_SLIDER_SPACING = 2.2f
private const val SPACING_STEP = 0.1f

/**
 * Настройки чтения документов с переносом: тема, шрифт, размер, межстрочный интервал.
 * Ползунки меняют предпросмотр сразу, а сохраняются при отпускании — без записи в DataStore на каждом кадре.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    currentTheme: ReaderTheme,
    onThemeChange: (ReaderTheme) -> Unit,
    onFontChange: (ReaderFont) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var fontSize by remember(settings.fontSizeSp) { mutableFloatStateOf(settings.fontSizeSp.coerceIn(MIN_SLIDER_FONT, MAX_SLIDER_FONT)) }
    var lineSpacing by remember(settings.lineSpacing) { mutableFloatStateOf(settings.lineSpacing) }
    val locale = LocalLocale.current.platformLocale

    // Сразу во всю высоту: в ландшафте наполовину раскрытая шторка показывала лишь пример текста.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)

            Text(
                stringResource(R.string.settings_preview),
                style = TextStyle(
                    color = currentTheme.palette().text,
                    fontSize = fontSize.sp,
                    lineHeight = lineSpacing.em,
                    fontFamily = settings.font.toFontFamily(),
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(currentTheme.palette().page, MaterialTheme.shapes.medium)
                    .padding(12.dp),
            )

            SettingLabel(stringResource(R.string.settings_theme))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ReaderTheme.entries.forEach { theme ->
                    ThemeSwatch(theme, selected = theme == currentTheme, onClick = { onThemeChange(theme) })
                }
            }

            SettingLabel(stringResource(R.string.settings_font))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ReaderFont.entries.forEachIndexed { index, font ->
                    SegmentedButton(
                        selected = font == settings.font,
                        onClick = { onFontChange(font) },
                        shape = SegmentedButtonDefaults.itemShape(index, ReaderFont.entries.size),
                        label = { Text(stringResource(font.titleRes), fontFamily = font.toFontFamily(), maxLines = 1) },
                    )
                }
            }

            SettingLabel(stringResource(R.string.settings_font_size), value = fontSize.roundToInt().toString())
            Slider(
                value = fontSize,
                onValueChange = { fontSize = it.roundToInt().toFloat() },
                onValueChangeFinished = { onFontSizeChange(fontSize) },
                valueRange = MIN_SLIDER_FONT..MAX_SLIDER_FONT,
                steps = (MAX_SLIDER_FONT - MIN_SLIDER_FONT).toInt() - 1,
            )

            SettingLabel(stringResource(R.string.settings_line_spacing), value = String.format(locale, "%.1f", lineSpacing))
            Slider(
                value = lineSpacing,
                onValueChange = { lineSpacing = (it / SPACING_STEP).roundToInt() * SPACING_STEP },
                onValueChangeFinished = { onLineSpacingChange(lineSpacing) },
                valueRange = MIN_SLIDER_SPACING..MAX_SLIDER_SPACING,
                steps = ((MAX_SLIDER_SPACING - MIN_SLIDER_SPACING) / SPACING_STEP).roundToInt() - 1,
            )
        }
    }
}

@Composable
private fun SettingLabel(title: String, value: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        if (value != null) Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ThemeSwatch(theme: ReaderTheme, selected: Boolean, onClick: () -> Unit) {
    val palette = theme.palette()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .background(palette.page, CircleShape)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
        ) {
            Text("Aa", color = palette.text, style = MaterialTheme.typography.titleMedium)
        }
        Text(stringResource(theme.titleRes), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

private val ReaderFont.titleRes: Int
    get() = when (this) {
        ReaderFont.SANS -> R.string.font_sans
        ReaderFont.SERIF -> R.string.font_serif
        ReaderFont.MONO -> R.string.font_mono
    }
