package io.doccraftstudio.onedoc.ui.viewer.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.doccraftstudio.onedoc.R
import io.doccraftstudio.onedoc.domain.model.Bookmark
import io.doccraftstudio.onedoc.domain.model.ReadingPosition

/**
 * Список закладок документа для боковой панели.
 * [positionLabel] переводит позицию в понятную подпись («Абзац 12», «Стр. 5», «Лист 2, строка 40»).
 */
@Composable
fun BookmarksDrawerContent(
    bookmarks: List<Bookmark>,
    positionLabel: @Composable (ReadingPosition) -> String,
    onSelect: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
) {
    Text(
        stringResource(R.string.bookmarks_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
    )
    if (bookmarks.isEmpty()) {
        Text(
            stringResource(R.string.bookmarks_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        return
    }
    LazyColumn {
        items(bookmarks, key = { it.id }) { bookmark ->
            val label = positionLabel(bookmark.position)
            ListItem(
                modifier = Modifier.clickable { onSelect(bookmark) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(painterResource(R.drawable.ic_bookmark), contentDescription = null) },
                headlineContent = {
                    Text(bookmark.title.ifBlank { label }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = if (bookmark.title.isNotBlank()) {
                    { Text(label) }
                } else {
                    null
                },
                trailingContent = {
                    IconButton(onClick = { onDelete(bookmark) }) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_bookmark_remove))
                    }
                },
            )
        }
    }
}
