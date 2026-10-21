package com.example.fileopener.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.fileopener.appContainer
import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.domain.repository.RecentRepository
import com.example.fileopener.domain.usecase.DeleteDocumentUseCase
import com.example.fileopener.domain.usecase.RenameDocumentUseCase
import com.example.fileopener.domain.usecase.RenameResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Итог действия над файлом для Snackbar. */
sealed interface DocumentActionMessage {
    data object RenameFailed : DocumentActionMessage
    data object DeleteFailed : DocumentActionMessage
    data class Deleted(val name: String) : DocumentActionMessage
}

/** Переименование и удаление файлов из библиотеки и «Недавних». */
class DocumentActionsViewModel(
    private val renameDocument: RenameDocumentUseCase,
    private val deleteDocument: DeleteDocumentUseCase,
    private val recentRepository: RecentRepository,
) : ViewModel() {
    private val _messages = Channel<DocumentActionMessage>(Channel.BUFFERED)
    val messages: Flow<DocumentActionMessage> = _messages.receiveAsFlow()

    fun rename(document: DocumentItem, newName: String) {
        viewModelScope.launch {
            // Неверное имя диалог не пропускает, поэтому здесь остаётся только отказ провайдера.
            if (renameDocument(document, newName) !is RenameResult.Success) {
                _messages.send(DocumentActionMessage.RenameFailed)
            }
        }
    }

    fun delete(document: DocumentItem) {
        viewModelScope.launch {
            _messages.send(
                if (deleteDocument(document)) DocumentActionMessage.Deleted(document.name) else DocumentActionMessage.DeleteFailed,
            )
        }
    }

    fun removeFromRecent(document: DocumentItem) {
        viewModelScope.launch { recentRepository.remove(document.uri) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = appContainer
                DocumentActionsViewModel(container.renameDocument, container.deleteDocument, container.recentRepository)
            }
        }
    }
}
