package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.ai.AiRequestLog
import com.theveloper.pixelplay.data.ai.AiRequestLogStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiRequestLogViewModel @Inject constructor(
    private val store: AiRequestLogStore
) : ViewModel() {

    private val _logs = MutableStateFlow<List<AiRequestLog>>(emptyList())
    val logs: StateFlow<List<AiRequestLog>> = _logs.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _logs.value = store.list()
            _isLoading.value = false
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            store.clearAll()
            _logs.value = emptyList()
        }
    }

    fun fileFor(id: String) = store.fileFor(id)
}
