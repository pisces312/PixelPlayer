package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.jellyfin.JellyfinRepository
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.netease.NeteaseRepository
import com.theveloper.pixelplay.data.qqmusic.QqMusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExternalServiceAccount {
    NETEASE,
    QQ_MUSIC,
    NAVIDROME,
    JELLYFIN
}

data class ExternalAccountUiModel(
    val service: ExternalServiceAccount,
    val title: String,
    val accountLabel: String,
    val syncedContentLabel: String,
    val isLoggingOut: Boolean
)

data class AccountsUiState(
    val connectedAccounts: List<ExternalAccountUiModel> = emptyList(),
    val disconnectedServices: List<ExternalServiceAccount> = emptyList()
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val neteaseRepository: NeteaseRepository,
    private val qqMusicRepository: QqMusicRepository,
    private val navidromeRepository: NavidromeRepository,
    private val jellyfinRepository: JellyfinRepository
) : ViewModel() {

    private val loggingOutServices = MutableStateFlow<Set<ExternalServiceAccount>>(emptySet())

    private val neteaseStateFlow = combine(
        neteaseRepository.isLoggedInFlow,
        neteaseRepository.getPlaylists().map { it.size }
    ) { connected, playlistCount ->
        connected to playlistCount
    }

    private val qqMusicStateFlow = combine(
        qqMusicRepository.isLoggedInFlow,
        qqMusicRepository.getPlaylists().map { it.size }
    ) { connected, playlistCount ->
        connected to playlistCount
    }

    private val navidromeStateFlow = combine(
        navidromeRepository.isLoggedInFlow,
        navidromeRepository.getPlaylists().map { it.size }
    ) { connected, playlistCount ->
        connected to playlistCount
    }

    private val jellyfinStateFlow = combine(
        jellyfinRepository.isLoggedInFlow,
        jellyfinRepository.getPlaylists().map { it.size }
    ) { connected, playlistCount ->
        connected to playlistCount
    }

    val uiState: StateFlow<AccountsUiState> = combine(
        combine(
            listOf(
                neteaseStateFlow,
                qqMusicStateFlow,
                navidromeStateFlow,
                jellyfinStateFlow
            )
        ) { it.toList() },
        loggingOutServices
    ) { states, activeLogouts ->
        val (neteaseConnected, neteasePlaylistCount) = states[0] as Pair<Boolean, Int>
        val (qqConnected, qqPlaylistCount) = states[1] as Pair<Boolean, Int>
        val (navidromeConnected, navidromePlaylistCount) = states[2] as Pair<Boolean, Int>
        val (jellyfinConnected, jellyfinPlaylistCount) = states[3] as Pair<Boolean, Int>

        val connectedAccounts = buildList {
            if (neteaseConnected) {
                add(
                    ExternalAccountUiModel(
                        service = ExternalServiceAccount.NETEASE,
                        title = "Netease Music",
                        accountLabel = neteaseRepository.userNickname
                            ?.takeIf { it.isNotBlank() }
                            ?: "Netease account connected",
                        syncedContentLabel = formatCount(
                            count = neteasePlaylistCount,
                            singular = "synced playlist",
                            plural = "synced playlists"
                        ),
                        isLoggingOut = ExternalServiceAccount.NETEASE in activeLogouts
                    )
                )
            }
            if (qqConnected) {
                add(
                    ExternalAccountUiModel(
                        service = ExternalServiceAccount.QQ_MUSIC,
                        title = "QQ Music",
                        accountLabel = qqMusicRepository.userNickname
                            ?.takeIf { it.isNotBlank() }
                            ?: "QQ Music account connected",
                        syncedContentLabel = formatCount(
                            count = qqPlaylistCount,
                            singular = "synced playlist",
                            plural = "synced playlists"
                        ),
                        isLoggingOut = ExternalServiceAccount.QQ_MUSIC in activeLogouts
                    )
                )
            }
            if (navidromeConnected) {
                add(
                    ExternalAccountUiModel(
                        service = ExternalServiceAccount.NAVIDROME,
                        title = "Subsonic",
                        accountLabel = navidromeRepository.username
                            ?.takeIf { it.isNotBlank() }
                            ?: "Subsonic account connected",
                        syncedContentLabel = formatCount(
                            count = navidromePlaylistCount,
                            singular = "synced playlist",
                            plural = "synced playlists"
                        ),
                        isLoggingOut = ExternalServiceAccount.NAVIDROME in activeLogouts
                    )
                )
            }
            if (jellyfinConnected) {
                add(
                    ExternalAccountUiModel(
                        service = ExternalServiceAccount.JELLYFIN,
                        title = "Jellyfin",
                        accountLabel = jellyfinRepository.username
                            ?.takeIf { it.isNotBlank() }
                            ?: "Jellyfin account connected",
                        syncedContentLabel = formatCount(
                            count = jellyfinPlaylistCount,
                            singular = "synced playlist",
                            plural = "synced playlists"
                        ),
                        isLoggingOut = ExternalServiceAccount.JELLYFIN in activeLogouts
                    )
                )
            }
        }

        val disconnectedServices = buildList {
            if (!neteaseConnected) add(ExternalServiceAccount.NETEASE)
            if (!qqConnected) add(ExternalServiceAccount.QQ_MUSIC)
            if (!navidromeConnected) add(ExternalServiceAccount.NAVIDROME)
            if (!jellyfinConnected) add(ExternalServiceAccount.JELLYFIN)
        }

        AccountsUiState(
            connectedAccounts = connectedAccounts,
            disconnectedServices = disconnectedServices
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    fun logout(service: ExternalServiceAccount) {
        if (service in loggingOutServices.value) return

        viewModelScope.launch {
            loggingOutServices.update { it + service }
            try {
                runCatching {
                    when (service) {
                        ExternalServiceAccount.NETEASE -> neteaseRepository.logout()
                        ExternalServiceAccount.QQ_MUSIC -> qqMusicRepository.logout()
                        ExternalServiceAccount.NAVIDROME -> navidromeRepository.logout()
                        ExternalServiceAccount.JELLYFIN -> jellyfinRepository.logout()
                    }
                }
            } finally {
                loggingOutServices.update { it - service }
            }
        }
    }

    private fun formatCount(count: Int, singular: String, plural: String): String {
        return if (count == 1) {
            "1 $singular"
        } else {
            "$count $plural"
        }
    }
}
