package com.example.fileopener.ui.viewer.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.example.fileopener.R

/** Состояние поиска для панели: общее для PDF и таблиц. */
@Immutable
data class SearchStatus(
    val isActive: Boolean = false,
    val matchCount: Int = 0,
    val currentIndex: Int = -1,
    val inProgress: Boolean = false,
)

/** Поле поиска вместо заголовка верхней панели. */
@Composable
fun SearchField(onSearch: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                // Клавиатура закрывала бы половину экрана с найденным местом.
                keyboard?.hide()
                onSearch(query)
            },
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
}

/** Счётчик совпадений и переходы между ними — действия панели в режиме поиска. */
@Composable
fun RowScope.SearchActions(
    status: SearchStatus,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val text = when {
        !status.isActive -> null
        status.matchCount > 0 && status.currentIndex >= 0 -> stringResource(
            if (status.inProgress) R.string.search_count_partial else R.string.search_count,
            status.currentIndex + 1,
            status.matchCount,
        )
        status.inProgress -> stringResource(R.string.search_in_progress)
        else -> stringResource(R.string.search_no_results)
    }
    if (text != null) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
    }
    IconButton(onClick = onPrevious, enabled = status.matchCount > 0) {
        Icon(painterResource(R.drawable.ic_arrow_up), stringResource(R.string.search_previous))
    }
    IconButton(onClick = onNext, enabled = status.matchCount > 0) {
        Icon(painterResource(R.drawable.ic_arrow_down), stringResource(R.string.search_next))
    }
    IconButton(onClick = onClose) {
        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close_search))
    }
}
