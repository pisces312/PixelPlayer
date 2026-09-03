package com.theveloper.pixelplay.presentation.screens.import

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.importer.ImportProgress
import com.theveloper.pixelplay.presentation.viewmodel.ImportViewModel

/**
 * Poweramp 导入向导对话框组（poweramp-import-feature-plan §5）：
 * 解析中 → 预览（必看）→ 选项 → 执行（可取消）→ 结果报告。
 */
@Composable
fun PowerampImportFlow(
    viewModel: ImportViewModel,
    onFinished: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when (state.step) {
        ImportViewModel.Step.PARSING -> ParsingDialog()
        ImportViewModel.Step.PREVIEW -> PreviewDialog(
            state = state,
            favoritesEstimate = viewModel.favoritesCountForThreshold(state.options.favoriteRatingThreshold),
            engagementImportCount = viewModel.engagementImportCount(),
            onNext = { viewModel.confirmPreview() },
            onCancel = onFinished
        )
        ImportViewModel.Step.OPTIONS -> OptionsDialog(
            state = state,
            favoritesCountForThreshold = { viewModel.favoritesCountForThreshold(it) },
            onOptionsChanged = { viewModel.updateOptions(it) },
            onStart = { viewModel.startImport() },
            onCancel = onFinished
        )
        ImportViewModel.Step.IMPORTING -> ImportingDialog(
            progress = state.progress,
            onCancel = { viewModel.cancelImport() }
        )
        ImportViewModel.Step.RESULT -> ResultDialog(
            state = state,
            onDone = onFinished
        )
        ImportViewModel.Step.ERROR -> ErrorDialog(
            message = state.errorMessage.orEmpty(),
            onDismiss = onFinished
        )
        ImportViewModel.Step.IDLE -> Unit
    }
}

@Composable
private fun ParsingDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.import_parsing_title)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun PreviewDialog(
    state: ImportViewModel.UiState,
    favoritesEstimate: Int,
    engagementImportCount: Int,
    onNext: () -> Unit,
    onCancel: () -> Unit
) {
    val preview = state.prepared?.preview ?: return
    val impact = state.impact

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.import_preview_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 解析结果
                StatRow(stringResource(R.string.import_preview_playlists), preview.playlistCount)
                StatRow(stringResource(R.string.import_preview_songs), preview.songCount)
                StatRow(stringResource(R.string.import_preview_rated), preview.ratedCount)
                StatRow(stringResource(R.string.import_preview_played), preview.playedCount)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.import_preview_rating_distribution,
                        preview.ratingDistribution[5] ?: 0,
                        preview.ratingDistribution[4] ?: 0,
                        preview.ratingDistribution[3] ?: 0,
                        preview.ratingDistribution[2] ?: 0,
                        preview.ratingDistribution[1] ?: 0
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.import_preview_matched,
                        preview.matchedEstimate,
                        (preview.matchRate * 100).toInt()
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                // 导入规模冲击对比（D11：数量级跳变必须在执行前可见）
                if (impact != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.import_preview_impact_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.import_preview_impact_engagement,
                            impact.currentEngagement,
                            impact.currentEngagement + engagementImportCount
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(
                            R.string.import_preview_impact_favorites,
                            impact.currentFavorites,
                            impact.currentFavorites + favoritesEstimate
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(
                            R.string.import_preview_impact_playlists,
                            impact.currentPlaylists,
                            impact.currentPlaylists + preview.playlistCount
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNext) { Text(stringResource(R.string.import_action_next)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

@Composable
private fun OptionsDialog(
    state: ImportViewModel.UiState,
    favoritesCountForThreshold: (Int) -> Int,
    onOptionsChanged: (com.theveloper.pixelplay.data.importer.ImportOptions) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    val options = state.options

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.import_options_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 模式
                Text(
                    text = stringResource(R.string.import_options_mode_label),
                    style = MaterialTheme.typography.labelLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !options.replaceMode,
                        onClick = { onOptionsChanged(options.copy(replaceMode = false)) },
                        label = { Text(stringResource(R.string.import_options_mode_merge)) }
                    )
                    FilterChip(
                        selected = options.replaceMode,
                        onClick = { onOptionsChanged(options.copy(replaceMode = true)) },
                        label = { Text(stringResource(R.string.import_options_mode_replace)) }
                    )
                }
                Text(
                    text = stringResource(R.string.import_options_mode_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                // 评分阈值（实时显示将新增的收藏数，F10）
                Text(
                    text = stringResource(
                        R.string.import_options_threshold_label,
                        options.favoriteRatingThreshold
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 4, 5).forEach { threshold ->
                        FilterChip(
                            selected = options.favoriteRatingThreshold == threshold,
                            onClick = {
                                onOptionsChanged(options.copy(favoriteRatingThreshold = threshold))
                            },
                            label = { Text("$threshold★") }
                        )
                    }
                }
                Text(
                    text = stringResource(
                        R.string.import_options_threshold_count,
                        favoritesCountForThreshold(options.favoriteRatingThreshold)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(12.dp))

                // 导入内容开关
                OptionSwitch(
                    label = stringResource(R.string.import_options_playlists),
                    checked = options.importPlaylists,
                    onCheckedChange = { onOptionsChanged(options.copy(importPlaylists = it)) }
                )
                OptionSwitch(
                    label = stringResource(R.string.import_options_history),
                    checked = options.importHistory,
                    onCheckedChange = { onOptionsChanged(options.copy(importHistory = it)) }
                )
                OptionSwitch(
                    label = stringResource(R.string.import_options_engagement),
                    checked = options.importEngagement,
                    onCheckedChange = { onOptionsChanged(options.copy(importEngagement = it)) }
                )
                OptionSwitch(
                    label = stringResource(R.string.import_options_favorites),
                    checked = options.importFavorites,
                    onCheckedChange = { onOptionsChanged(options.copy(importFavorites = it)) }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.import_options_merge_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onStart) { Text(stringResource(R.string.import_action_start)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

@Composable
private fun OptionSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ImportingDialog(progress: ImportProgress?, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.import_importing_title)) },
        text = {
            Column {
                Text(
                    text = progress?.let { stepLabel(it.step) }
                        ?: stringResource(R.string.import_step_parsing),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                if (progress != null && progress.total > 1) {
                    LinearProgressIndicator(
                        progress = { progress.current.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

@Composable
private fun stepLabel(step: ImportProgress.Step): String = stringResource(
    when (step) {
        ImportProgress.Step.PARSING -> R.string.import_step_parsing
        ImportProgress.Step.MATCHING -> R.string.import_step_matching
        ImportProgress.Step.PLAYLISTS -> R.string.import_step_playlists
        ImportProgress.Step.HISTORY -> R.string.import_step_history
        ImportProgress.Step.ENGAGEMENT -> R.string.import_step_engagement
        ImportProgress.Step.FAVORITES -> R.string.import_step_favorites
    }
)

@Composable
private fun ResultDialog(state: ImportViewModel.UiState, onDone: () -> Unit) {
    val result = state.result ?: return
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.import_result_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.import_result_matched, result.matchedSongs))
                if (result.unresolvedSongs > 0) {
                    Text(
                        text = stringResource(R.string.import_result_unresolved, result.unresolvedSongs),
                        color = MaterialTheme.colorScheme.error
                    )
                    if (result.unresolvedExamples.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.import_result_unresolved_examples,
                                result.unresolvedExamples.joinToString("、")
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.import_result_playlists, result.playlistsCreated, result.playlistsMerged))
                Text(stringResource(R.string.import_result_history, result.historyEventsImported))
                Text(stringResource(R.string.import_result_engagement, result.engagementImported))
                Text(stringResource(R.string.import_result_favorites, result.favoritesImported, result.ratingsSaved))
                if (result.skippedEmptyPlaylists > 0) {
                    Text(stringResource(R.string.import_result_skipped_empty, result.skippedEmptyPlaylists))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDone) { Text(stringResource(R.string.import_action_done)) }
        }
    )
}

@Composable
private fun ErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_error_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        }
    )
}

@Composable
private fun StatRow(label: String, value: Int) {
    Row(Modifier.fillMaxWidth()) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(text = "$value", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
