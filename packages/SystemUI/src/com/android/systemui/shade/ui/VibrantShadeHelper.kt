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

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Helper object for managing vibrant shade elements setting.
 * When enabled, QS tiles, brightness slider, and other shade elements
 * will use vibrant accent colors instead of standard colors.
 */
object VibrantShadeHelper {
    
    private const val SETTING_KEY = "qs_vibrant_shade_elements"
    
    /**
     * Check if vibrant shade elements are enabled.
     * @param context Context to access ContentResolver
     * @return true if vibrant mode is enabled
     */
    @JvmStatic
    fun isEnabled(context: Context): Boolean {
        return Settings.System.getInt(
            context.contentResolver,
            SETTING_KEY,
            0
        ) == 1
    }
    
    /**
     * Enable or disable vibrant shade elements.
     * @param context Context to access ContentResolver
     * @param enabled Whether to enable vibrant mode
     */
    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        Settings.System.putInt(
            context.contentResolver,
            SETTING_KEY,
            if (enabled) 1 else 0
        )
    }
}

/**
 * Composable function to observe vibrant shade elements setting.
 * Use this in any Compose UI that needs to react to the setting.
 * 
 * @return true if vibrant mode is enabled
 */
@Composable
fun isVibrantShadeEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(VibrantShadeHelper.isEnabled(context))
    }

    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = VibrantShadeHelper.isEnabled(context)
            }
        }

        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor("qs_vibrant_shade_elements"),
            false,
            observer
        )

        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    return enabled
}
