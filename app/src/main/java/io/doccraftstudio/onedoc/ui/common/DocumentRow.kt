package io.doccraftstudio.onedoc.ui.common

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.doccraftstudio.onedoc.R
import io.doccraftstudio.onedoc.domain.model.DocumentCategory
import io.doccraftstudio.onedoc.domain.model.DocumentItem
import java.text.DateFormat
import java.util.Date

/** Пункт меню документа (кнопка «⋮» и долгое нажатие). */
class DocumentMenuItem(
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val iconRes: Int,
    val onClick: () -> Unit,
)

/**
 * Строка списка документов: иконка типа (миниатюра у PDF), имя, тип · размер · дата и меню действий.
 * [timestamp] — дата для подписи (изменения файла в библиотеке, открытия в «Недавних»).
 */
@Composable
fun DocumentRow(
    document: DocumentItem,
    timestamp: Long,
    onClick: () -> Unit,
    menuItems: List<DocumentMenuItem>,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ListItem(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true }),
        leadingContent = { DocumentLeadingIcon(document) },
        headlineContent = { Text(document.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(documentDetails(document, timestamp, withType = true), maxLines = 1) },
        trailingContent = {
            DocumentMenuButton(menuItems, expanded = menuExpanded, onExpandedChange = { menuExpanded = it })
        },
    )
}

/** Карточка документа для сетки: обложка (миниатюра PDF), имя, размер · дата и меню действий. */
@Composable
fun DocumentCard(
    document: DocumentItem,
    timestamp: Long,
    onClick: () -> Unit,
    menuItems: List<DocumentMenuItem>,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    OutlinedCard(modifier) {
        Column(
            Modifier
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true }),
        ) {
            // Кнопка меню — в углу обложки: имени без пробелов («отчёт_2026.xlsx») нужна вся ширина карточки.
            Box(Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT)) {
                DocumentCover(document, Modifier.matchParentSize())
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) {
                    DocumentMenuButton(menuItems, expanded = menuExpanded, onExpandedChange = { menuExpanded = it })
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    document.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    documentDetails(document, timestamp, withType = false),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DocumentMenuButton(
    items: List<DocumentMenuItem>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    if (items.isEmpty()) return
    Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.action_document_menu))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(item.labelRes)) },
                    leadingIcon = { Icon(painterResource(item.iconRes), contentDescription = null) },
                    onClick = {
                        onExpandedChange(false)
                        item.onClick()
                    },
                )
            }
        }
    }
}

@Composable
private fun documentDetails(document: DocumentItem, timestamp: Long, withType: Boolean): String {
    val context = LocalContext.current
    return listOfNotNull(
        // Группа «TXT/XML» объединяет два формата — в строке документа показываем конкретный.
        when {
            !withType -> null
            document.format.category == DocumentCategory.TEXT -> document.format.name
            else -> stringResource(document.format.category.labelRes)
        },
        Formatter.formatShortFileSize(context, document.sizeBytes),
        formatDate(timestamp),
    ).joinToString(" · ")
}

/** Обложка чуть выше квадрата — ближе к пропорциям страницы, но в ряд помещается больше карточек. */
private const val COVER_ASPECT = 0.9f

private fun formatDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(millis))
