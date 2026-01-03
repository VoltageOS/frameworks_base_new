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
package com.android.systemui.statusbar.policy

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import javax.inject.Inject
import javax.inject.Provider

@SysUISingleton
class NotificationSuppressController @Inject constructor(
    private val context: Context,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val dialogDelegateProvider: Provider<NotificationSuppressDialogDelegate>,
    private val keyguardStateController: KeyguardStateController,
    private val activityStarter: ActivityStarter,
    private val mainHandler: Handler
) {
    interface StateListener {
        fun onStateChanged(suppressed: Boolean, label: String)
    }

    companion object {
        private const val SETTING_KEY = Settings.System.NOTIFICATION_SOUND_VIB_SCREEN_ON
        private const val PREF_NAME = "notif_suppress_prefs"
        private const val KEY_END_TIME = "suppress_end_time"
    }

    private val resolver: ContentResolver = context.contentResolver
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val listeners = mutableSetOf<StateListener>()
    private var countdownTimer: CountDownTimer? = null
    private var currentLabel = ""
    
    // 0=Off, then minutes, -1=Infinite
    val durations = listOf(0, 15, 30, 60, 120, 240, -1)
    var currentIndex = 0
        private set

    val isSuppressed: Boolean
        get() = Settings.System.getInt(resolver, SETTING_KEY, 1) == 0

    init {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                checkState()
            }
        }
        resolver.registerContentObserver(
            Settings.System.getUriFor(SETTING_KEY), false, observer
        )
        checkExistingTimer()
    }

    fun addListener(listener: StateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: StateListener) {
        listeners.remove(listener)
    }

fun setDuration(index: Int) {
        stopCountdown()
        currentIndex = index.coerceIn(0, durations.size - 1)
        val minutes = durations[currentIndex]

        if (minutes == 0) {
            Settings.System.putInt(resolver, SETTING_KEY, 1) // Normal
            prefs.edit().remove(KEY_END_TIME).apply()
            currentLabel = ""
        } else {
            Settings.System.putInt(resolver, SETTING_KEY, 0) // Suppressed
            if (minutes == -1) {
                prefs.edit().putLong(KEY_END_TIME, -1).apply()
                currentLabel = "Silent • ∞"
            } else {
                // Fixed line below: added '+'
                val endTime = System.currentTimeMillis() + (minutes * 60 * 1000L)
                prefs.edit().putLong(KEY_END_TIME, endTime).apply()
                startTimer(minutes * 60 * 1000L)
            }
        }
        notifyListeners()
    }

    private fun startTimer(durationMillis: Long) {
        countdownTimer = object : CountDownTimer(durationMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                currentLabel = formatTime((millisUntilFinished / 1000).toInt())
                notifyListeners()
            }
            override fun onFinish() {
                setDuration(0)
            }
        }.start()
    }

    private fun stopCountdown() {
        countdownTimer?.cancel()
        countdownTimer = null
    }

    private fun checkExistingTimer() {
        val endTime = prefs.getLong(KEY_END_TIME, 0)
        if (isSuppressed && endTime != 0L) {
            if (endTime == -1L) {
                currentIndex = durations.size - 1
                currentLabel = "Silent • ∞"
            } else if (endTime > System.currentTimeMillis()) {
                val remaining = endTime - System.currentTimeMillis()
                startTimer(remaining)
            } else {
                setDuration(0)
            }
        }
    }

    private fun checkState() {
        if (!isSuppressed) {
            stopCountdown()
            currentIndex = 0
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { it.onStateChanged(isSuppressed, currentLabel) }
    }

    fun getCurrentLabel() = currentLabel
    private fun formatTime(seconds: Int) = String.format("Silent • %02d:%02d", seconds / 60, seconds % 60)

    fun expandDialog(expandable: Expandable?) {
        val animate = expandable != null && !keyguardStateController.isShowing
        mainHandler.post {
            activityStarter.executeRunnableDismissingKeyguard({
                val delegate = dialogDelegateProvider.get()
                val dialog = delegate.createDialog()
                if (animate) {
                    val c = expandable?.dialogTransitionController(DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, "suppress"))
                    c?.let { dialogTransitionAnimator.show(dialog, it) } ?: dialog.show()
                } else dialog.show()
            }, null, false, true, false)
        }
    }
}
