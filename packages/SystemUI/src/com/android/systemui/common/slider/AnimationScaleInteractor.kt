/*
 * Copyright (C) 2025 VoltageOS
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
package com.android.systemui.common.slider

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlin.math.roundToInt
import javax.inject.Inject

@SysUISingleton
class AnimationScaleInteractor @Inject constructor(
    private val context: Context,
) : LevelSliderInteractor {

    private val resolver: ContentResolver = context.contentResolver

    companion object {
        private val SCALE_VALUES = listOf(0f, 0.5f, 1.0f, 1.5f, 2.0f, 5.0f)
        private val SCALE_LABELS = listOf("Off", "0.5x", "1x", "1.5x", "2x", "5x")
    }

    private val _stateFlow = MutableStateFlow(getCurrentLevel())
    private val _labelFlow = MutableStateFlow("Speed • 1x")

    val label: StateFlow<String> = _labelFlow.asStateFlow()

    init {
        updateLabel()
    }

    override val level: Flow<Float> = callbackFlow {
        trySend(_stateFlow.value)

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val newLevel = getCurrentLevel()
                _stateFlow.value = newLevel
                updateLabel()
                trySend(newLevel)
            }
        }

        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer
        )
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE),
            false,
            observer
        )
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.WINDOW_ANIMATION_SCALE),
            false,
            observer
        )

        _stateFlow.collect { trySend(it) }

        awaitClose {
            resolver.unregisterContentObserver(observer)
        }
    }.distinctUntilChanged()

    override fun getCurrentLevel(): Float {
        val currentScale = Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )

        val index = SCALE_VALUES.indexOfFirst { it >= currentScale }.takeIf { it >= 0 } 
            ?: (SCALE_VALUES.size - 1)
        
        return index / (SCALE_VALUES.size - 1).toFloat()
    }

    override fun setLevel(level: Float) {
        val index = (level * (SCALE_VALUES.size - 1)).roundToInt()
            .coerceIn(0, SCALE_VALUES.size - 1)
        
        val scale = SCALE_VALUES[index]

        try {
            Settings.Global.putFloat(
                resolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                scale
            )
            Settings.Global.putFloat(
                resolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                scale
            )
            Settings.Global.putFloat(
                resolver,
                Settings.Global.WINDOW_ANIMATION_SCALE,
                scale
            )

            _stateFlow.value = level
            updateLabel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateLabel() {
        val currentScale = Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
        val index = SCALE_VALUES.indexOfFirst { it >= currentScale }.takeIf { it >= 0 } ?: 2
        _labelFlow.value = "Speed • ${SCALE_LABELS[index]}"
    }

    @Composable
    override fun getIcon(level: Float): ImageVector = Icons.Filled.Speed

    @Composable
    override fun getLabel(level: Float): String = _labelFlow.collectAsState().value
}
