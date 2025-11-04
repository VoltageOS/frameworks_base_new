/*
 * Copyright (C) 2025 AxionOS
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
package com.android.systemui.qs.tiles.impl

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.Dependency
import com.android.systemui.common.ringer.RingerModeInteractorImpl
import com.android.systemui.common.ringer.RingerSliderWidget
import com.android.systemui.common.ringer.RingerSliderTheme
import com.android.systemui.common.ringer.RingerSliderDimens
import com.android.systemui.common.slider.LevelSliderInteractor
import com.android.systemui.common.slider.LevelSliderWidget
import com.android.systemui.common.slider.LevelSliderTheme
import com.android.systemui.common.slider.LevelSliderDimens
import com.android.systemui.common.slider.VolumeInteractor
import com.android.systemui.common.slider.TorchLevelInteractor
import com.android.systemui.common.slider.CaffeineInteractor
import com.android.systemui.common.slider.NotificationSuppressInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileHeight
import com.android.systemui.statusbar.policy.FlashlightStrengthController
import com.android.systemui.util.CustomAndroidColorScheme
import javax.inject.Inject

// Note: Also update LevelSliderWidget.kt line 38 to change:
// import com.android.systemui.util.AxColorScheme
// to:
// import com.android.systemui.util.CustomAndroidColorScheme
//
// And update lines 63-67 to use CustomAndroidColorScheme.current instead of AxColorScheme

class AxTileTheme : RingerSliderTheme, LevelSliderTheme {
    override val activeBg: Color
        @Composable get() = CustomAndroidColorScheme.current.primary
    
    override val neutralBg: Color
        @Composable get() = CustomAndroidColorScheme.current.secondary
    
    override val activeIcon: Color
        @Composable get() = CustomAndroidColorScheme.current.onPrimary
    
    override val neutralIcon: Color
        @Composable get() = CustomAndroidColorScheme.current.onSurface
    
    override val labelColor: Color
        @Composable get() = CustomAndroidColorScheme.current.onSurface
    
    override val dozeStroke: Dp = 2.dp
}

class AxTileRingerDimens(
    private val tileHeight: Dp
) : RingerSliderDimens {
    override val totalWidth: Dp? = null
    override val thumbSize: Dp = tileHeight
    override val iconSize: Dp = 24.dp
    override val thumbPadding: Dp = 8.dp
    override val dotSize: Dp = 6.dp
}

class AxTileLevelDimens(
    private val tileHeight: Dp
) : LevelSliderDimens {
    override val totalWidth: Dp? = null
    override val height: Dp = tileHeight
    override val iconSize: Dp = 24.dp
    override val horizontalPadding: Dp = 16.dp
    override val labelPadding: Dp = 12.dp
}

@SysUISingleton
class AxTileProvider @Inject constructor(
    private val flashlightController: FlashlightStrengthController,
    private val caffeineInteractor: CaffeineInteractor,
    private val notificationSuppressInteractor: NotificationSuppressInteractor,
) {

    companion object {
        @JvmStatic
        fun get(): AxTileProvider {
            return Dependency.get(AxTileProvider::class.java)
        }
    }

    @Composable
    fun provideAxTile(
        spec: String,
        border: Modifier = Modifier
    ): Boolean {
        return when (spec) {
            "sound" -> {
                RingerSlider(border)
                true
            }
            "volume" -> {
                VolumeSlider(border)
                true
            }
            "flashlight" -> {
                if (flashlightController.supported) {
                    TorchSlider(border)
                    true
                } else {
                    false
                }
            }
            "caffeine" -> {
                LevelSliderTile(interactor = caffeineInteractor, border = border)
                true
            }
            "notif_suppress" -> {
                LevelSliderTile(interactor = notificationSuppressInteractor, border = border)
                true
            }
            else -> false
        }
    }
    
    @Composable
    private fun RingerSlider(border: Modifier = Modifier) {
        val context = LocalContext.current
        val tileHeight = context.TileHeight
        val modifier = Modifier.fillMaxWidth(0.9f)
        
        val interactor = remember {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            RingerModeInteractorImpl(context, audioManager)
        }
        
        RingerSliderWidget(
            interactor = interactor,
            theme = AxTileTheme(),
            dimens = AxTileRingerDimens(tileHeight),
            modifier = modifier,
            isDozing = false,
            border = border
        )
    }
    
    @Composable
    private fun VolumeSlider(border: Modifier = Modifier) {
        val streamType = AudioManager.STREAM_MUSIC
        val context = LocalContext.current
        
        val interactor = remember(streamType) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            VolumeInteractor(context, audioManager, streamType)
        }
        
        LevelSliderTile(interactor = interactor, border = border)
    }
    
    @Composable
    private fun TorchSlider(border: Modifier = Modifier) {
        val interactor = remember {
            TorchLevelInteractor(flashlightController)
        }
        
        LevelSliderTile(interactor = interactor, border = border)
    }

    @Composable
    private fun LevelSliderTile(
        interactor: LevelSliderInteractor,
        border: Modifier = Modifier
    ) {
        val context = LocalContext.current
        val tileHeight = context.TileHeight
        val modifier = Modifier.fillMaxWidth(0.9f)
        
        LevelSliderWidget(
            interactor = interactor,
            theme = AxTileTheme(),
            dimens = AxTileLevelDimens(tileHeight),
            modifier = modifier,
            isDozing = false,
            border = border
        )
    }
}
