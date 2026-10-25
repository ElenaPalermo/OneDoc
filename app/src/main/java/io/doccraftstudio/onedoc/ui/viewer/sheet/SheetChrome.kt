package io.doccraftstudio.onedoc.ui.viewer.sheet

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Высота панели вкладок без системных отступов (их добавляет каркас). */
val SheetTabsHeight = 48.dp

/** Вкладки листов книги внизу экрана, как в Excel. */
@Composable
fun SheetTabs(names: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        PrimaryScrollableTabRow(
            selectedTabIndex = selected,
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(SheetTabsHeight),
        ) {
            names.forEachIndexed { index, name ->
                Tab(
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
                )
            }
        }
    }
}
