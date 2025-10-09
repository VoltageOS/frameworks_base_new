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
+ * Takes a Compose Color and returns a vivid, saturated version.
+ * 
+ * @param targetSaturation Target saturation level. Default 0.9f for maximum vibrancy.
+ * @param targetLightness Target lightness for optimal vibrancy. Default 0.5f (mid-range).
+ * @param lightnessBlend How much to blend toward target lightness. 0.6f = 60% target, 40% original.
 */
@Composable
	
fun Color.boost(
    targetSaturation: Float = 0.9f,
    targetLightness: Float = 0.5f,
    lightnessBlend: Float = 0.6f
): Color {
    val argb = this.toArgb()

    val hsl = floatArrayOf(0f, 0f, 0f)

    ColorUtils.colorToHSL(argb, hsl)

    hsl[1] = targetSaturation
    
    val currentLightness = hsl[2]
    hsl[2] = currentLightness * (1f - lightnessBlend) + targetLightness * lightnessBlend
    
    hsl[2] = hsl[2].coerceIn(0.35f, 0.65f)

    val boostedArgb = ColorUtils.HSLToColor(hsl)

    return Color(boostedArgb)
}
