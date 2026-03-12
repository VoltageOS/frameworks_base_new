/*
 * Copyright (C) 2024 The Android Open Source Project
 * Copyright (C) 2026 VoltageOS
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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.keyguard.KeyguardSliceView
import com.android.keyguard.KeyguardSliceViewController
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.KeyguardSliceViewBinder
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

class KeyguardSliceViewSection
@Inject
constructor(
    private val context: Context,
    val smartspaceController: LockscreenSmartspaceController,
    val layoutInflater: LayoutInflater,
    @Main val handler: Handler,
    @Background val bgHandler: Handler,
    val activityStarter: ActivityStarter,
    val keyguardClockViewModel: KeyguardClockViewModel,
    val configurationController: ConfigurationController,
    val dumpManager: DumpManager,
    val displayTracker: DisplayTracker,
    val keyguardInteractor: KeyguardInteractor,
    val powerInteractor: PowerInteractor,
    val aodBurnInViewModel: AodBurnInViewModel,
) : KeyguardSection() {
    private lateinit var sliceView: KeyguardSliceView
    private var disposableHandle: DisposableHandle? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (smartspaceController.isEnabled) return
        sliceView =
            layoutInflater.inflate(R.layout.keyguard_slice_view, null, false) as KeyguardSliceView
        constraintLayout.addView(sliceView)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (smartspaceController.isEnabled) return
        val controller =
            KeyguardSliceViewController(
                handler,
                bgHandler,
                sliceView,
                activityStarter,
                configurationController,
                dumpManager,
                displayTracker,
                keyguardInteractor,
                powerInteractor,
            )
        controller.setupUri(null)
        controller.init()

        disposableHandle?.dispose()
        disposableHandle =
            KeyguardSliceViewBinder.bind(
                sliceView,
                keyguardInteractor,
                controller,
                aodBurnInViewModel,
            )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (smartspaceController.isEnabled) return

        val isModern = smartspaceController.isOmniWeatherModern
        val isLargeClock = keyguardClockViewModel.isLargeClockVisible.value
        val marginStart =
            context.resources.getDimensionPixelSize(clocksR.dimen.clock_padding_start) +
                context.resources.getDimensionPixelSize(clocksR.dimen.status_view_margin_horizontal)
        
        val clockGap = (16 * context.resources.displayMetrics.density).toInt()
        
        val smallClockGap = (46 * context.resources.displayMetrics.density).toInt()
        
        val topClockMargin = (36 * context.resources.displayMetrics.density).toInt()
        val barrierMargin = (48 * context.resources.displayMetrics.density).toInt()

        constraintSet.apply {
            constrainHeight(R.id.keyguard_slice_view, ConstraintSet.WRAP_CONTENT)

            when {
                isModern && !isLargeClock -> {
                    sliceView.setPaddingRelative(smallClockGap, 0, 0, 0)
                    
                    try {
                        setMargin(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL, ConstraintSet.TOP, topClockMargin)
                    } catch (e: Exception) {}

                    constrainWidth(R.id.keyguard_slice_view, ConstraintSet.WRAP_CONTENT)
                    clear(R.id.keyguard_slice_view, ConstraintSet.START)
                    clear(R.id.keyguard_slice_view, ConstraintSet.END)
                    clear(R.id.keyguard_slice_view, ConstraintSet.TOP)
                    clear(R.id.keyguard_slice_view, ConstraintSet.BOTTOM)

                    connect(
                        R.id.keyguard_slice_view, ConstraintSet.START,
                        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL, ConstraintSet.END,
                        0,
                    )
                    connect(
                        R.id.keyguard_slice_view, ConstraintSet.END,
                        ConstraintSet.PARENT_ID, ConstraintSet.END,
                        0,
                    )
                    setHorizontalBias(R.id.keyguard_slice_view, 0.0f)
                    
                    connect(
                        R.id.keyguard_slice_view, ConstraintSet.TOP,
                        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL, ConstraintSet.TOP,
                    )
                    connect(
                        R.id.keyguard_slice_view, ConstraintSet.BOTTOM,
                        R.id.weather_inline_text, ConstraintSet.TOP,
                    )
                    setVerticalChainStyle(R.id.keyguard_slice_view, ConstraintSet.CHAIN_PACKED)
                    setVerticalBias(R.id.keyguard_slice_view, 0.5f)
                }

                isModern && isLargeClock -> {
                    sliceView.setPaddingRelative(0, 0, 0, 0)
                    constrainWidth(R.id.keyguard_slice_view, ConstraintSet.WRAP_CONTENT)
                    clear(R.id.keyguard_slice_view, ConstraintSet.START)
                    clear(R.id.keyguard_slice_view, ConstraintSet.END)
                    clear(R.id.keyguard_slice_view, ConstraintSet.TOP)
                    clear(R.id.keyguard_slice_view, ConstraintSet.BOTTOM)

                    connect(
                        R.id.keyguard_slice_view, ConstraintSet.START,
                        ConstraintSet.PARENT_ID, ConstraintSet.START,
                    )
                    connect(
                        R.id.keyguard_slice_view, ConstraintSet.END,
                        R.id.weather_inline_text, ConstraintSet.START,
                    )
                    setHorizontalChainStyle(R.id.keyguard_slice_view, ConstraintSet.CHAIN_PACKED)
                    setHorizontalBias(R.id.keyguard_slice_view, 0.5f)

                    connect(
                        R.id.keyguard_slice_view, ConstraintSet.TOP,
                        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, ConstraintSet.BOTTOM,
                        clockGap,
                    )
                }

                else -> {
                    sliceView.setPaddingRelative(0, 0, 0, 0)
                    constrainWidth(R.id.keyguard_slice_view, ConstraintSet.MATCH_CONSTRAINT)
                    clear(R.id.keyguard_slice_view, ConstraintSet.START)
                    clear(R.id.keyguard_slice_view, ConstraintSet.END)
                    clear(R.id.keyguard_slice_view, ConstraintSet.TOP)
                    clear(R.id.keyguard_slice_view, ConstraintSet.BOTTOM)

                    connect(
                        R.id.keyguard_slice_view, ConstraintSet.START,
                        ConstraintSet.PARENT_ID, ConstraintSet.START,
                        marginStart,
                    )
                    connect(
                        R.id.keyguard_slice_view, ConstraintSet.END,
                        ConstraintSet.PARENT_ID, ConstraintSet.END,
                    )
                    connect(
                        R.id.keyguard_slice_view, ConstraintSet.TOP,
                        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL, ConstraintSet.BOTTOM,
                    )
                }
            }

            val barrierIds = mutableListOf(R.id.keyguard_slice_view)
            if (smartspaceController.isOmniWeatherEnabled) {
                if (isModern) {
                    barrierIds.add(R.id.weather_inline_text)
                } else {
                    barrierIds.add(R.id.keyguard_weather_area)
                }
            }
            
            createBarrier(
                R.id.smart_space_barrier_bottom,
                Barrier.BOTTOM,
                if (isModern && !isLargeClock) barrierMargin else 0,
                *barrierIds.toIntArray()
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (smartspaceController.isEnabled) return

        disposableHandle?.dispose()
        constraintLayout.removeView(R.id.keyguard_slice_view)
    }
}
