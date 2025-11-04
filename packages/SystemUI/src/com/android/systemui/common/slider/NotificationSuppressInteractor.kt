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
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
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
class NotificationSuppressInteractor @Inject constructor(
    private val context: Context,
) : LevelSliderInteractor {

    private val resolver: ContentResolver = context.contentResolver

    companion object {
       private const val SETTING_KEY = Settings.System.NOTIFICATION_SOUND_VIB_SCREEN_ON
        private const val PREF_NAME = "notif_suppress_prefs"
        private const val KEY_END_TIME = "suppress_end_time"
        
        private val DURATIONS_MINUTES = listOf(0, 15, 30, 60, 120, 240, -1)
        private const val INFINITE_INDEX = 6
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val _stateFlow = MutableStateFlow(getCurrentLevel())
    private val _labelFlow = MutableStateFlow(getInitialLabel())
    private var countdownTimer: CountDownTimer? = null
    private var currentDurationIndex = 0

    val label: StateFlow<String> = _labelFlow.asStateFlow()

    init {
        checkExistingTimer()
    }

    override val level: Flow<Float> = callbackFlow {
        trySend(_stateFlow.value)

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val newLevel = getCurrentLevel()
                trySend(newLevel)
            }
        }

        resolver.registerContentObserver(
            Settings.System.getUriFor(SETTING_KEY),
            false,
            observer
        )

        val job = _stateFlow.collect { trySend(it) }
        awaitClose {
            resolver.unregisterContentObserver(observer)
        }
    }.distinctUntilChanged()

    override fun getCurrentLevel(): Float {
        val isEnabled = Settings.System.getInt(resolver, SETTING_KEY, 1) == 0
        return if (isEnabled) {
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
            stopSuppression()
        } else {
           startSuppression(minutes)
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

    private fun startSuppression(minutes: Int) {
        stopCountdown()

        Settings.System.putInt(resolver, SETTING_KEY, 0)

        if (minutes == -1) {
            prefs.edit().putLong(KEY_END_TIME, -1).apply()
            _labelFlow.value = "Silent • ∞"
            return
        }

        val endTime = System.currentTimeMillis() + (minutes * 60 * 1000L)
        prefs.edit().putLong(KEY_END_TIME, endTime).apply()

        val durationMillis = minutes * 60 * 1000L
        countdownTimer = object : CountDownTimer(durationMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                _labelFlow.value = formatCountdown(seconds)
            }

            override fun onFinish() {
                stopSuppression()
            }
        }.start()
        
        _labelFlow.value = formatCountdown(minutes * 60)
    }

    private fun stopSuppression() {
        stopCountdown()
        Settings.System.putInt(resolver, SETTING_KEY, 1)
        prefs.edit().remove(KEY_END_TIME).apply()
        currentDurationIndex = 0
        _stateFlow.value = 0f
        _labelFlow.value = getInitialLabel()
    }

    private fun stopCountdown() {
        countdownTimer?.cancel()
        countdownTimer = null
    }

   private fun checkExistingTimer() {
        val endTime = prefs.getLong(KEY_END_TIME, 0)
        if (endTime == -1L) {
            currentDurationIndex = INFINITE_INDEX
            _labelFlow.value = "Silent • ∞"
        } else if (endTime > System.currentTimeMillis()) {
            val remainingMs = endTime - System.currentTimeMillis()
            val minutes = (remainingMs / 60000).toInt()
            startSuppression(minutes)
        }
    }

   private fun formatCountdown(seconds: Int): String {
        if (seconds == -1) return "Silent • ∞"
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("Silent • %02d:%02d", mins, secs)
    }

    private fun getInitialLabel(): String {
        return "Silent notifs"
    }

    @Composable
    override fun getIcon(level: Float): ImageVector {
        return if (level > 0f) Icons.Filled.NotificationsOff else Icons.Filled.Notifications
    }

    @Composable
    override fun getLabel(level: Float): String {
        return _labelFlow.collectAsState().value
    }
}
