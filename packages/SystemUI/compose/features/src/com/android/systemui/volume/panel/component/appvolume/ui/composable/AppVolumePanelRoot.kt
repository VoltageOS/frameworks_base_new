/*
 * Copyright (C) 2026 Halcyon Project
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

package com.android.systemui.volume.panel.component.appvolume.ui.composable

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.PlatformButton
import com.android.compose.PlatformOutlinedButton
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.haptics.slider.SliderHapticFeedbackFilter
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.sliders.ui.compose.SliderTrack
import com.android.systemui.volume.haptics.ui.VolumeHapticsConfigsProvider
import com.android.systemui.volume.panel.component.appvolume.domain.model.AppVolumeModel
import com.android.systemui.volume.panel.component.appvolume.ui.viewmodel.AppVolumeViewModel
import com.android.systemui.volume.ui.compose.slider.AccessibilityParams
import com.android.systemui.volume.ui.compose.slider.Haptics
import com.android.systemui.volume.ui.compose.slider.Slider
import com.android.systemui.volume.ui.compose.slider.SliderIcon

private const val AppVolumePanelTestTag = "AppVolumePanel"
private val padding = 24.dp

@Composable
fun AppVolumePanelRoot(
    viewModel: AppVolumeViewModel,
    modifier: Modifier = Modifier,
) {
    val accessibilityTitle = stringResource(R.string.app_volume)
    val appVolumes by viewModel.getSliders().collectAsStateWithLifecycle()

    Column(
        modifier =
            modifier
                .sysuiResTag(AppVolumePanelTestTag)
                .semantics { paneTitle = accessibilityTitle }
                .padding(start = padding, top = padding, end = padding, bottom = 20.dp)
                .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(padding),
    ) {
        // Header
        Text(
            text = stringResource(R.string.app_volume),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Sliders list
        if (appVolumes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.app_volume_no_active_apps),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier =
                    Modifier.weight(weight = 1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for (app in appVolumes) {
                    AppVolumeSliderItem(
                        app = app,
                        viewModel = viewModel,
                    )
                }
            }
        }

        // Bottom Bar
        Row(
            modifier = Modifier.heightIn(min = 48.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlatformOutlinedButton(onClick = viewModel::onSettingsClicked) {
                Text(text = stringResource(R.string.volume_panel_dialog_settings_button))
            }
            PlatformButton(onClick = viewModel::onDoneClicked) {
                Text(text = stringResource(R.string.inline_done_button))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppVolumeSliderItem(
    app: AppVolumeModel,
    viewModel: AppVolumeViewModel,
    modifier: Modifier = Modifier,
) {
    var currentValue by remember(app.packageName, app.volume) {
        mutableFloatStateOf((app.volume * 100f).coerceIn(0f, 100f))
    }

    Column(modifier = modifier.fillMaxWidth().animateContentSize()) {
        Text(
            text = app.label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val materialSliderColors =
                SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f),
                    inactiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    thumbColor = MaterialTheme.colorScheme.primary,
                )

            Slider(
                value = currentValue,
                valueRange = 0f..100f,
                onValueChanged = { newValue ->
                    currentValue = newValue
                    viewModel.onVolumeChanged(app.packageName, newValue)
                },
                onValueChangeFinished = null,
                colors = materialSliderColors,
                isEnabled = true,
                stepDistance = 1f,
                accessibilityParams =
                    AccessibilityParams(
                        contentDescription = app.label,
                        stateDescription = null,
                    ),
                track = { sliderState ->
                    SliderTrack(
                        sliderState = sliderState,
                        colors = materialSliderColors,
                        isEnabled = true,
                        trackSize = 40.dp,
                        activeTrackEndIcon =
                            app.icon?.drawable?.let { drawable ->
                                { iconsState ->
                                    SliderIcon(
                                        icon = {
                                            Image(
                                                painter = rememberDrawablePainter(drawable),
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp).clip(CircleShape),
                                            )
                                        },
                                        isVisible = !iconsState.isInactiveTrackEndIconVisible,
                                    )
                                }
                            },
                        inactiveTrackEndIcon =
                            app.icon?.drawable?.let { drawable ->
                                { iconsState ->
                                    SliderIcon(
                                        icon = {
                                            Image(
                                                painter = rememberDrawablePainter(drawable),
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp).clip(CircleShape),
                                            )
                                        },
                                        isVisible = iconsState.isInactiveTrackEndIconVisible,
                                    )
                                }
                            },
                    )
                },
                thumb = { sliderState, interactionSource ->
                    SliderDefaults.Thumb(
                        sliderState = sliderState,
                        interactionSource = interactionSource,
                        enabled = true,
                        colors = materialSliderColors,
                        thumbSize = DpSize(4.dp, 52.dp),
                    )
                },
                haptics =
                    viewModel.getSliderHapticsViewModelFactory()?.let {
                        Haptics.Enabled(
                            hapticsViewModelFactory = it,
                            hapticConfigs =
                                VolumeHapticsConfigsProvider.continuousConfigs(
                                    SliderHapticFeedbackFilter()
                                ),
                            orientation = Orientation.Horizontal,
                        )
                    } ?: Haptics.Disabled,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .sysuiResTag(app.label),
            )
        }
    }
}
