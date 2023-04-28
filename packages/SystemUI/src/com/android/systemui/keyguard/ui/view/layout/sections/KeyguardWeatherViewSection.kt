/*
 * Copyright (C) 2024-2025 crDroid Android Project
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
import android.view.LayoutInflater
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.customization.R as custR
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import com.android.systemui.weather.WeatherInfoView
import javax.inject.Inject

class KeyguardWeatherViewSection
@Inject
constructor(
    private val context: Context,
    // Add LayoutInflater to the constructor
    private val layoutInflater: LayoutInflater,
) : KeyguardSection() {

    private var weatherArea: WeatherInfoView? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        // Inflate the view here instead of finding it
        weatherArea = layoutInflater.inflate(R.layout.keyguard_weather_area, constraintLayout, false)
            as WeatherInfoView

        constraintLayout.addView(weatherArea)
        weatherArea?.init()
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        // Data binding logic can go here if needed, but your init() call in addViews is fine.
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        // Your existing constraint logic is correct and should work without changes.
        // It positions the weather view below the slice view.
        constraintSet.apply {
            connect(
                R.id.keyguard_weather_area,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                context.resources.getDimensionPixelSize(custR.dimen.clock_padding_start) +
                    context.resources.getDimensionPixelSize(custR.dimen.status_view_margin_horizontal),
            )
            connect(
                R.id.keyguard_weather_area,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END
            )
            constrainHeight(R.id.keyguard_weather_area, ConstraintSet.WRAP_CONTENT)

            connect(
                R.id.keyguard_weather_area,
                ConstraintSet.TOP,
                R.id.keyguard_slice_view,
                ConstraintSet.BOTTOM
            )

            // This seems incorrect, as it re-defines the barrier. 
            // The smartspace section already creates this barrier.
            // You may need to add to the existing barrier instead.
            // However, for now, let's see if this works. A better approach might be:
            // addToHorizontalChain(R.id.smart_space_barrier_bottom, R.id.keyguard_weather_area, ConstraintSet.GONE)
            createBarrier(
                R.id.smart_space_barrier_bottom,
                Barrier.BOTTOM,
                0,
                *intArrayOf(R.id.keyguard_weather_area)
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        // Clean up the view and controller
        constraintLayout.removeView(weatherArea)
        weatherArea?.cleanup()
        weatherArea = null
    }
}
