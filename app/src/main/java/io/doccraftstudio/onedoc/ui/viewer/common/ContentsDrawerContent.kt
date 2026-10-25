package io.doccraftstudio.onedoc.ui.viewer.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.doccraftstudio.onedoc.R
import io.doccraftstudio.onedoc.reader.TocEntry

/**
 * Боковая панель: вкладки «Оглавление» и «Закладки». Если оглавления нет (TXT, PDF без outline,
 * Word без заголовков) — сразу закладки, без пустой вкладки. `null` — оглавление ещё загружается.
 *
 * @param targetLabel подпись цели пункта справа (номер страницы PDF); `null` — не показывать.
 */
@Composable
fun ContentsDrawerContent(
    tableOfContents: List<TocEntry>?,
    onTocSelect: (TocEntry) -> Unit,
    targetLabel: ((TocEntry) -> String)?,
    bookmarksContent: @Composable () -> Unit,
) {
    if (tableOfContents != null && tableOfContents.isEmpty()) {
        bookmarksContent()
        return
    }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    Column {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(stringResource(R.string.drawer_tab_contents)) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(stringResource(R.string.drawer_tab_bookmarks)) })
        }
        if (selectedTab == 0) TableOfContents(tableOfContents.orEmpty(), onTocSelect, targetLabel) else bookmarksContent()
    }
}

@Composable
private fun TableOfContents(entries: List<TocEntry>, onSelect: (TocEntry) -> Unit, targetLabel: ((TocEntry) -> String)?) {
    LazyColumn {
        items(entries) { entry ->
            ListItem(
                modifier = Modifier.clickable { onSelect(entry) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = {
                    Text(
                        entry.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = if (entry.level == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = (entry.level.coerceAtMost(MAX_TOC_INDENT_LEVEL) * 16).dp),
                    )
                },
                trailingContent = targetLabel?.let { label ->
                    { Text(label(entry), style = MaterialTheme.typography.labelMedium) }
                },
            )
        }
    }
}

private const val MAX_TOC_INDENT_LEVEL = 4
