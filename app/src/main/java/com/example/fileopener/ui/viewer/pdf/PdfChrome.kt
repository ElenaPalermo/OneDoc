package com.example.fileopener.ui.viewer.pdf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.fileopener.R
import kotlin.math.roundToInt

/** Высота нижней панели без системных отступов (их добавляет каркас). */
val PdfBottomBarHeight = 64.dp

/** Нижняя панель: номер страницы (тап — ввод номера) и ползунок для быстрого перехода. */
@Composable
fun PdfBottomBar(currentPage: Int, pageCount: Int, onGoToPage: (Int) -> Unit) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    // Пока ползунок тянут, показываем выбираемую страницу, а переходим при отпускании.
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shownPage = dragValue?.roundToInt() ?: currentPage

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(PdfBottomBarHeight)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.pdf_page_counter, shownPage + 1, pageCount),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .widthIn(min = 72.dp)
                    .clickable { showDialog = true }
                    .padding(vertical = 12.dp),
            )
            if (pageCount > 1) {
                Slider(
                    value = dragValue ?: currentPage.toFloat(),
                    onValueChange = { dragValue = it },
                    onValueChangeFinished = {
                        dragValue?.let { onGoToPage(it.roundToInt()) }
                        dragValue = null
                    },
                    valueRange = 0f..(pageCount - 1).toFloat(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showDialog) {
        GoToPageDialog(
            pageCount = pageCount,
            onConfirm = { page ->
                showDialog = false
                onGoToPage(page)
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun GoToPageDialog(pageCount: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val page = text.toIntOrNull()?.takeIf { it in 1..pageCount }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_go_to_page_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(R.string.pdf_go_to_page_hint, pageCount)) },
                singleLine = true,
                isError = text.isNotEmpty() && page == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { page?.let { onConfirm(it - 1) } }),
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { page?.let { onConfirm(it - 1) } }, enabled = page != null) {
                Text(stringResource(R.string.action_go))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
