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
class AnimationScaleController @Inject constructor(
    private val context: Context,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val dialogDelegateProvider: Provider<AnimationScaleDialogDelegate>,
    private val keyguardStateController: KeyguardStateController,
    private val activityStarter: ActivityStarter,
    private val mainHandler: Handler
) {
    interface AnimationScaleListener {
        fun onAnimationScaleChanged(scale: Float)
    }

    private val resolver: ContentResolver = context.contentResolver
    private val listeners = mutableSetOf<AnimationScaleListener>()
    
    // Matches values from original Interactor implementation
    val scaleValues = listOf(0f, 0.5f, 1.0f, 1.5f, 2.0f, 5.0f)
    val scaleLabels = listOf("Off", "0.5x", "1x", "1.5x", "2x", "5x")

    val isAnimationsOn: Boolean
        get() = getScale() > 0f

    init {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                notifyListeners()
            }
        }

        val uris = listOf(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE),
            Settings.Global.getUriFor(Settings.Global.WINDOW_ANIMATION_SCALE)
        )
        
        uris.forEach { resolver.registerContentObserver(it, false, observer) }
    }

    fun addListener(listener: AnimationScaleListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: AnimationScaleListener) {
        listeners.remove(listener)
    }

    fun getScale(): Float {
        return Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
    }

    fun getCurrentIndex(): Int {
        val currentScale = getScale()
        // Find closest match
        val index = scaleValues.indexOfFirst { it >= currentScale }
        return if (index >= 0) index else 2 // Default to 1.0f (index 2) if not found/weird
    }

    fun getCurrentLabel(): String {
        val index = getCurrentIndex()
        return "Speed • ${scaleLabels.getOrElse(index) { "1x" }}"
    }

    fun setIndex(index: Int) {
        val safeIndex = index.coerceIn(0, scaleValues.size - 1)
        val scale = scaleValues[safeIndex]
        
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
            notifyListeners()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun notifyListeners() {
        val scale = getScale()
        listeners.forEach { it.onAnimationScaleChanged(scale) }
    }

    fun expandDialog(expandable: Expandable?) {
        val animateFromExpandable = expandable != null && !keyguardStateController.isShowing
        val runnable = Runnable {
            val delegate = dialogDelegateProvider.get()
            val dialog = delegate.createDialog()
            if (animateFromExpandable) {
                val controller = expandable?.dialogTransitionController(
                    DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, "animation_scale")
                )
                if (controller != null) {
                    dialogTransitionAnimator.show(dialog, controller)
                } else {
                    dialog.show()
                }
            } else {
                dialog.show()
            }
        }
        mainHandler.post {
            activityStarter.executeRunnableDismissingKeyguard(runnable, null, false, true, false)
        }
    }
}
