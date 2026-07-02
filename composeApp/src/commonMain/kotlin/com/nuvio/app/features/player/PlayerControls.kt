package com.nuvio.app.features.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.AppIconResource
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioTypeScale
import com.nuvio.app.core.ui.appIconPainter
import com.nuvio.app.core.ui.nuvioTypeScale
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerControlsShell(
    title: String,
    streamTitle: String,
    providerName: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    metrics: PlayerLayoutMetrics,
    resizeMode: PlayerResizeMode,
    isLocked: Boolean,
    showPlaybackControls: Boolean = true,
    isFullscreen: Boolean = false,
    onLockToggle: () -> Unit,
    onBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onResizeModeClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onAudioClick: () -> Unit,
    onVideoSettingsClick: (() -> Unit)? = null,
    onSourcesClick: (() -> Unit)? = null,
    onEpisodesClick: (() -> Unit)? = null,
    onOpenInExternalPlayer: (() -> Unit)? = null,
    onSubmitIntroClick: (() -> Unit)? = null,
    onFullscreenClick: (() -> Unit)? = null,
    parentalWarnings: List<ParentalWarning> = emptyList(),
    showParentalGuide: Boolean = false,
    onParentalGuideAnimationComplete: () -> Unit = {},
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    horizontalSafePadding: androidx.compose.ui.unit.Dp,
    currentVolume: Float = 50f,
    isVolumeMuted: Boolean = false,
    onVolumeSliderChange: (Float) -> Unit = {},
    onClickVolumeIcon: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val typeScale = MaterialTheme.nuvioTypeScale
    val metadataAlpha by animateFloatAsState(
        targetValue = if (!showParentalGuide && showPlaybackControls) 1f else 0f,
        animationSpec = tween(durationMillis = if (!showParentalGuide && showPlaybackControls) 260 else 160),
        label = "playerMetadataAlpha",
    )

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.6f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.85f),
                        ),
                    ),
                ),
        )

        if (showPlaybackControls) {
            PlayerHeader(
                metrics = metrics,
                isLocked = isLocked,
                isFullscreen = isFullscreen,
                onLockToggle = onLockToggle,
                onVideoSettingsClick = onVideoSettingsClick,
                onFullscreenClick = onFullscreenClick,
                onBack = onBack,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                    .padding(
                        end = metrics.horizontalPadding,
                        top = metrics.verticalPadding / 4,
                    ),
            )
        }

        if (showPlaybackControls) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = horizontalSafePadding)
                    .padding(bottom = metrics.sliderBottomOffset),
            ) {
                PlayerBottomInfo(
                    title = title,
                    streamTitle = streamTitle,
                    providerName = providerName,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    typeScale = typeScale,
                    metrics = metrics,
                    metadataAlpha = metadataAlpha,
                    parentalWarnings = parentalWarnings,
                    showParentalGuide = showParentalGuide,
                    onParentalGuideAnimationComplete = onParentalGuideAnimationComplete,
                    modifier = Modifier.padding(horizontal = metrics.horizontalPadding),
                )

                PlayerBottomControls(
                    playbackSnapshot = playbackSnapshot,
                    displayedPositionMs = displayedPositionMs,
                    metrics = metrics,
                    resizeMode = resizeMode,
                    isPlaying = playbackSnapshot.isPlaying,
                    isBuffering = playbackSnapshot.isLoading,
                    currentVolume = currentVolume,
                    isVolumeMuted = isVolumeMuted,
                    onTogglePlayback = onTogglePlayback,
                    onVolumeSliderChange = onVolumeSliderChange,
                    onClickVolumeIcon = onClickVolumeIcon,
                    onScrubChange = onScrubChange,
                    onScrubFinished = onScrubFinished,
                    onResizeModeClick = onResizeModeClick,
                    onSpeedClick = onSpeedClick,
                    onSubtitleClick = onSubtitleClick,
                    onAudioClick = onAudioClick,
                    onSourcesClick = onSourcesClick,
                    onEpisodesClick = onEpisodesClick,
                    onOpenInExternalPlayer = onOpenInExternalPlayer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = metrics.horizontalPadding),
                )
            }
        }

        if (showPlaybackControls) {
            CenterControls(
                snapshot = playbackSnapshot,
                metrics = metrics,
                onSeekBack = onSeekBack,
                onSeekForward = onSeekForward,
                onTogglePlayback = onTogglePlayback,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = metrics.centerLift),
            )
        }
    }
}

@Composable
private fun PlayerHeader(
    metrics: PlayerLayoutMetrics,
    isLocked: Boolean,
    isFullscreen: Boolean,
    onLockToggle: () -> Unit,
    onVideoSettingsClick: (() -> Unit)?,
    onFullscreenClick: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioBackButton(
            onClick = onBack,
            containerColor = Color.Black.copy(alpha = 0.35f),
            contentColor = Color.White,
            buttonSize = metrics.headerIconSize + 16.dp,
            iconSize = metrics.headerIconSize,
            contentDescription = stringResource(Res.string.compose_player_close),
        )
        PlayerHeaderIconButton(
            icon = if (isLocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
            contentDescription = if (isLocked) {
                stringResource(Res.string.compose_player_unlock_controls)
            } else {
                stringResource(Res.string.compose_player_lock_controls)
            },
            buttonSize = metrics.headerIconSize + 16.dp,
            iconSize = metrics.headerIconSize,
            onClick = onLockToggle,
        )
        if (onVideoSettingsClick != null) {
            PlayerHeaderIconButton(
                icon = Icons.Rounded.Build,
                contentDescription = stringResource(Res.string.player_action_video_settings),
                buttonSize = metrics.headerIconSize + 16.dp,
                iconSize = metrics.headerIconSize,
                onClick = onVideoSettingsClick,
            )
        }
        if (onFullscreenClick != null) {
            PlayerHeaderIconButton(
                icon = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                contentDescription = if (isFullscreen) {
                    stringResource(Res.string.action_exit_fullscreen)
                } else {
                    stringResource(Res.string.action_fullscreen)
                },
                buttonSize = metrics.headerIconSize + 16.dp,
                iconSize = metrics.headerIconSize,
                onClick = onFullscreenClick,
            )
        }
    }
}

@Composable
private fun PlayerHeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    buttonSize: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    customTint: Color = Color.White,
) {
    Box(
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = customTint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun PlayerBottomInfo(
    title: String,
    streamTitle: String,
    providerName: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    typeScale: NuvioTypeScale,
    metrics: PlayerLayoutMetrics,
    metadataAlpha: Float,
    parentalWarnings: List<ParentalWarning>,
    showParentalGuide: Boolean,
    onParentalGuideAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.graphicsLayer { alpha = metadataAlpha },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = typeScale.titleLg.copy(
                fontSize = metrics.titleSize,
                lineHeight = metrics.titleSize * 1.16f,
                fontWeight = FontWeight.Bold,
            ),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (seasonNumber != null && episodeNumber != null && !episodeTitle.isNullOrBlank()) {
            Text(
                text = stringResource(
                    Res.string.compose_player_episode_title_format,
                    seasonNumber,
                    episodeNumber,
                    episodeTitle,
                ),
                style = typeScale.bodyMd.copy(
                    fontSize = metrics.episodeInfoSize,
                    lineHeight = metrics.episodeInfoSize * 1.3f,
                ),
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = streamTitle,
                style = typeScale.labelSm.copy(
                    fontSize = metrics.metadataSize,
                    lineHeight = metrics.metadataSize * 1.25f,
                ),
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = providerName,
                style = typeScale.labelSm.copy(
                    fontSize = metrics.metadataSize,
                    lineHeight = metrics.metadataSize * 1.25f,
                    fontStyle = FontStyle.Italic,
                ),
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ParentalGuideOverlay(
            warnings = parentalWarnings,
            isVisible = showParentalGuide,
            onAnimationComplete = onParentalGuideAnimationComplete,
            contentPadding = PaddingValues(0.dp),
        )
    }
}

@Composable
private fun PlayerBottomControls(
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    metrics: PlayerLayoutMetrics,
    resizeMode: PlayerResizeMode,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentVolume: Float,
    isVolumeMuted: Boolean,
    onTogglePlayback: () -> Unit,
    onVolumeSliderChange: (Float) -> Unit,
    onClickVolumeIcon: () -> Unit,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    onResizeModeClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onAudioClick: () -> Unit,
    onSourcesClick: (() -> Unit)? = null,
    onEpisodesClick: (() -> Unit)? = null,
    onOpenInExternalPlayer: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val durationMs = playbackSnapshot.durationMs.coerceAtLeast(1L)

    Column(modifier = modifier) {
        Slider(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .graphicsLayer { scaleY = 0.6f },
            value = displayedPositionMs.coerceIn(0L, durationMs).toFloat(),
            onValueChange = { value -> onScrubChange(value.toLong()) },
            onValueChangeFinished = { onScrubFinished(displayedPositionMs.coerceIn(0L, durationMs)) },
            valueRange = 0f..durationMs.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFF2F6FED),
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BluePlayButton(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                onClick = onTogglePlayback,
            )

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomBarIconButton(
                    painter = appIconPainter(AppIconResource.PlayerAspectRatio),
                    contentDescription = stringResource(resizeMode.labelRes),
                    onClick = onResizeModeClick,
                )
                BottomBarIconButton(
                    icon = Icons.Rounded.Speed,
                    contentDescription = formatPlaybackSpeedLabel(playbackSnapshot.playbackSpeed),
                    onClick = onSpeedClick,
                )
                BottomBarIconButton(
                    painter = appIconPainter(AppIconResource.PlayerSubtitles),
                    contentDescription = stringResource(Res.string.compose_player_subs),
                    onClick = onSubtitleClick,
                )
                BottomBarIconButton(
                    painter = appIconPainter(AppIconResource.PlayerAudioFilled),
                    contentDescription = stringResource(Res.string.compose_player_audio),
                    onClick = onAudioClick,
                )
                if (onSourcesClick != null) {
                    BottomBarIconButton(
                        painter = appIconPainter(AppIconResource.PlayerAspectRatio),
                        contentDescription = stringResource(Res.string.compose_player_sources),
                        onClick = onSourcesClick,
                    )
                }
                if (onEpisodesClick != null) {
                    BottomBarIconButton(
                        painter = appIconPainter(AppIconResource.PlayerAspectRatio),
                        contentDescription = stringResource(Res.string.compose_player_episodes),
                        onClick = onEpisodesClick,
                    )
                }
                if (onOpenInExternalPlayer != null) {
                    BottomBarIconButton(
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(Res.string.streams_open_external_player),
                        onClick = onOpenInExternalPlayer,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (isVolumeMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = stringResource(Res.string.compose_player_volume),
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClickVolumeIcon)
                        .padding(2.dp),
                )
                Slider(
                    value = currentVolume,
                    onValueChange = onVolumeSliderChange,
                    valueRange = 0f..100f,
                    modifier = Modifier.width(100.dp).height(20.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF2F6FED),
                        activeTrackColor = Color(0xFF2F6FED),
                        inactiveTrackColor = Color.White.copy(alpha = 0.26f),
                    ),
                )
                TimeLabel(
                    positionMs = displayedPositionMs,
                    durationMs = durationMs,
                    fontSize = metrics.timeSize,
                )
            }
        }
    }
}

@Composable
private fun BluePlayButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
) {
    val playPausePainter = appIconPainter(
        if (isPlaying) AppIconResource.PlayerPause else AppIconResource.PlayerPlay,
    )

    Box(
        modifier = Modifier
            .size(42.dp)
            .background(Color(0xFF2F6FED), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isBuffering) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Icon(
                painter = playPausePainter,
                contentDescription = if (isPlaying) {
                    stringResource(Res.string.compose_action_pause)
                } else {
                    stringResource(Res.string.detail_btn_play)
                },
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun BottomBarIconButton(
    icon: ImageVector? = null,
    painter: Painter? = null,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            painter != null -> Icon(
                painter = painter,
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(18.dp),
            )
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TimeLabel(
    positionMs: Long,
    durationMs: Long,
    fontSize: androidx.compose.ui.unit.TextUnit,
) {
    Text(
        text = "${formatPlaybackTime(positionMs)} / ${formatPlaybackTime(durationMs)}",
        style = MaterialTheme.nuvioTypeScale.labelSm.copy(
            fontSize = fontSize,
            lineHeight = fontSize * 1.25f,
            fontWeight = FontWeight.Medium,
        ),
        color = Color.White.copy(alpha = 0.85f),
        maxLines = 1,
    )
}

@Composable
private fun CenterControls(
    snapshot: PlayerPlaybackSnapshot,
    metrics: PlayerLayoutMetrics,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(metrics.centerGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SideControlButton(
            icon = Icons.Rounded.Replay10,
            contentDescription = stringResource(Res.string.compose_player_seek_back_10),
            metrics = metrics,
            onClick = onSeekBack,
        )
        PlayPauseControlButton(
            isPlaying = snapshot.isPlaying,
            isBuffering = snapshot.isLoading,
            metrics = metrics,
            onClick = onTogglePlayback,
        )
        SideControlButton(
            icon = Icons.Rounded.Forward10,
            contentDescription = stringResource(Res.string.compose_player_seek_forward_10),
            metrics = metrics,
            onClick = onSeekForward,
        )
    }
}

@Composable
private fun SideControlButton(
    icon: ImageVector,
    contentDescription: String,
    metrics: PlayerLayoutMetrics,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(metrics.sideButtonPadding),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(metrics.playIconSize),
        )
    }
}

@Composable
private fun PlayPauseControlButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    metrics: PlayerLayoutMetrics,
    onClick: () -> Unit,
) {
    val playPausePainter = appIconPainter(
        if (isPlaying) AppIconResource.PlayerPause else AppIconResource.PlayerPlay,
    )

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(metrics.playButtonPadding),
        contentAlignment = Alignment.Center,
    ) {
        if (isBuffering) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(metrics.playIconSize),
            )
        } else {
            Icon(
                painter = playPausePainter,
                contentDescription = if (isPlaying) {
                    stringResource(Res.string.compose_action_pause)
                } else {
                    stringResource(Res.string.detail_btn_play)
                },
                tint = Color.White,
                modifier = Modifier.size(metrics.playIconSize),
            )
        }
    }
}

@Composable
internal fun LockedPlayerOverlay(
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    metrics: PlayerLayoutMetrics,
    horizontalSafePadding: androidx.compose.ui.unit.Dp,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationMs = playbackSnapshot.durationMs.coerceAtLeast(1L)
    val sliderColors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.28f),
        disabledThumbColor = Color.White,
        disabledActiveTrackColor = Color.White,
        disabledInactiveTrackColor = Color.White.copy(alpha = 0.28f),
    )

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.52f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                    .clickable(onClick = onUnlock),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = stringResource(Res.string.compose_player_unlock_controls),
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.compose_player_tap_to_unlock),
                style = MaterialTheme.nuvioTypeScale.bodyMd.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.92f),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = horizontalSafePadding + metrics.horizontalPadding)
                .padding(bottom = metrics.sliderBottomOffset),
        ) {
            Slider(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.sliderTouchHeight)
                    .graphicsLayer(scaleY = metrics.sliderScaleY),
                value = displayedPositionMs.coerceIn(0L, durationMs).toFloat(),
                onValueChange = {},
                onValueChangeFinished = {},
                valueRange = 0f..durationMs.toFloat(),
                enabled = false,
                colors = sliderColors,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TimeLabel(
                    positionMs = displayedPositionMs,
                    durationMs = durationMs,
                    fontSize = metrics.timeSize,
                )
            }
        }
    }
}
