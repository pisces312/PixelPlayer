package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.LocalMaterialTheme
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun ToggleSegmentButton(
    modifier: Modifier,
    active: Boolean,
    enabled: Boolean = true,
    activeColor: Color,
    inactiveColor: Color = Color.Gray,
    activeContentColor: Color = LocalMaterialTheme.current.onPrimary,
    inactiveContentColor: Color = LocalMaterialTheme.current.onSurfaceVariant,
    activeCornerRadius: Dp = 8.dp,
    onClick: () -> Unit,
    iconId: Int,
    contentDesc: String
) {
    ToggleSegmentButtonContainer(
        modifier = modifier,
        active = active,
        enabled = enabled,
        activeColor = activeColor,
        inactiveColor = inactiveColor,
        activeCornerRadius = activeCornerRadius,
        onClick = onClick
    ) {
        Icon(
            painter = painterResource(iconId),
            contentDescription = contentDesc,
            tint = if (active) activeContentColor else inactiveContentColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun ToggleSegmentButton(
    modifier: Modifier,
    active: Boolean,
    enabled: Boolean = true,
    activeColor: Color,
    inactiveColor: Color = Color.Gray,
    activeContentColor: Color = LocalMaterialTheme.current.onPrimary,
    inactiveContentColor: Color = LocalMaterialTheme.current.onSurfaceVariant,
    activeCornerRadius: Dp = 8.dp,
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDesc: String
) {
    ToggleSegmentButtonContainer(
        modifier = modifier,
        active = active,
        enabled = enabled,
        activeColor = activeColor,
        inactiveColor = inactiveColor,
        activeCornerRadius = activeCornerRadius,
        onClick = onClick
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDesc,
            tint = if (active) activeContentColor else inactiveContentColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun ToggleSegmentButton(
    modifier: Modifier,
    active: Boolean,
    enabled: Boolean = true,
    activeColor: Color,
    inactiveColor: Color = Color.Gray,
    activeContentColor: Color = LocalMaterialTheme.current.onPrimary,
    inactiveContentColor: Color = LocalMaterialTheme.current.primary,
    activeCornerRadius: Dp = 8.dp,
    onClick: () -> Unit,
    text: String,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Center
) {
    ToggleSegmentButtonContainer(
        modifier = modifier,
        active = active,
        enabled = enabled,
        activeColor = activeColor,
        inactiveColor = inactiveColor,
        activeCornerRadius = activeCornerRadius,
        onClick = onClick
    ) {
        Text(
            text = text,
            color = if (active) activeContentColor else inactiveContentColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = maxLines,
            overflow = overflow,
            textAlign = textAlign
        )
    }
}

@Composable
fun ToggleSegmentButton(
    modifier: Modifier,
    active: Boolean,
    enabled: Boolean = true,
    activeColor: Color,
    inactiveColor: Color = Color.Gray,
    activeContentColor: Color = LocalMaterialTheme.current.onPrimary,
    inactiveContentColor: Color = LocalMaterialTheme.current.onSurfaceVariant,
    activeCornerRadius: Dp = 8.dp,
    onClick: () -> Unit,
    text: String,
    imageVector: ImageVector,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Center
) {
    ToggleSegmentButtonContainer(
        modifier = modifier,
        active = active,
        enabled = enabled,
        activeColor = activeColor,
        inactiveColor = inactiveColor,
        activeCornerRadius = activeCornerRadius,
        onClick = onClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = if (active) activeContentColor else inactiveContentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                color = if (active) activeContentColor else inactiveContentColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = maxLines,
                overflow = overflow,
                textAlign = textAlign
            )
        }
    }
}


@Composable
private fun ToggleSegmentButtonContainer(
    modifier: Modifier,
    active: Boolean,
    enabled: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    activeCornerRadius: Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val targetBgColor = if (active) activeColor else inactiveColor
    val bgColor by animateColorAsState(
        targetValue = if (enabled) targetBgColor else targetBgColor.copy(alpha = 0.5f),
        animationSpec = tween(durationMillis = 250),
        label = ""
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (active) activeCornerRadius else 8.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = ""
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(cornerRadius))
            .background(bgColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer(alpha = if (enabled) 1f else 0.38f)
                .padding(horizontal = 10.dp)
        ) {
            content()
        }
    }
}

/**
 * 收藏 + 五星评分双态分段格（详见 docs/five-star-rating-feature-plan.md §3.1）。
 * 收起态：单击切换收藏，长按原地展开五星条；
 * 展开态：点星设置评分（点当前星级清除评分）并收起，点格内空白收起。
 */
@Composable
fun FavoriteRatingSegment(
    modifier: Modifier,
    isFavorite: Boolean,
    rating: Int,
    expanded: Boolean,
    rowCorners: Dp,
    activeColor: Color,
    activeContentColor: Color,
    ratingOnlyColor: Color,
    ratingOnlyContentColor: Color,
    inactiveColor: Color,
    inactiveContentColor: Color,
    onToggleFavorite: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onRatingSelected: (Int) -> Unit
) {
    val haptics = LocalHapticFeedback.current

    val active = isFavorite || rating > 0 || expanded
    val targetBgColor = when {
        expanded || isFavorite -> activeColor
        rating > 0 -> ratingOnlyColor
        else -> inactiveColor
    }
    val currentActiveContentColor =
        if (isFavorite || expanded) activeContentColor else ratingOnlyContentColor
    val bgColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = tween(durationMillis = 250),
        label = "ratingSegmentBg"
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (active) rowCorners else 8.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "ratingSegmentCorner"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(cornerRadius))
            .background(bgColor)
            .combinedClickable(
                onClick = {
                    if (expanded) onCollapse() else onToggleFavorite()
                },
                onLongClick = {
                    if (!expanded) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onExpand()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (star in 1..5) {
                    val selected = star <= rating
                    Icon(
                        imageVector = if (selected) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = "Calificar con $star estrellas",
                        tint = activeContentColor.copy(alpha = if (selected) 1f else 0.45f),
                        modifier = Modifier
                            .size(26.dp)
                            .clickable {
                                haptics.performHapticFeedback(
                                    if (star == rating) HapticFeedbackType.ToggleOff
                                    else HapticFeedbackType.ToggleOn
                                )
                                onRatingSelected(if (star == rating) 0 else star)
                                onCollapse()
                            }
                    )
                }
            }
        } else {
            val contentTint = if (active) currentActiveContentColor else inactiveContentColor
            val contentDesc = buildString {
                append(if (isFavorite) "Favorito" else "Marcar como favorito")
                if (rating > 0) append(", calificado con $rating estrellas")
                append(", mantén pulsado para calificar")
            }
            Box(contentAlignment = Alignment.Center) {
                if (isFavorite || rating == 0) {
                    Icon(
                        painter = painterResource(
                            if (isFavorite) R.drawable.round_favorite_24
                            else R.drawable.rounded_favorite_24
                        ),
                        contentDescription = contentDesc,
                        tint = contentTint,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    // 仅评分、未收藏
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = contentDesc,
                        tint = contentTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
                if (isFavorite && rating > 0) {
                    Text(
                        text = rating.toString(),
                        color = contentTint,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .graphicsLayer { translationX = 6.dp.toPx(); translationY = 3.dp.toPx() }
                    )
                }
            }
        }
    }
}
