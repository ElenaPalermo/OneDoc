package com.example.fileopener.ui.incoming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.fileopener.appContainer
import com.example.fileopener.domain.model.ImportResult
import com.example.fileopener.domain.repository.DocumentStorage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface IncomingEvent {
    data class Open(val uri: String) : IncomingEvent
    data class Unsupported(val fileName: String?) : IncomingEvent
    data object Failed : IncomingEvent
}

/**
 * Принимает файлы из Intent-ов `VIEW`/`SEND`. Живёт на уровне Activity: временный доступ к URI,
 * выданный вызывающим приложением, действует, пока жива Activity, поэтому импорт запускается сразу.
 */
class IncomingViewModel(private val storage: DocumentStorage) : ViewModel() {
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _events = Channel<IncomingEvent>(Channel.BUFFERED)
    val events: Flow<IncomingEvent> = _events.receiveAsFlow()

    fun open(uri: String, mimeType: String?) {
        viewModelScope.launch {
            _isImporting.value = true
            val event = try {
                when (val result = storage.importIncoming(uri, mimeType)) {
                    is ImportResult.Success -> IncomingEvent.Open(result.document.uri)
                    is ImportResult.Unsupported -> IncomingEvent.Unsupported(result.fileName)
                    ImportResult.Failed -> IncomingEvent.Failed
                }
            } finally {
                _isImporting.value = false
            }
            _events.send(event)
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { IncomingViewModel(appContainer.documentStorage) }
        }
    }
}
