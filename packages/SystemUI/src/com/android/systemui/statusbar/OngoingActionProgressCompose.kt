/*
 * Copyright (C) 2025-2026 VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.HotspotController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.statusbar.policy.CaffeineController
import com.android.systemui.statusbar.policy.NotificationSuppressController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "OngoingActionProgressCompose"

@Composable
fun ProgressRing(progressProvider: () -> Int, maxProgressProvider: () -> Int, statusColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val progressValue = if (maxProgressProvider() > 0) {
            (progressProvider().toFloat() / maxProgressProvider().toFloat()).coerceIn(0f, 1f)
        } else 0f
        val strokeWidthPx = 2.dp.toPx()
        val diameter = size.minDimension - strokeWidthPx
        val radius = diameter / 2
        val topLeftOffset = center - Offset(radius, radius)
        val arcSize = Size(diameter, diameter)

        drawArc(
            color = statusColor.copy(alpha = 0.2f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeftOffset,
            size = arcSize,
            style = Stroke(width = strokeWidthPx),
        )

        drawArc(
            color = statusColor,
            startAngle = -90f,
            sweepAngle = 360f * progressValue,
            useCenter = false,
            topLeft = topLeftOffset,
            size = arcSize,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun ProgressBar(progressProvider: () -> Int, maxProgressProvider: () -> Int, statusColor: Color, modifier: Modifier = Modifier) {
    val progressValue = if (maxProgressProvider() > 0) {
        (progressProvider().toFloat() / maxProgressProvider().toFloat()).coerceIn(0f, 1f)
    } else 0f

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(1.dp))
            .background(statusColor.copy(alpha = 0.18f)),
    ) {
        if (progressValue > 0f) {
            val minFraction = 4f / 96f
            val clampedFraction = progressValue.coerceAtLeast(minFraction)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(clampedFraction)
                    .clip(RoundedCornerShape(1.dp))
                    .background(statusColor.copy(alpha = 0.9f)),
            )
        }
    }
}

@Composable
fun OngoingActionProgress(
    controller: OnGoingActionProgressComposeController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()

    val rawAccentColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val surfaceColor = MaterialTheme.colorScheme.surface

    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(tween(120, easing = FastOutSlowInEasing)) + scaleIn(initialScale = 0.8f, animationSpec = tween(120, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(120, easing = FastOutSlowInEasing)) + scaleOut(targetScale = 0.8f, animationSpec = tween(120, easing = FastOutSlowInEasing)),
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            val statusColor = Color(state.iconTint)
            val dimmedStatusColor = statusColor.copy(alpha = 0.75f)

            var isPressed by remember { mutableStateOf(false) }
            var displayedIcon by remember { mutableStateOf(state.icon) }
            var lastIconChangeTime by remember { mutableLongStateOf(0L) }

            LaunchedEffect(state.icon) {
                if (state.icon !== displayedIcon) {
                    val now = System.currentTimeMillis()
                    val timeSinceLast = now - lastIconChangeTime
                    if (timeSinceLast < 400) {
                        delay(400 - timeSinceLast)
                    }
                    displayedIcon = state.icon
                    lastIconChangeTime = System.currentTimeMillis()
                }
            }

            val scale by animateFloatAsState(
                targetValue = if (isPressed) 0.9f else 1f,
                animationSpec = tween(150, easing = FastOutSlowInEasing),
                label = "ScaleAnimation"
            )

            var dragOffset = 0f
            val baseModifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragOffset = 0f },
                        onDragEnd = {
                            if (dragOffset < -50) controller.onSwipe(true)
                            else if (dragOffset > 50) controller.onSwipe(false)
                        },
                    ) { _, dragAmount -> dragOffset += dragAmount }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onDoubleTap = { controller.onDoubleTap() },
                        onLongPress = { controller.onLongPress() },
                        onTap = {
                            controller.onInteraction()
                        },
                    )
                }

            val isExpandedTransient = state.activeStateType == OnGoingActionProgressController.TYPE_TRANSIENT && !state.isCompactMode

            if (isExpandedTransient) {
               Row(
                    modifier = baseModifier
                        .alpha(state.opacity)
                        .width(96.dp)
                        .height(30.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (displayedIcon != null) {
                        Image(
                            bitmap = displayedIcon!!,
                            contentDescription = "App icon",
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            colorFilter = null,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    ProgressBar(
                        progressProvider = { state.progress },
                        maxProgressProvider = { state.maxProgress },
                        statusColor = statusColor,
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                    )
                }
            } else {
                AnimatedContent(
                    targetState = state.activeStateType,
                    transitionSpec = {
                        (
                            (fadeIn(tween(250, easing = FastOutSlowInEasing)) + 
                             scaleIn(initialScale = 0.4f, animationSpec = tween(250, easing = FastOutSlowInEasing)) + 
                                 slideInVertically(animationSpec = tween(250, easing = FastOutSlowInEasing), initialOffsetY = { it / 2 })) togetherWith 
                            (fadeOut(tween(150, easing = FastOutSlowInEasing)) + 
                             scaleOut(targetScale = 0.6f, animationSpec = tween(150, easing = FastOutSlowInEasing)) + 
                                 slideOutVertically(animationSpec = tween(150, easing = FastOutSlowInEasing), targetOffsetY = { -it / 2 }))
                        )
                    },
                    label = "IndicatorTypeCrossfade"
                ) { currentType ->
                    val circleModifier = baseModifier
                        .size(30.dp)
                        .alpha(state.opacity)
                        .clip(RoundedCornerShape(15.dp))

                    if (currentType == OnGoingActionProgressController.TYPE_DONE_CHECKMARK) {
                        val checkmarkPath = remember { Path() }
                        Box(
                            modifier = circleModifier,
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(modifier = Modifier.size(12.dp)) {
                                checkmarkPath.reset()
                                checkmarkPath.moveTo(size.width * 0.15f, size.height * 0.5f)
                                checkmarkPath.lineTo(size.width * 0.4f, size.height * 0.75f)
                                checkmarkPath.lineTo(size.width * 0.85f, size.height * 0.25f)
                                drawPath(checkmarkPath, color = statusColor, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                    } else if (currentType == OnGoingActionProgressController.TYPE_LOGO) {
                        val iconTint = when {
                            state.batteryLevel <= 15 -> errorColor.copy(alpha = 0.8f)
                            else -> statusColor
                        }

                        var hasSparked by remember { mutableStateOf(false) }
                        val sparkAlpha = remember { Animatable(0f) }
                        val sparkScale = remember { Animatable(1f) }

                        LaunchedEffect(Unit) {
                            if (!hasSparked) {
                                delay(800)
                                launch { sparkScale.animateTo(1.2f, tween(50)) }
                                sparkAlpha.animateTo(1f, tween(50))
                                sparkAlpha.animateTo(0f, tween(50))
                                launch { sparkScale.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
                                sparkAlpha.animateTo(1f, tween(40))
                                sparkAlpha.animateTo(0f, tween(400))
                                hasSparked = true
                            }
                        }

                        Box(
                            modifier = circleModifier,
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = com.android.systemui.res.R.drawable.ic_voltage_logo),
                                contentDescription = "PowerHub",
                                tint = iconTint,
                                modifier = Modifier.size(20.dp)
                            )
                            if (sparkAlpha.value > 0f) {
                                Icon(
                                    painter = painterResource(id = com.android.systemui.res.R.drawable.ic_voltage_logo),
                                    contentDescription = null,
                                    tint = rawAccentColor.copy(alpha = sparkAlpha.value),
                                    modifier = Modifier
                                        .size(20.dp)
                                        .graphicsLayer {
                                            scaleX = sparkScale.value
                                            scaleY = sparkScale.value
                                        }
                                )
                            }
                        }
                    } else if (currentType != OnGoingActionProgressController.TYPE_TRANSIENT) {
                        val iconRes = when (currentType) {
                            OnGoingActionProgressController.TYPE_FLASHLIGHT -> com.android.systemui.res.R.drawable.ic_ongoing_flashlight
                            OnGoingActionProgressController.TYPE_HOTSPOT -> com.android.systemui.res.R.drawable.ic_ongoing_hotspot
                            OnGoingActionProgressController.TYPE_DND -> com.android.systemui.res.R.drawable.ic_qs_dnd
                            OnGoingActionProgressController.TYPE_SAVER -> com.android.systemui.res.R.drawable.ic_battery_saver_mode
                            OnGoingActionProgressController.TYPE_NIRVANA -> com.android.systemui.res.R.drawable.ic_qs_nirvana
                            OnGoingActionProgressController.TYPE_ALARM -> com.android.systemui.res.R.drawable.ic_ongoing_alarm
                            OnGoingActionProgressController.TYPE_STUCK_NOTIF -> com.android.systemui.res.R.drawable.ic_ongoing_stuck
                            OnGoingActionProgressController.TYPE_SILENT -> com.android.systemui.res.R.drawable.ic_volume_ringer_mute
                            OnGoingActionProgressController.TYPE_CAFFEINE -> com.android.systemui.res.R.drawable.ic_qs_caffeine
                            OnGoingActionProgressController.TYPE_NOTIF_SUPPRESS -> com.android.systemui.res.R.drawable.ic_qs_notification_suppress
                            OnGoingActionProgressController.TYPE_FIVEG -> com.android.settingslib.R.drawable.ic_5g_mobiledata
                            else -> 0
                        }

                        val currentIconTint = when (currentType) {
                            OnGoingActionProgressController.TYPE_FLASHLIGHT,
                            OnGoingActionProgressController.TYPE_HOTSPOT -> rawAccentColor
                            OnGoingActionProgressController.TYPE_SAVER,
                            OnGoingActionProgressController.TYPE_NIRVANA -> dimmedStatusColor
                            else -> statusColor
                        }

                        if (iconRes != 0) {
                            Box(
                                modifier = circleModifier,
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(id = iconRes),
                                    contentDescription = "Active State",
                                    tint = currentIconTint,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    } else if (state.isCompactMode) {
                        Box(
                            modifier = circleModifier,
                            contentAlignment = Alignment.Center,
                        ) {
                            ProgressRing(
                                progressProvider = { state.progress },
                                maxProgressProvider = { state.maxProgress },
                                statusColor = statusColor,
                                modifier = Modifier.size(26.dp) // Sized down from fillMaxSize (30dp)
                            )

                            AnimatedContent(
                                targetState = displayedIcon != null,
                                transitionSpec = {
                                    (fadeIn(tween(200, easing = FastOutSlowInEasing)) + scaleIn(initialScale = 0.8f, animationSpec = tween(200, easing = FastOutSlowInEasing))) togetherWith
                                    (fadeOut(tween(150, easing = FastOutSlowInEasing)) + scaleOut(targetScale = 0.8f, animationSpec = tween(150, easing = FastOutSlowInEasing)))
                                },
                                label = "CompactAppIconPresence"
                            ) { hasIcon ->
                                if (hasIcon && displayedIcon != null) {
                                    Image(
                                        bitmap = displayedIcon!!,
                                        contentDescription = "App icon",
                                        modifier = Modifier.size(16.dp).clip(RoundedCornerShape(8.dp)),
                                        colorFilter = null,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state.showMediaControls && state.activeStateType == OnGoingActionProgressController.TYPE_TRANSIENT) {
                Popup(
                    alignment = Alignment.BottomCenter,
                    onDismissRequest = { controller.onMediaMenuDismiss() },
                ) {
                    Row(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .width(140.dp)
                            .height(48.dp)
                            .shadow(8.dp, RoundedCornerShape(24.dp))
                            .background(surfaceColor, RoundedCornerShape(24.dp))
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MediaControlButton(
                            iconRes = R.drawable.ic_media_control_skip_previous,
                            contentDescription = "Previous",
                            onClick = { controller.onMediaAction(0) }
                        )

                        MediaControlButton(
                            iconRes = R.drawable.ic_media_control_pause,
                            contentDescription = "Pause",
                            onClick = { controller.onMediaAction(1) }
                        )

                        MediaControlButton(
                            iconRes = R.drawable.ic_media_control_skip_next,
                            contentDescription = "Next",
                            onClick = { controller.onMediaAction(2) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaControlButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
        )
    }
}

data class ProgressState(
    val isVisible: Boolean = false,
    val progress: Int = 0,
    val maxProgress: Int = 100,
    val icon: ImageBitmap? = null,
    val packageName: String? = null,
    val isIconAdaptive: Boolean = false,
    val isCompactMode: Boolean = false,
    val opacity: Float = 1f,
    val showMediaControls: Boolean = false,
    val activeStateType: Int = 0,
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val isPowerSave: Boolean = false,
    val iconTint: Int = android.graphics.Color.WHITE,
)

class OnGoingActionProgressComposeController(
    context: Context,
    notificationListener: NotificationListener,
    keyguardStateController: KeyguardStateController,
    headsUpManager: HeadsUpManager,
    flashlightController: FlashlightController?,
    hotspotController: HotspotController?,
    zenModeController: ZenModeController?,
    batteryController: BatteryController?,
    nextAlarmController: NextAlarmController?,
    broadcastDispatcher: BroadcastDispatcher,
    caffeineController: CaffeineController?,
    notifSuppressController: NotificationSuppressController?
) {
    private val _state = MutableStateFlow(ProgressState())
    val state: StateFlow<ProgressState> = _state

    private var lastBitmap: Bitmap? = null
    private var lastImageBitmap: ImageBitmap? = null

    private val javaController: OnGoingActionProgressController

    init {
        try {
            javaController = OnGoingActionProgressController(
                context,
                notificationListener,
                keyguardStateController,
                headsUpManager,
                flashlightController,
                hotspotController,
                zenModeController,
                batteryController,
                nextAlarmController,
                broadcastDispatcher,
                caffeineController,
                notifSuppressController
            )

            javaController.setStateCallback { isVisible, progress, maxProgress, iconBitmap, isAdaptive, packageName, isCompact, opacity, showMenu, activeStateType, batteryLevel, isCharging, isPowerSave, iconTint ->
                if (iconBitmap !== lastBitmap) {
                    lastBitmap = iconBitmap
                    lastImageBitmap = iconBitmap?.asImageBitmap()
                }
                _state.value = ProgressState(
                    isVisible = isVisible,
                    progress = progress,
                    maxProgress = maxProgress,
                    icon = lastImageBitmap,
                    packageName = packageName,
                    isIconAdaptive = isAdaptive,
                    isCompactMode = isCompact,
                    opacity = opacity,
                    showMediaControls = showMenu,
                    activeStateType = activeStateType,
                    batteryLevel = batteryLevel,
                    isCharging = isCharging,
                    isPowerSave = isPowerSave,
                    iconTint = iconTint,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OnGoingActionProgressController", e)
            throw e
        }
    }

    fun destroy() {
        javaController.destroy()
        lastBitmap = null
        lastImageBitmap = null
    }
    fun onInteraction() = javaController.onInteraction()
    fun onMediaAction(action: Int) = javaController.onMediaAction(action)
    fun onMediaMenuDismiss() = javaController.onMediaMenuDismiss()
    fun onDoubleTap() = javaController.onDoubleTap()
    fun onSwipe(isNext: Boolean) = javaController.onSwipe(isNext)
    fun onLongPress() = javaController.onLongPress()
    fun setSystemChipVisible(visible: Boolean) = javaController.setSystemChipVisible(visible)
}
