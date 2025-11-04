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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.CountDownTimer
import android.os.PowerManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalCafe
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
class CaffeineInteractor @Inject constructor(
    private val context: Context,
) : LevelSliderInteractor {

    private val powerManager: PowerManager = context.getSystemService(PowerManager::class.java)

    companion object {
        private val DURATIONS_MINUTES = listOf(0, 5, 15, 30, 60, 120, -1)
        private const val INFINITE_INDEX = 6
    }

    private val wakeLock = powerManager.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
        "CaffeineInteractor"
    )

    private val _stateFlow = MutableStateFlow(getCurrentLevel())
    private val _labelFlow = MutableStateFlow(context.getString(R.string.quick_settings_caffeine_label))
    private var countdownTimer: CountDownTimer? = null
    private var currentDurationIndex = 0

    val label: StateFlow<String> = _labelFlow.asStateFlow()

    override val level: Flow<Float> = callbackFlow {
        trySend(_stateFlow.value)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    stopCaffeine()
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        context.registerReceiver(receiver, filter)

        _stateFlow.collect { trySend(it) }

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }.distinctUntilChanged()

    override fun getCurrentLevel(): Float {
        return if (wakeLock.isHeld) {
            currentDurationIndex / (DURATIONS_MINUTES.size - 1).toFloat()
        } else {
            0f
        }
    }

    override fun setLevel(level: Float) {
        val index = (level * (DURATIONS_MINUTES.size - 1)).roundToInt()
            .coerceIn(0, DURATIONS_MINUTES.size - 1)
        
        currentDurationIndex = index
        val minutes = DURATIONS_MINUTES[index]

        if (minutes == 0) {
            stopCaffeine()
        } else {
            startCaffeine(minutes)
        }

        _stateFlow.value = level
    }

    fun cycleTimeout() {
        val newIndex = if (currentDurationIndex == 0) {
            1
       } else {
            (currentDurationIndex + 1) % DURATIONS_MINUTES.size
        }
        setLevel(newIndex / (DURATIONS_MINUTES.size - 1).toFloat())
    }

    fun setInfinite() {
        setLevel(1.0f)
    }

    private fun startCaffeine(minutes: Int) {
        stopCountdown()

        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }

        if (minutes == -1) {
            _labelFlow.value = formatCountdown(-1)
            return
        }

        val durationMillis = minutes * 60 * 1000L
        
        countdownTimer = object : CountDownTimer(durationMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                _labelFlow.value = formatCountdown(seconds)
            }

            override fun onFinish() {
                stopCaffeine()
            }
        }.start()
        
        _labelFlow.value = formatCountdown(minutes * 60)
    }

    private fun stopCaffeine() {
        stopCountdown()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        currentDurationIndex = 0
        _stateFlow.value = 0f
        _labelFlow.value = context.getString(R.string.quick_settings_caffeine_label)
    }

    private fun stopCountdown() {
        countdownTimer?.cancel()
        countdownTimer = null
    }

    private fun formatCountdown(seconds: Int): String {
        val labelPrefix = context.getString(R.string.quick_settings_caffeine_label)
        if (seconds == -1) return "$labelPrefix • ∞"
        val hours = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return if (hours > 0) {
            String.format("$labelPrefix • %d:%02d:%02d", hours, mins, secs)
        } else {
            String.format("$labelPrefix • %02d:%02d", mins, secs)
        }
    }

    @Composable
    override fun getIcon(level: Float): ImageVector {
        return Icons.Filled.LocalCafe
    }

    @Composable
    override fun getLabel(level: Float): String {
        return _labelFlow.collectAsState().value
    }
}
