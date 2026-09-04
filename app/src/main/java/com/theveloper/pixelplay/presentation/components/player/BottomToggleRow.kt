package com.theveloper.pixelplay.presentation.components.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.FavoriteRatingSegment
import com.theveloper.pixelplay.presentation.components.ToggleSegmentButton
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

@Composable
fun BottomToggleRow(
    modifier: Modifier,
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    isFavoriteProvider: () -> Boolean,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onFavoriteToggle: () -> Unit,
    songRatingProvider: () -> Int = { 0 },
    onRatingSelected: (Int) -> Unit = {},
    activeColorMain: Color = MaterialTheme.colorScheme.primary,
    activeColorSecondary: Color = MaterialTheme.colorScheme.secondary,
    activeColorTertiary: Color = MaterialTheme.colorScheme.tertiary,
    onActiveColorMain: Color = MaterialTheme.colorScheme.onPrimary,
    onActiveColorSecondary: Color = MaterialTheme.colorScheme.onSecondary,
    onActiveColorTertiary: Color = MaterialTheme.colorScheme.onTertiary,
    ratingOnlyColor: Color = MaterialTheme.colorScheme.tertiaryContainer,
    ratingOnlyContentColor: Color = MaterialTheme.colorScheme.onTertiaryContainer,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    inactiveContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer
) {
    val isFavorite = isFavoriteProvider()
    val rating = songRatingProvider()
    val rowCorners = 60.dp

    // 五星评分展开态：第三格原地展宽，其余两格收窄
    var ratingExpanded by remember { mutableStateOf(false) }
    val collapseRating: () -> Unit = { ratingExpanded = false }
    val sideWeight by animateFloatAsState(
        targetValue = if (ratingExpanded) 0.7f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sideSegmentWeight"
    )
    val ratingWeight by animateFloatAsState(
        targetValue = if (ratingExpanded) 2.6f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "ratingSegmentWeight"
    )

    Box(
        modifier = modifier.background(
            color = containerColor,
            shape = AbsoluteSmoothCornerShape(
                cornerRadiusBL = rowCorners,
                smoothnessAsPercentTR = 60,
                cornerRadiusBR = rowCorners,
                smoothnessAsPercentBL = 60,
                cornerRadiusTL = rowCorners,
                smoothnessAsPercentBR = 60,
                cornerRadiusTR = rowCorners,
                smoothnessAsPercentTL = 60
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clip(
                    AbsoluteSmoothCornerShape(
                        cornerRadiusBL = rowCorners,
                        smoothnessAsPercentTR = 60,
                        cornerRadiusBR = rowCorners,
                        smoothnessAsPercentBL = 60,
                        cornerRadiusTL = rowCorners,
                        smoothnessAsPercentBR = 60,
                        cornerRadiusTR = rowCorners,
                        smoothnessAsPercentTL = 60
                    )
                )
                .background(Color.Transparent),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToggleSegmentButton(
                modifier = Modifier.weight(sideWeight),
                active = isShuffleEnabled,
                activeColor = activeColorMain,
                activeCornerRadius = rowCorners,
                activeContentColor = onActiveColorMain,
                inactiveColor = inactiveColor,
                inactiveContentColor = inactiveContentColor,
                onClick = { collapseRating(); onShuffleToggle() },
                iconId = R.drawable.rounded_shuffle_24,
                contentDesc = "Shuffle"
            )
            val repeatActive = repeatMode != Player.REPEAT_MODE_OFF
            val repeatIcon = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> R.drawable.rounded_repeat_one_24
                Player.REPEAT_MODE_ALL -> R.drawable.rounded_repeat_24
                else -> R.drawable.rounded_repeat_24
            }
            ToggleSegmentButton(
                modifier = Modifier.weight(sideWeight),
                active = repeatActive,
                activeColor = activeColorSecondary,
                activeCornerRadius = rowCorners,
                activeContentColor = onActiveColorSecondary,
                inactiveColor = inactiveColor,
                inactiveContentColor = inactiveContentColor,
                onClick = { collapseRating(); onRepeatToggle() },
                iconId = repeatIcon,
                contentDesc = "Repeat"
            )
            // 双态格：单击切收藏，长按原地展开五星
            FavoriteRatingSegment(
                modifier = Modifier.weight(ratingWeight),
                isFavorite = isFavorite,
                rating = rating,
                expanded = ratingExpanded,
                rowCorners = rowCorners,
                activeColor = activeColorTertiary,
                activeContentColor = onActiveColorTertiary,
                ratingOnlyColor = ratingOnlyColor,
                ratingOnlyContentColor = ratingOnlyContentColor,
                inactiveColor = inactiveColor,
                inactiveContentColor = inactiveContentColor,
                onToggleFavorite = onFavoriteToggle,
                onExpand = { ratingExpanded = true },
                onCollapse = collapseRating,
                onRatingSelected = onRatingSelected
            )
        }
    }
}
