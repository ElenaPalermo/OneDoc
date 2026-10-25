package io.doccraftstudio.onedoc.ui.library

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.doccraftstudio.onedoc.R
import io.doccraftstudio.onedoc.domain.model.DocumentCategory
import io.doccraftstudio.onedoc.domain.model.DocumentItem
import io.doccraftstudio.onedoc.domain.model.LibraryFolder
import io.doccraftstudio.onedoc.domain.model.LibraryViewMode
import io.doccraftstudio.onedoc.ui.common.DocumentCard
import io.doccraftstudio.onedoc.ui.common.DocumentMenuItem
import io.doccraftstudio.onedoc.ui.common.DocumentRow
import io.doccraftstudio.onedoc.ui.common.accentColor
import io.doccraftstudio.onedoc.ui.common.iconRes
import io.doccraftstudio.onedoc.ui.common.labelRes
import io.doccraftstudio.onedoc.ui.common.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    pickers: LibraryPickers,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onOpenDocument: (DocumentItem) -> Unit,
    onToggleCategory: (DocumentCategory) -> Unit,
    onClearCategoryFilter: () -> Unit,
    menuItems: (DocumentItem) -> List<DocumentMenuItem>,
) {
    if (state.isLoading) {
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        if (state.totalCount > 0) {
            CategoryFilterRow(state.preferences.categoryFilter, onToggleCategory, onClearCategoryFilter)
        }
        PullToRefreshBox(
            isRefreshing = state.isScanning,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LibraryContent(state, pickers, onOpenDocument, menuItems)
        }
    }
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    pickers: LibraryPickers,
    onOpenDocument: (DocumentItem) -> Unit,
    menuItems: (DocumentItem) -> List<DocumentMenuItem>,
) {
    when {
        state.folders.isEmpty() -> ScrollableEmptyState {
            EmptyState(
                iconRes = R.drawable.ic_folder,
                title = stringResource(R.string.library_empty_title),
                message = stringResource(R.string.library_empty_message),
                modifier = it,
            ) {
                AddButtons(pickers)
                // До Android 11 «Загрузки» выбираются как любая папка.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Text(
                        stringResource(R.string.library_downloads_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        state.totalCount == 0 -> ScrollableEmptyState {
            EmptyState(
                iconRes = R.drawable.ic_doc_text,
                title = stringResource(R.string.library_no_documents_title),
                message = stringResource(R.string.library_no_documents_message),
                modifier = it,
            )
        }

        state.documents.isEmpty() -> ScrollableEmptyState {
            EmptyState(
                iconRes = R.drawable.ic_search,
                title = stringResource(R.string.library_nothing_found_title),
                message = stringResource(R.string.library_nothing_found_message),
                modifier = it,
            )
        }

        state.preferences.viewMode == LibraryViewMode.GRID -> LazyVerticalGrid(
            // В невысоком окне (ландшафт телефона) карточки уже, чтобы на экране помещался хотя бы ряд целиком.
            columns = GridCells.Adaptive(
                minSize = if (windowHeight() < CompactHeight) 120.dp else 156.dp,
            ),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = FAB_CLEARANCE),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.documents, key = { it.uri }) { document ->
                DocumentCard(
                    document = document,
                    timestamp = document.lastModified,
                    onClick = { onOpenDocument(document) },
                    menuItems = menuItems(document),
                    modifier = Modifier.animateItem(),
                )
            }
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
        ) {
            items(state.documents, key = { it.uri }) { document ->
                DocumentRow(
                    document = document,
                    timestamp = document.lastModified,
                    onClick = { onOpenDocument(document) },
                    menuItems = menuItems(document),
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

private val CompactHeight = 480.dp

@Composable
private fun windowHeight(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

/** Отступ снизу, чтобы FAB не перекрывал последний элемент. */
private val FAB_CLEARANCE = 88.dp

/** Фильтр по типу: «Все» сбрасывает выбор, типы выбираются по нескольку. */
@Composable
private fun CategoryFilterRow(
    selected: Set<DocumentCategory>,
    onToggle: (DocumentCategory) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected.isEmpty(),
            onClick = onClear,
            label = { Text(stringResource(R.string.filter_all)) },
        )
        DocumentCategory.entries.forEach { category ->
            FilterChip(
                selected = category in selected,
                onClick = { onToggle(category) },
                label = { Text(stringResource(category.labelRes)) },
                leadingIcon = {
                    Icon(
                        painterResource(category.iconRes),
                        contentDescription = null,
                        tint = category.accentColor,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

/**
 * Пустое состояние в прокручиваемом контейнере: так pull-to-refresh работает и на пустом экране,
 * а содержимое по-прежнему центрируется (и прокручивается в ландшафте, если не помещается).
 */
@Composable
private fun ScrollableEmptyState(content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            content(Modifier.heightIn(min = viewportHeight))
        }
    }
}

@Composable
private fun AddButtons(pickers: LibraryPickers) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(4.dp))
        Button(onClick = pickers.pickFolder) {
            Icon(painterResource(R.drawable.ic_folder_add), contentDescription = null)
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.action_add_folder))
        }
        OutlinedButton(onClick = pickers.pickFiles) {
            Icon(painterResource(R.drawable.ic_file_add), contentDescription = null)
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.action_add_files))
        }
    }
}

/** Нижний лист со списком папок библиотеки: удаление и добавление новых. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryFoldersSheet(
    folders: List<LibraryFolder>,
    pickers: LibraryPickers,
    onRemove: (LibraryFolder) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.library_folders_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn {
            if (folders.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.library_folders_empty),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(folders, key = { it.treeUri }) { folder ->
                ListItem(
                    leadingContent = {
                        Icon(
                            painterResource(if (folder.isSingleFiles) R.drawable.ic_file_add else R.drawable.ic_folder),
                            contentDescription = null,
                        )
                    },
                    headlineContent = {
                        Text(
                            if (folder.isSingleFiles) stringResource(R.string.library_single_files) else folder.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { onRemove(folder) }) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_remove))
                        }
                    },
                )
            }
            item {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    AddButtons(pickers)
                }
            }
        }
    }
}
