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

package com.android.systemui.volume.panel.component.appvolume.ui.viewmodel

import android.content.Intent
import android.provider.Settings
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.haptics.slider.SliderHapticFeedbackFilter
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.volume.panel.component.appvolume.domain.interactor.AppVolumeInteractor
import com.android.systemui.volume.panel.component.appvolume.domain.interactor.AppVolumePanelGlobalStateInteractor
import com.android.systemui.volume.panel.component.appvolume.domain.model.AppVolumeModel
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

@SysUISingleton
class AppVolumeViewModel
@Inject
constructor(
    private val appVolumeInteractor: AppVolumeInteractor,
    private val appVolumePanelGlobalStateInteractor: AppVolumePanelGlobalStateInteractor,
    private val activityStarter: ActivityStarter,
    private val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
    fun getSliders(): StateFlow<List<AppVolumeModel>> = appVolumeInteractor.activeAppVolumes

    fun onVolumeChanged(packageName: String, newValue: Float) {
        appVolumeInteractor.setAppVolume(packageName, newValue / 100f)
    }

    fun onSettingsClicked() {
        appVolumePanelGlobalStateInteractor.setVisible(false)
        activityStarter.startActivity(
            Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            /* dismissShade= */ true,
        )
    }

    fun onDoneClicked() {
        appVolumePanelGlobalStateInteractor.setVisible(false)
    }

    fun getSliderHapticsViewModelFactory(): SliderHapticsViewModel.Factory =
        hapticsViewModelFactory

    data class AppVolumeSliderState(
        val packageName: String,
        override val value: Float,
        override val icon: Icon.Loaded?,
        override val label: String,
        override val valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
        override val step: Float = 1f,
        override val hapticFilter: SliderHapticFeedbackFilter = SliderHapticFeedbackFilter(),
        override val disabledMessage: String? = null,
        override val isEnabled: Boolean = true,
        override val a11yClickDescription: String? = null,
        override val a11yStateDescription: String? = null,
        override val a11yContentDescription: String = label,
        override val isMutable: Boolean = false,
    ) : SliderState
}
