package io.doccraftstudio.onedoc.ui.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.doccraftstudio.onedoc.R
import io.doccraftstudio.onedoc.data.settings.AppLanguageStore
import io.doccraftstudio.onedoc.domain.model.DocumentItem
import io.doccraftstudio.onedoc.domain.model.LibraryPreferences
import io.doccraftstudio.onedoc.domain.model.LibraryViewMode
import io.doccraftstudio.onedoc.domain.model.SortField
import io.doccraftstudio.onedoc.ui.common.DeleteDocumentDialog
import io.doccraftstudio.onedoc.ui.common.DocumentItemSaver
import io.doccraftstudio.onedoc.ui.common.DocumentMenuItem
import io.doccraftstudio.onedoc.ui.common.RenameDocumentDialog
import io.doccraftstudio.onedoc.ui.common.openDocumentWith
import io.doccraftstudio.onedoc.ui.common.shareDocument
import io.doccraftstudio.onedoc.ui.library.LibraryFoldersSheet
import io.doccraftstudio.onedoc.ui.library.LibraryMessage
import io.doccraftstudio.onedoc.ui.library.LibraryPickers
import io.doccraftstudio.onedoc.ui.library.LibraryScreen
import io.doccraftstudio.onedoc.ui.library.LibraryViewModel
import io.doccraftstudio.onedoc.ui.library.rememberLibraryPickers
import io.doccraftstudio.onedoc.ui.recent.RecentScreen
import kotlinx.coroutines.launch

private enum class HomeTab(@param:StringRes val labelRes: Int, @param:DrawableRes val iconRes: Int) {
    LIBRARY(R.string.tab_library, R.drawable.ic_folder),
    RECENT(R.string.tab_recent, R.drawable.ic_history),
}

/** С этой ширины (ландшафт телефона, планшет) вкладки переезжают в боковую навигационную панель. */
private val NavigationRailMinWidth = 600.dp

/** Уже этой ширины (телефон в портрете) «Вид» и «Папки библиотеки» уходят в меню ⋮, чтобы название помещалось. */
private val CompactActionsMaxWidth = 480.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDocument: (DocumentItem) -> Unit,
    libraryViewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
    actionsViewModel: DocumentActionsViewModel = viewModel(factory = DocumentActionsViewModel.Factory),
) {
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.LIBRARY) }
    var showFoldersSheet by rememberSaveable { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var renameTarget by rememberSaveable(stateSaver = DocumentItemSaver) { mutableStateOf<DocumentItem?>(null) }
    var deleteTarget by rememberSaveable(stateSaver = DocumentItemSaver) { mutableStateOf<DocumentItem?>(null) }
    val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val pickers = rememberLibraryPickers(libraryViewModel)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val useRail = windowWidth >= NavigationRailMinWidth
    val compactActions = windowWidth < CompactActionsMaxWidth

    LaunchedEffect(libraryViewModel) {
        libraryViewModel.messages.collect { message ->
            val text = when (message) {
                LibraryMessage.FolderAccessFailed -> resources.getString(R.string.message_folder_access_failed)
                LibraryMessage.NoSupportedFiles -> resources.getString(R.string.message_no_supported_files)
                is LibraryMessage.FilesAdded ->
                    resources.getQuantityString(R.plurals.message_files_added, message.count, message.count)
                is LibraryMessage.FoldersUnavailable ->
                    resources.getString(R.string.message_folders_unavailable, message.names.joinToString())
            }
            snackbarHostState.showSnackbar(text)
        }
    }
    LaunchedEffect(actionsViewModel) {
        actionsViewModel.messages.collect { message ->
            val text = when (message) {
                DocumentActionMessage.RenameFailed -> resources.getString(R.string.message_rename_failed)
                DocumentActionMessage.DeleteFailed -> resources.getString(R.string.message_delete_failed)
                is DocumentActionMessage.Deleted -> resources.getString(R.string.message_deleted, message.name)
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    fun closeSearch() {
        searchActive = false
        libraryViewModel.setQuery("")
    }
    BackHandler(enabled = searchActive) { closeSearch() }

    val scope = rememberCoroutineScope()
    fun menuItems(document: DocumentItem, inRecent: Boolean): List<DocumentMenuItem> = buildList {
        add(
            DocumentMenuItem(R.string.action_share, R.drawable.ic_share) {
                if (!context.shareDocument(document)) {
                    scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.message_share_failed)) }
                }
            },
        )
        add(
            DocumentMenuItem(R.string.action_open_with, R.drawable.ic_open_in_new) {
                if (!context.openDocumentWith(document)) {
                    scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.message_open_with_failed)) }
                }
            },
        )
        add(DocumentMenuItem(R.string.action_rename, R.drawable.ic_edit) { renameTarget = document })
        if (inRecent) {
            add(
                DocumentMenuItem(R.string.action_remove_from_recent, R.drawable.ic_close) {
                    actionsViewModel.removeFromRecent(document)
                },
            )
        }
        add(DocumentMenuItem(R.string.action_delete, R.drawable.ic_delete) { deleteTarget = document })
    }

    // Боковая панель сама отступает от системных панелей слева; остальному экрану этот край не нужен.
    val contentInsets = if (useRail) {
        ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.End + WindowInsetsSides.Vertical)
    } else {
        ScaffoldDefaults.contentWindowInsets
    }

    Row(Modifier.fillMaxSize()) {
        if (useRail) {
            NavigationRail {
                HomeTab.entries.forEach { tab ->
                    NavigationRailItem(
                        selected = tab == selectedTab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(painterResource(tab.iconRes), contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            contentWindowInsets = contentInsets,
            topBar = {
                val isLibrary = selectedTab == HomeTab.LIBRARY
                TopAppBar(
                    windowInsets = if (useRail) {
                        TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.End)
                    } else {
                        TopAppBarDefaults.windowInsets
                    },
                    navigationIcon = {
                        if (isLibrary && searchActive) {
                            IconButton(onClick = ::closeSearch) {
                                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_close_search))
                            }
                        }
                    },
                    title = {
                        if (isLibrary && searchActive) {
                            LibrarySearchField(libraryState.query, libraryViewModel::setQuery)
                        } else {
                            // При ширине 480–600 dp кнопок в панели много — название иначе переносится на вторую строку.
                            Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    actions = {
                        if (isLibrary) {
                            LibraryActions(
                                searchActive = searchActive,
                                hasDocuments = libraryState.totalCount > 0,
                                preferences = libraryState.preferences,
                                onSearch = { searchActive = true },
                                onClearQuery = { libraryViewModel.setQuery("") },
                                onSort = libraryViewModel::sortBy,
                                onViewMode = libraryViewModel::setViewMode,
                                onManageFolders = { showFoldersSheet = true },
                                compact = compactActions,
                            )
                        }
                        if (!(isLibrary && searchActive)) {
                            OverflowMenuButton(
                                buildList {
                                    if (isLibrary && compactActions) {
                                        if (libraryState.totalCount > 0) {
                                            val grid = libraryState.preferences.viewMode == LibraryViewMode.GRID
                                            add(
                                                DocumentMenuItem(
                                                    if (grid) R.string.action_view_list else R.string.action_view_grid,
                                                    if (grid) R.drawable.ic_view_list else R.drawable.ic_view_grid,
                                                ) { libraryViewModel.setViewMode(if (grid) LibraryViewMode.LIST else LibraryViewMode.GRID) },
                                            )
                                        }
                                        add(DocumentMenuItem(R.string.action_manage_folders, R.drawable.ic_folder) { showFoldersSheet = true })
                                    }
                                    add(DocumentMenuItem(R.string.action_language, R.drawable.ic_language) { showLanguageDialog = true })
                                },
                            )
                        }
                    },
                )
            },
            floatingActionButton = {
                if (selectedTab == HomeTab.LIBRARY && libraryState.folders.isNotEmpty() && !searchActive) AddFab(pickers)
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        HomeTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = tab == selectedTab,
                                onClick = { selectedTab = tab },
                                icon = { Icon(painterResource(tab.iconRes), contentDescription = null) },
                                label = { Text(stringResource(tab.labelRes)) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            when (selectedTab) {
                HomeTab.LIBRARY -> LibraryScreen(
                    state = libraryState,
                    pickers = pickers,
                    contentPadding = innerPadding,
                    onRefresh = libraryViewModel::refresh,
                    onOpenDocument = onOpenDocument,
                    onToggleCategory = libraryViewModel::toggleCategory,
                    onClearCategoryFilter = libraryViewModel::clearCategoryFilter,
                    menuItems = { menuItems(it, inRecent = false) },
                )

                HomeTab.RECENT -> RecentScreen(
                    contentPadding = innerPadding,
                    onOpenDocument = onOpenDocument,
                    menuItems = { menuItems(it, inRecent = true) },
                )
            }
        }
    }

    if (showFoldersSheet) {
        LibraryFoldersSheet(
            folders = libraryState.folders,
            pickers = pickers,
            onRemove = libraryViewModel::removeFolder,
            onDismiss = { showFoldersSheet = false },
        )
    }
    renameTarget?.let { document ->
        RenameDocumentDialog(
            document = document,
            onConfirm = { name ->
                renameTarget = null
                actionsViewModel.rename(document, name)
            },
            onDismiss = { renameTarget = null },
        )
    }
    if (showLanguageDialog) {
        val activity = LocalActivity.current
        LanguageDialog(
            current = remember { AppLanguageStore.current(context) },
            onSelect = { language ->
                // Закрываем до пересоздания Activity, иначе сохранённое состояние снова откроет диалог.
                showLanguageDialog = false
                activity?.let { AppLanguageStore.apply(it, language) }
            },
            onDismiss = { showLanguageDialog = false },
        )
    }
    deleteTarget?.let { document ->
        DeleteDocumentDialog(
            document = document,
            onConfirm = {
                deleteTarget = null
                actionsViewModel.delete(document)
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun LibrarySearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.library_search_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.focusRequester(focusRequester),
    )
}

@Composable
private fun LibraryActions(
    searchActive: Boolean,
    hasDocuments: Boolean,
    preferences: LibraryPreferences,
    onSearch: () -> Unit,
    onClearQuery: () -> Unit,
    onSort: (SortField) -> Unit,
    onViewMode: (LibraryViewMode) -> Unit,
    onManageFolders: () -> Unit,
    compact: Boolean,
) {
    if (searchActive) {
        IconButton(onClick = onClearQuery) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close_search))
        }
        return
    }
    if (hasDocuments) {
        IconButton(onClick = onSearch) {
            Icon(painterResource(R.drawable.ic_search), stringResource(R.string.action_search_library))
        }
        SortMenuButton(preferences, onSort)
    }
    // В компактном режиме «Вид» и «Папки библиотеки» — пункты меню ⋮.
    if (compact) return
    if (hasDocuments) {
        val grid = preferences.viewMode == LibraryViewMode.GRID
        IconButton(onClick = { onViewMode(if (grid) LibraryViewMode.LIST else LibraryViewMode.GRID) }) {
            Icon(
                painterResource(if (grid) R.drawable.ic_view_list else R.drawable.ic_view_grid),
                stringResource(if (grid) R.string.action_view_list else R.string.action_view_grid),
            )
        }
    }
    IconButton(onClick = onManageFolders) {
        Icon(painterResource(R.drawable.ic_folder), stringResource(R.string.action_manage_folders))
    }
}

@Composable
private fun SortMenuButton(preferences: LibraryPreferences, onSort: (SortField) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_sort), stringResource(R.string.action_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortField.entries.forEach { field ->
                val selected = field == preferences.sortField
                val label = stringResource(
                    when (field) {
                        SortField.DATE -> R.string.sort_date
                        SortField.NAME -> R.string.sort_name
                        SortField.SIZE -> R.string.sort_size
                    },
                )
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = {
                        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null)
                    },
                    trailingIcon = {
                        if (selected) {
                            val descending = preferences.sortDescending
                            Icon(
                                painterResource(if (descending) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up),
                                stringResource(if (descending) R.string.sort_descending else R.string.sort_ascending),
                            )
                        }
                    },
                    // Меню остаётся открытым: повторное нажатие на поле сразу меняет направление.
                    onClick = { onSort(field) },
                )
            }
        }
    }
}

@Composable
private fun OverflowMenuButton(items: List<DocumentMenuItem>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(item.labelRes)) },
                    leadingIcon = { Icon(painterResource(item.iconRes), contentDescription = null) },
                    onClick = {
                        expanded = false
                        item.onClick()
                    },
                )
            }
        }
    }
}

@Composable
private fun AddFab(pickers: LibraryPickers) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        ExtendedFloatingActionButton(
            onClick = { menuExpanded = true },
            icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
            text = { Text(stringResource(R.string.action_add)) },
        )
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_add_folder)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_folder_add), contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    pickers.pickFolder()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_add_files)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_file_add), contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    pickers.pickFiles()
                },
            )
        }
    }
}
