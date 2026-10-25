package io.doccraftstudio.onedoc.ui.viewer.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.doccraftstudio.onedoc.R
import kotlinx.coroutines.launch

/** Высота верхней панели Material 3; на неё сдвигается начало документа, чтобы панель не закрывала первую строку. */
private val TopBarHeight = 64.dp

/**
 * Общий каркас всех просмотрщиков.
 *
 * Панели рисуются поверх документа и прячутся тапом по центру ([chromeVisible]); вместе с ними
 * прячутся системные панели (immersive). Отступы для содержимого считаются по
 * `systemBarsIgnoringVisibility`, поэтому при скрытии панелей документ не «прыгает».
 *
 * @param bottomBarHeight высота [bottomBar], чтобы конец документа не уходил под неё.
 * @param content получает отступы, которые нужно передать в `contentPadding` списка.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScaffold(
    title: String,
    onBack: () -> Unit,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    drawerState: DrawerState,
    drawerContent: @Composable () -> Unit,
    /** Замена заголовка (например, поле поиска); `null` — показывается [title]. */
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    bottomBarHeight: Dp = 0.dp,
    content: @Composable (PaddingValues) -> Unit,
) {
    val palette = LocalReaderPalette.current
    SystemBarsEffect(immersive = !chromeVisible, lightBars = !palette.isDark)

    // «Назад» при открытой панели закладок закрывает панель, а не документ.
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Свайп от края открывал бы панель вместо горизонтальной прокрутки таблиц и зума.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = { ModalDrawerSheet { drawerContent() } },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(palette.page)
                .centerTap(onToggleChrome),
        ) {
            content(readerContentPadding(bottomBarHeight))

            AnimatedVisibility(
                visible = chromeVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
            ) {
                TopAppBar(
                    title = titleContent ?: { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                        }
                    },
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }

            if (bottomBar != null) {
                AnimatedVisibility(
                    visible = chromeVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) { bottomBar() }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun readerContentPadding(bottomBarHeight: Dp): PaddingValues {
    val insets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout).asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = insets.calculateStartPadding(layoutDirection),
        end = insets.calculateEndPadding(layoutDirection),
        top = insets.calculateTopPadding() + TopBarHeight,
        bottom = insets.calculateBottomPadding() + bottomBarHeight,
    )
}
