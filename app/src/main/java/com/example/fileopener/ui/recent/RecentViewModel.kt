package com.example.fileopener.ui.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.fileopener.appContainer
import com.example.fileopener.domain.model.RecentDocument
import com.example.fileopener.domain.repository.RecentRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecentUiState(
    val isLoading: Boolean = true,
    val documents: List<RecentDocument> = emptyList(),
)

class RecentViewModel(private val recentRepository: RecentRepository) : ViewModel() {

    val uiState: StateFlow<RecentUiState> = recentRepository.observeRecent()
        .map { RecentUiState(isLoading = false, documents = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentUiState())

    fun remove(uri: String) {
        viewModelScope.launch { recentRepository.remove(uri) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { RecentViewModel(appContainer.recentRepository) }
        }
    }
}
