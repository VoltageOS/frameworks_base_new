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

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.android.settingslib.Utils
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.concurrency.DelayableExecutor
import com.google.android.material.slider.Slider
import javax.inject.Inject
import kotlin.math.roundToInt

class AnimationScaleDialogDelegate @Inject constructor(
    private val ctx: Context,
    private val systemUIDialogFactory: SystemUIDialog.Factory,
    private val layoutInflater: android.view.LayoutInflater,
    private val controller: AnimationScaleController,
    @Main private val mainHandler: Handler,
    @Background private val backgroundDelayableExecutor: DelayableExecutor
) : SystemUIDialog.Delegate, AnimationScaleController.AnimationScaleListener {

    private lateinit var statusText: TextView
    private lateinit var doneButton: Button
    private lateinit var slider: Slider
    private var userTracking = false

    private val iconScale: Drawable get() = ContextCompat.getDrawable(ctx, R.drawable.ic_qs_animation_scale)!!
    private val iconColor: ColorStateList
        get() = ColorStateList.valueOf(
            Utils.getColorAttrDefaultColor(ctx, android.R.attr.textColorPrimaryInverse)
        )

    override fun createDialog(): SystemUIDialog = systemUIDialogFactory.create(this)

    override fun beforeCreate(d: SystemUIDialog, b: Bundle?) {
        d.setTitle(R.string.qs_animation_scale_dialog_title)
        d.setView(layoutInflater.inflate(R.layout.qs_animation_scale_dialog, null))
        d.setPositiveButton(R.string.quick_settings_done, null, true)
    }

    override fun onCreate(d: SystemUIDialog, savedInstanceState: Bundle?) {
        statusText = d.requireViewById(R.id.animation_scale_status_text)
        doneButton = d.requireViewById(com.android.internal.R.id.button1)
        slider = d.requireViewById(R.id.animation_scale_slider)

        controller.addListener(this)
        setupSlider()
        setupListeners(d)
        updateStatusText(controller.getCurrentIndex())
    }

    private fun setupSlider() {
        val maxIndex = controller.scaleValues.size - 1
        slider.valueFrom = 0f
        slider.valueTo = maxIndex.toFloat()
        slider.stepSize = 1f
        slider.value = controller.getCurrentIndex().toFloat()
        slider.trackIconActiveStart = iconScale
        slider.trackIconActiveColor = iconColor
        slider.setLabelFormatter { value ->
            val index = value.toInt()
            getLabelForIndex(index)
        }
    }

    private fun setupListeners(d: SystemUIDialog) {
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val index = value.roundToInt()
                updateStatusText(index)
            }
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                userTracking = true
            }
            override fun onStopTrackingTouch(slider: Slider) {
                userTracking = false
                controller.setIndex(slider.value.roundToInt())
            }
        })
        doneButton.setOnClickListener { d.dismiss() }
    }

    private fun updateStatusText(index: Int) {
        statusText.text = getLabelForIndex(index)
    }

    private fun getLabelForIndex(index: Int): String {
        if (index < 0 || index >= controller.scaleLabels.size) return ""
        val label = controller.scaleLabels[index]
        return if (label == "Off") {
             ctx.getString(R.string.quick_settings_animation_scale_off)
        } else {
            "Speed: $label"
        }
    }

    override fun onStop(d: SystemUIDialog) {
        controller.removeListener(this)
    }

    override fun onAnimationScaleChanged(scale: Float) {
        if (!userTracking) {
            val index = controller.getCurrentIndex()
            slider.value = index.toFloat()
            updateStatusText(index)
        }
    }
}
