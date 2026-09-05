package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SortOption
import com.theveloper.pixelplay.data.model.YearBucket
import com.theveloper.pixelplay.presentation.components.LibrarySortBottomSheet
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet
import com.theveloper.pixelplay.presentation.components.SongInfoBottomSheet
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixelplay.presentation.viewmodel.YearDetailViewModel
import com.theveloper.pixelplay.utils.formatDuration
import com.theveloper.pixelplay.utils.formatSongCount
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 年份智能播放列表详情（L2）：展示某一年（或未知年份）下的全部歌曲，
 * 支持 9 种属性排序与一键反向，排序偏好全局持久化。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YearDetailScreen(
    navController: NavHostController,
    year: Int,
    playerViewModel: PlayerViewModel,
    viewModel: YearDetailViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel()
) {
    LaunchedEffect(year) { viewModel.loadYear(year) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()

    val currentSongId by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val isPlaying by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.isPlaying }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    var showSortSheet by remember { mutableStateOf(false) }
    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    var selectedSongForInfo by remember { mutableStateOf<Song?>(null) }

    val isUnknown = year == YearBucket.UNKNOWN_YEAR
    val title = if (isUnknown) stringResource(R.string.unknown_year) else year.toString()
    val queueName = title
    val songs = uiState.songs
    val bottomBarHeightDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val bgColors = listOf(
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.24f),
        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        MaterialTheme.colorScheme.surface
    )
    val backgroundBrush = remember(bgColors) {
        Brush.verticalGradient(colors = bgColors, endY = 1200f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                bottom = MiniPlayerHeight + bottomBarHeightDp + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "year_detail_header") {
                YearDetailHeader(
                    title = title,
                    isUnknown = isUnknown,
                    songCount = songs.size,
                    totalDurationMs = uiState.totalDurationMs
                )
            }

            if (songs.isNotEmpty()) {
                item(key = "year_detail_actions") {
                    YearDetailActions(
                        onPlay = {
                            val first = songs.firstOrNull() ?: return@YearDetailActions
                            playerViewModel.playSongs(songs, first, queueName)
                        },
                        onShuffle = {
                            playerViewModel.playSongsShuffled(
                                songsToPlay = songs,
                                queueName = queueName,
                                startAtZero = true
                            )
                        },
                        onSort = { showSortSheet = true },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                items(items = songs, key = { it.id }, contentType = { "year_song" }) { song ->
                    EnhancedSongListItem(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        song = song,
                        isCurrentSong = currentSongId == song.id,
                        isPlaying = currentSongId == song.id && isPlaying,
                        onClick = {
                            playerViewModel.playSongs(songs, song, queueName)
                        },
                        onMoreOptionsClick = { tapped ->
                            selectedSongForInfo = tapped
                            showSongInfoBottomSheet = true
                        }
                    )
                }
            } else if (!uiState.isLoading) {
                item(key = "year_detail_empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.library_empty_years_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        FilledIconButton(
            onClick = { navController.popBackStack() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 10.dp, top = 8.dp)
                .clip(CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.common_back)
            )
        }
    }

    if (showSortSheet) {
        LibrarySortBottomSheet(
            title = stringResource(R.string.library_sort_by_title),
            options = SortOption.YEAR_SONGS,
            selectedOption = uiState.sortOption,
            onDismiss = { showSortSheet = false },
            onOptionSelected = { option ->
                viewModel.setSortOption(option)
                showSortSheet = false
            },
            onDirectionToggle = { option -> viewModel.setSortOption(option) }
        )
    }

    val infoSong = selectedSongForInfo
    if (showSongInfoBottomSheet && infoSong != null) {
        SongInfoBottomSheet(
            song = infoSong,
            isFavorite = favoriteSongIds.contains(infoSong.id),
            onToggleFavorite = { playerViewModel.toggleFavoriteSpecificSong(infoSong) },
            onDismiss = {
                showSongInfoBottomSheet = false
                showPlaylistBottomSheet = false
            },
            onPlaySong = { playerViewModel.playSongs(songs, infoSong, queueName) },
            onAddToQueue = { playerViewModel.addSongToQueue(infoSong) },
            onAddNextToQueue = { playerViewModel.addSongNextToQueue(infoSong) },
            onAddToPlayList = { showPlaylistBottomSheet = true },
            onDeleteFromDevice = playerViewModel::deleteFromDevice,
            onNavigateToAlbum = {
                navController.navigateSafely(Screen.AlbumDetail.createRoute(infoSong.albumId))
                showSongInfoBottomSheet = false
            },
            onNavigateToArtist = {
                navController.navigateSafely(Screen.ArtistDetail.createRoute(infoSong.artistId))
                showSongInfoBottomSheet = false
            },
            onNavigateToArtistById = { artistId ->
                navController.navigateSafely(Screen.ArtistDetail.createRoute(artistId))
                showSongInfoBottomSheet = false
            },
            onNavigateToGenre = {
                infoSong.genre?.let {
                    navController.navigateSafely(
                        Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it, "UTF-8"))
                    )
                }
                showSongInfoBottomSheet = false
            },
            onEditSong = { newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate ->
                playerViewModel.editSongMetadata(
                    infoSong,
                    newTitle,
                    newArtist,
                    newAlbum,
                    newAlbumArtist,
                    newComposer,
                    newGenre,
                    newLyrics,
                    newTrackNumber,
                    newDiscNumber,
                    replayGainTrackGainDb,
                    replayGainAlbumGainDb,
                    coverArtUpdate
                )
            },
            removeFromListTrigger = {}
        )

        if (showPlaylistBottomSheet) {
            PlaylistBottomSheet(
                playlistUiState = playlistUiState,
                songs = listOf(infoSong),
                onDismiss = { showPlaylistBottomSheet = false },
                bottomBarHeight = bottomBarHeightDp,
                playerViewModel = playerViewModel
            )
        }
    }
}

@Composable
private fun YearDetailHeader(
    title: String,
    isUnknown: Boolean,
    songCount: Int,
    totalDurationMs: Long
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 96.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (isUnknown) {
                Icon(
                    painter = painterResource(R.drawable.rounded_calendar_view_week_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(56.dp)
                )
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(6.dp))

        val meta = buildString {
            append(formatSongCount(songCount))
            if (totalDurationMs > 0) {
                append(" · ")
                append(formatDuration(totalDurationMs))
            }
        }
        Text(
            text = meta,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun YearDetailActions(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSort: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FilledTonalButton(
            onClick = onPlay,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.common_play), maxLines = 1)
        }
        FilledTonalButton(
            onClick = onShuffle,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Rounded.Shuffle, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.common_shuffle), maxLines = 1)
        }
        IconButton(onClick = onSort) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Sort,
                contentDescription = stringResource(R.string.library_sort_by_title)
            )
        }
    }
}
