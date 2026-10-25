package com.example.fileopener.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fileopener.R
import com.example.fileopener.domain.model.ReaderTheme
import com.example.fileopener.domain.model.ReadingPosition
import com.example.fileopener.ui.common.EmptyState
import com.example.fileopener.ui.viewer.common.BookmarksDrawerContent
import com.example.fileopener.ui.viewer.common.ReaderThemeProvider
import com.example.fileopener.ui.viewer.common.ViewerScaffold
import com.example.fileopener.ui.viewer.common.effectiveTheme
import com.example.fileopener.ui.viewer.common.titleRes
import com.example.fileopener.ui.viewer.pdf.PdfBottomBar
import com.example.fileopener.ui.viewer.pdf.PdfBottomBarHeight
import com.example.fileopener.ui.viewer.common.ContentsDrawerContent
import com.example.fileopener.ui.viewer.common.ReaderSettingsSheet
import com.example.fileopener.ui.viewer.pdf.PdfViewer
import com.example.fileopener.ui.viewer.flow.WithDocumentNotice
import com.example.fileopener.ui.viewer.flow.FlowViewer
import com.example.fileopener.ui.viewer.common.SearchActions
import com.example.fileopener.ui.viewer.common.SearchField
import com.example.fileopener.ui.viewer.common.SearchStatus
import com.example.fileopener.ui.viewer.sheet.SheetTabs
import com.example.fileopener.ui.viewer.sheet.SheetTabsHeight
import com.example.fileopener.ui.viewer.sheet.SheetViewer
import androidx.compose.material3.HorizontalDivider
import kotlinx.coroutines.launch

/** Диспетчер просмотрщиков: общий каркас (панели, темы, закладки) + экран под формат документа. */
@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    viewModel: ViewerViewModel = viewModel(factory = ViewerViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = viewModel.readerSettings.collectAsStateWithLifecycle().value
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val isBookmarked by viewModel.isCurrentPositionBookmarked.collectAsStateWithLifecycle()
    val currentIndex by viewModel.currentIndex.collectAsStateWithLifecycle()

    // Уход в фон или выключение экрана: позицию дописываем сразу, не дожидаясь задержки.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flushPosition() }

    val theme = settings?.effectiveTheme() ?: ReaderTheme.LIGHT
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val loaded = state as? ViewerUiState.Loaded
    val pdfSession = (state as? ViewerUiState.Pdf)?.session
    val pdfSearch = pdfSession?.search?.collectAsStateWithLifecycle()?.value
    val sheetSession = (state as? ViewerUiState.Sheet)?.session
    val sheetSearch = sheetSession?.search?.collectAsStateWithLifecycle()?.value
    val currentSheet = sheetSession?.currentSheet?.collectAsStateWithLifecycle()?.value ?: 0
    val searchable = pdfSession != null || sheetSession != null
    val searchStatus = when {
        pdfSearch != null -> SearchStatus(pdfSearch.isActive, pdfSearch.matches.size, pdfSearch.currentIndex, pdfSearch.inProgress)
        sheetSearch != null -> SearchStatus(sheetSearch.isActive, sheetSearch.matches.size, sheetSearch.currentIndex, sheetSearch.inProgress)
        else -> SearchStatus()
    }
    val showSheetTabs = sheetSession != null && sheetSession.sheetNames.size > 1
    val flowDocument = (state as? ViewerUiState.Flow)?.content
    val tableOfContents = pdfSession?.tableOfContents?.collectAsStateWithLifecycle()?.value
        ?: flowDocument?.tableOfContents
        ?: emptyList()

    var searchMode by rememberSaveable { mutableStateOf(false) }
    var showTextSettings by rememberSaveable { mutableStateOf(false) }
    fun closeSearch() {
        searchMode = false
        pdfSession?.clearSearch()
        sheetSession?.clearSearch()
    }
    BackHandler(enabled = searchMode) { closeSearch() }

    val bookmarksContent = @Composable {
        BookmarksDrawerContent(
            bookmarks = bookmarks,
            positionLabel = { positionLabel(state, it) },
            onSelect = { bookmark ->
                viewModel.goTo(bookmark.position)
                scope.launch { drawerState.close() }
            },
            onDelete = viewModel::removeBookmark,
        )
    }

    ReaderThemeProvider(theme) {
        ViewerScaffold(
            title = documentTitle(state),
            onBack = onBack,
            // Пока документа нет, панели не прячем: иначе с экрана ошибки не уйти без свайпа.
            chromeVisible = chromeVisible || loaded == null,
            onToggleChrome = { if (loaded != null) chromeVisible = !chromeVisible },
            drawerState = drawerState,
            drawerContent = {
                ContentsDrawerContent(
                    // У PDF оглавление читается в фоне: null — «ещё загружается», вкладки уже показываем.
                    tableOfContents = if (pdfSession != null) pdfSession.tableOfContents.collectAsStateWithLifecycle().value else tableOfContents,
                    onTocSelect = { entry ->
                        viewModel.goTo(ReadingPosition(index = entry.targetIndex))
                        scope.launch { drawerState.close() }
                    },
                    targetLabel = if (pdfSession != null) {
                        { entry -> (entry.targetIndex + 1).toString() }
                    } else {
                        null
                    },
                    bookmarksContent = bookmarksContent,
                )
            },
            titleContent = if (searchMode && searchable) {
                { SearchField(onSearch = viewModel::search) }
            } else {
                null
            },
            actions = {
                if (searchMode && searchable) {
                    SearchActions(
                        status = searchStatus,
                        onPrevious = { pdfSession?.previousMatch() ?: sheetSession?.previousMatch() },
                        onNext = { pdfSession?.nextMatch() ?: sheetSession?.nextMatch() },
                        onClose = ::closeSearch,
                    )
                } else {
                    if (searchable) {
                        IconButton(onClick = { searchMode = true }) {
                            Icon(painterResource(R.drawable.ic_search), stringResource(R.string.action_search))
                        }
                    }
                    if (loaded != null) {
                        IconButton(onClick = viewModel::toggleBookmark) {
                            Icon(
                                painterResource(if (isBookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border),
                                stringResource(if (isBookmarked) R.string.action_bookmark_remove else R.string.action_bookmark_add),
                            )
                        }
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            if (pdfSession != null || tableOfContents.isNotEmpty()) {
                                Icon(painterResource(R.drawable.ic_toc), stringResource(R.string.action_contents_and_bookmarks))
                            } else {
                                Icon(painterResource(R.drawable.ic_bookmarks), stringResource(R.string.action_bookmarks))
                            }
                        }
                    }
                    when (loaded) {
                        // Для текста тема выбирается вместе со шрифтом в общей панели оформления.
                        is ViewerUiState.Flow -> IconButton(onClick = { showTextSettings = true }) {
                            Icon(painterResource(R.drawable.ic_text_format), stringResource(R.string.action_text_settings))
                        }
                        is ViewerUiState.Sheet -> ViewerMenuButton(
                            current = theme,
                            onSelect = viewModel::setTheme,
                            freezeHeaderRow = settings?.freezeHeaderRow ?: true,
                            onFreezeHeaderRowChange = viewModel::setFreezeHeaderRow,
                        )
                        else -> ViewerMenuButton(current = theme, onSelect = viewModel::setTheme)
                    }
                }
            },
            bottomBar = when {
                pdfSession != null -> {
                    {
                        PdfBottomBar(
                            currentPage = currentIndex,
                            pageCount = pdfSession.pageCount,
                            onGoToPage = { viewModel.goTo(ReadingPosition(index = it)) },
                        )
                    }
                }
                showSheetTabs -> {
                    { SheetTabs(names = sheetSession.sheetNames, selected = currentSheet, onSelect = sheetSession::selectSheet) }
                }
                else -> null
            },
            bottomBarHeight = when {
                pdfSession != null -> PdfBottomBarHeight
                showSheetTabs -> SheetTabsHeight
                else -> 0.dp
            },
        ) { contentPadding ->
            when (val current = state) {
                ViewerUiState.Loading -> Loading()

                is ViewerUiState.Error -> EmptyState(
                    iconRes = R.drawable.ic_doc_text,
                    title = null,
                    message = stringResource(current.messageRes),
                    modifier = Modifier.padding(contentPadding),
                )

                is ViewerUiState.PasswordRequired -> PasswordPrompt(
                    wrongPassword = current.wrongPassword,
                    onSubmit = viewModel::submitPassword,
                    modifier = Modifier.padding(contentPadding),
                )

                is ViewerUiState.Flow -> when {
                    settings == null -> Loading()

                    current.content.blocks.isEmpty() -> EmptyState(
                        iconRes = R.drawable.ic_doc_text,
                        title = null,
                        message = stringResource(R.string.viewer_empty_document),
                        modifier = Modifier.padding(contentPadding),
                    )

                    else -> WithDocumentNotice(current.content.notice, contentPadding) { padding ->
                        FlowViewer(
                            document = current.content,
                            settings = settings,
                            initialPosition = current.initialPosition,
                            navigationRequests = viewModel.navigationRequests,
                            contentPadding = padding,
                            onPositionChanged = viewModel::onPositionChanged,
                            onFontSizeChanged = viewModel::setFontSize,
                        )
                    }
                }

                is ViewerUiState.Pdf -> PdfViewer(
                    session = current.session,
                    initialPosition = current.initialPosition,
                    navigationRequests = viewModel.navigationRequests,
                    contentPadding = contentPadding,
                    onPositionChanged = viewModel::onPositionChanged,
                )

                is ViewerUiState.Sheet -> {
                    // Заголовки сетки закреплены и под панель не уходят: когда панели скрыты,
                    // поднимаем сетку к краю экрана, иначе сверху остаётся пустая полоса.
                    val cutoutTop = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
                    val top by animateDpAsState(
                        targetValue = if (chromeVisible) contentPadding.calculateTopPadding() else cutoutTop,
                        label = "sheetTop",
                    )
                    val layoutDirection = LocalLayoutDirection.current
                    SheetViewer(
                        session = current.session,
                        freezeHeaderRow = settings?.freezeHeaderRow ?: true,
                        navigationRequests = viewModel.navigationRequests,
                        contentPadding = PaddingValues(
                            start = contentPadding.calculateStartPadding(layoutDirection),
                            end = contentPadding.calculateEndPadding(layoutDirection),
                            top = top,
                            bottom = contentPadding.calculateBottomPadding(),
                        ),
                        onPositionChanged = viewModel::onPositionChanged,
                    )
                }
            }
        }

        if (showTextSettings && settings != null) {
            ReaderSettingsSheet(
                settings = settings,
                currentTheme = theme,
                onThemeChange = viewModel::setTheme,
                onFontChange = viewModel::setFont,
                onFontSizeChange = viewModel::setFontSize,
                onLineSpacingChange = viewModel::setLineSpacing,
                onDismiss = { showTextSettings = false },
            )
        }
    }
}

private fun documentTitle(state: ViewerUiState): String = when (state) {
    is ViewerUiState.Loaded -> state.document.name
    is ViewerUiState.Error -> state.document?.name.orEmpty()
    is ViewerUiState.PasswordRequired -> state.document.name
    ViewerUiState.Loading -> ""
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Подпись позиции в списке закладок; у каждого формата своя единица (абзац, страница, строка листа). */
@Composable
private fun positionLabel(state: ViewerUiState, position: ReadingPosition): String = when (state) {
    is ViewerUiState.Flow -> stringResource(R.string.position_paragraph, position.index + 1)
    is ViewerUiState.Pdf -> stringResource(R.string.position_page, position.index + 1)
    is ViewerUiState.Sheet -> stringResource(
        R.string.position_sheet_row,
        state.session.sheetNames.getOrElse(position.sheet) { "" },
        position.index + 1,
    )
    else -> ""
}

@Composable
private fun PasswordPrompt(wrongPassword: Boolean, onSubmit: (String) -> Unit, modifier: Modifier = Modifier) {
    var password by rememberSaveable { mutableStateOf("") }
    EmptyState(
        iconRes = R.drawable.ic_doc_pdf,
        title = stringResource(R.string.pdf_password_title),
        message = stringResource(R.string.pdf_password_message),
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.pdf_password_hint)) },
            singleLine = true,
            isError = wrongPassword,
            supportingText = if (wrongPassword) {
                { Text(stringResource(R.string.pdf_password_wrong)) }
            } else {
                null
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) onSubmit(password) }),
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) {
            Text(stringResource(R.string.action_open))
        }
    }
}

/**
 * Меню темы чтения. Для таблиц ([freezeHeaderRow] не `null`) — общее меню «Ещё» с темами
 * и переключателем закрепления первой строки.
 */
@Composable
private fun ViewerMenuButton(
    current: ReaderTheme,
    onSelect: (ReaderTheme) -> Unit,
    freezeHeaderRow: Boolean? = null,
    onFreezeHeaderRowChange: (Boolean) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            if (freezeHeaderRow == null) {
                Icon(painterResource(R.drawable.ic_palette), stringResource(R.string.action_reader_theme))
            } else {
                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.action_more))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (freezeHeaderRow != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sheet_freeze_header)) },
                    trailingIcon = {
                        if (freezeHeaderRow) Icon(painterResource(R.drawable.ic_check), contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onFreezeHeaderRowChange(!freezeHeaderRow)
                    },
                )
                HorizontalDivider()
            }
            ReaderTheme.entries.forEach { theme ->
                DropdownMenuItem(
                    text = { Text(stringResource(theme.titleRes)) },
                    trailingIcon = {
                        if (theme == current) Icon(painterResource(R.drawable.ic_check), contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onSelect(theme)
                    },
                )
            }
        }
    }
}
