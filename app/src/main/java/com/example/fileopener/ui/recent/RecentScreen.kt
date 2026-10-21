package com.example.fileopener.ui.recent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fileopener.R
import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.ui.common.DocumentMenuItem
import com.example.fileopener.ui.common.DocumentRow
import com.example.fileopener.ui.common.EmptyState

@Composable
fun RecentScreen(
    contentPadding: PaddingValues,
    onOpenDocument: (DocumentItem) -> Unit,
    menuItems: (DocumentItem) -> List<DocumentMenuItem>,
    viewModel: RecentViewModel = viewModel(factory = RecentViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        state.isLoading -> Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        state.documents.isEmpty() -> EmptyState(
            iconRes = R.drawable.ic_history,
            title = stringResource(R.string.recent_empty_title),
            message = stringResource(R.string.recent_empty_message),
            modifier = Modifier.padding(contentPadding),
        )

        else -> LazyColumn(contentPadding = contentPadding) {
            items(state.documents, key = { it.document.uri }) { recent ->
                DocumentRow(
                    document = recent.document,
                    timestamp = recent.lastOpenedAt,
                    onClick = { onOpenDocument(recent.document) },
                    menuItems = menuItems(recent.document),
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}
