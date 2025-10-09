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

package com.android.systemui.shade.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * Takes a Compose Color and returns a more vibrant version by increasing its saturation.
 * @param boostFactor How much to increase saturation by. 0.2f means 20% increase.
 *                    Values should be between 0.0f and 1.0f.
 */
@Composable
fun Color.boost(boostFactor: Float = 0.2f): Color {
    val argb = this.toArgb()

    val hsl = floatArrayOf(0f, 0f, 0f)

    ColorUtils.colorToHSL(argb, hsl)

    hsl[1] = (hsl[1] + boostFactor).coerceIn(0.0f, 1.0f)

    val boostedArgb = ColorUtils.HSLToColor(hsl)

    return Color(boostedArgb)
}
