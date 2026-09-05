package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SortOption
import com.theveloper.pixelplay.data.model.StorageFilter
import com.theveloper.pixelplay.data.model.YearBucket
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class YearDetailUiState(
    val year: Int? = null,
    val songs: List<Song> = emptyList(),
    val totalDurationMs: Long = 0L,
    val sortOption: SortOption = SortOption.YearSongPlayCount,
    val isLoading: Boolean = true
) {
    val isUnknownYear: Boolean get() = year != null && year == YearBucket.UNKNOWN_YEAR
}

/**
 * 年份智能播放列表详情（L2）。歌曲由 Room 按当前排序 SQL 层排序后以响应式 Flow 提供，
 * 排序偏好经 [UserPreferencesRepository] 全局持久化（所有年份共享同一份排序偏好）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class YearDetailViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(YearDetailUiState())
    val uiState: StateFlow<YearDetailUiState> = _uiState.asStateFlow()

    private val yearFlow = MutableStateFlow<Int?>(null)
    private val sortOptionFlow = MutableStateFlow<SortOption>(SortOption.YearSongPlayCount)
    private val storageFilterFlow = MutableStateFlow(StorageFilter.ALL)

    private var collectJob: Job? = null
    private var started = false

    init {
        viewModelScope.launch {
            val savedSortKey = userPreferencesRepository.yearDetailSortOptionFlow.first()
            sortOptionFlow.value =
                SortOption.YEAR_SONGS.firstOrNull { it.storageKey == savedSortKey }
                    ?: SortOption.YearSongPlayCount
            storageFilterFlow.value = userPreferencesRepository.lastStorageFilterFlow.first()
        }
    }

    /** 由页面在进入时调用；重复调用同一年份不会重建收集链。 */
    fun loadYear(year: Int) {
        if (started && yearFlow.value == year) return
        started = true
        yearFlow.value = year
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            combine(
                yearFlow.filterNotNull(),
                sortOptionFlow,
                storageFilterFlow
            ) { y, sort, filter -> Triple(y, sort, filter) }
                .flatMapLatest { (y, sort, filter) ->
                    _uiState.update { it.copy(isLoading = true) }
                    musicRepository.getSongsByYear(y, sort, filter)
                }
                .collect { songs ->
                    _uiState.update {
                        it.copy(
                            year = yearFlow.value,
                            songs = songs,
                            totalDurationMs = songs.sumOf { song -> song.duration },
                            sortOption = sortOptionFlow.value,
                            isLoading = false
                        )
                    }
                }
        }
    }

    fun setSortOption(option: SortOption) {
        sortOptionFlow.value = option
        viewModelScope.launch {
            userPreferencesRepository.setYearDetailSortOption(option.storageKey)
        }
    }

    /** 一键反向：在当前排序方法的升/降序对侧选项间切换。 */
    fun flipSortDirection() {
        setSortOption(sortOptionFlow.value.flipDirection())
    }
}
