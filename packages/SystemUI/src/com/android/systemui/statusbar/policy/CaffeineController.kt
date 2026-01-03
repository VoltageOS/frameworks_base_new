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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.CountDownTimer
import android.os.Handler
import android.os.PowerManager
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.phone.SystemUIDialog
import javax.inject.Inject
import javax.inject.Provider

@SysUISingleton
class CaffeineController @Inject constructor(
    private val context: Context,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val dialogDelegateProvider: Provider<CaffeineDialogDelegate>,
    private val keyguardStateController: KeyguardStateController,
    private val activityStarter: ActivityStarter,
    private val mainHandler: Handler
) {
    interface CaffeineStateListener {
        fun onCaffeineStateChanged(active: Boolean, label: String)
    }

    private val powerManager: PowerManager = context.getSystemService(PowerManager::class.java)
    private val wakeLock = powerManager.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "CaffeineController"
    )
    private val listeners = mutableSetOf<CaffeineStateListener>()
    private var countdownTimer: CountDownTimer? = null
    private var currentLabel = ""
    
    // Slider index to duration in minutes. Last is Infinite.
    val durations = listOf(0, 5, 10, 30, -1)
    var currentIndex = 0
        private set

    val isActive: Boolean get() = wakeLock.isHeld

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    setDuration(0)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    fun addListener(listener: CaffeineStateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: CaffeineStateListener) {
        listeners.remove(listener)
    }

    fun setDuration(index: Int) {
        stopCountdown()
        currentIndex = index.coerceIn(0, durations.size - 1)
        val minutes = durations[currentIndex]

        if (minutes == 0) {
            if (wakeLock.isHeld) wakeLock.release()
            currentLabel = ""
            notifyListeners()
        } else {
            if (!wakeLock.isHeld) wakeLock.acquire()
            
            if (minutes == -1) {
                currentLabel = "Infinite"
                notifyListeners()
            } else {
                startTimer(minutes)
            }
        }
    }

    private fun startTimer(minutes: Int) {
        val durationMillis = minutes * 60 * 1000L
        countdownTimer = object : CountDownTimer(durationMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                currentLabel = formatTime(seconds)
                notifyListeners()
            }
            override fun onFinish() {
                setDuration(0)
            }
        }.start()
        currentLabel = formatTime(minutes * 60)
        notifyListeners()
    }

    private fun stopCountdown() {
        countdownTimer?.cancel()
        countdownTimer = null
    }

    private fun notifyListeners() {
        listeners.forEach { it.onCaffeineStateChanged(isActive, currentLabel) }
    }

    fun getCurrentLabel() = currentLabel

    private fun formatTime(seconds: Int): String = 
        String.format("%02d:%02d", seconds / 60, seconds % 60)

    fun expandDialog(expandable: Expandable?) {
        val animateFromExpandable = expandable != null && !keyguardStateController.isShowing
        val runnable = Runnable {
            val delegate = dialogDelegateProvider.get()
            val dialog = delegate.createDialog()
            if (animateFromExpandable) {
                val controller = expandable?.dialogTransitionController(
                    DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, "caffeine")
                )
                controller?.let { dialogTransitionAnimator.show(dialog, it) } ?: dialog.show()
            } else {
                dialog.show()
            }
        }
        mainHandler.post {
            activityStarter.executeRunnableDismissingKeyguard(
                runnable, null, false, true, false
            )
        }
    }
}
