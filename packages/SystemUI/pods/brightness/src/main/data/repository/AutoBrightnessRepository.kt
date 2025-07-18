/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.brightness.data.repository

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
public class AutoBrightnessRepository
@Inject
constructor(
    private val contentResolver: ContentResolver,
    @Application applicationScope: CoroutineScope,
    @Background backgroundContext: CoroutineContext,
) {
    public val isAutoBrightnessEnabled: StateFlow<Boolean> =
        callbackFlow {
                val observer =
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(isEnabled())
                        }
                    }
                contentResolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false,
                    observer,
                    UserHandle.USER_ALL,
                )
                trySend(isEnabled())
                awaitClose { contentResolver.unregisterContentObserver(observer) }
            }
            .conflate()
            .flowOn(backgroundContext)
            .stateIn(applicationScope, SharingStarted.WhileSubscribed(), isEnabled())

    public fun toggleBrightnessMode() {
        Settings.System.putIntForUser(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (isEnabled()) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            } else {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            },
            UserHandle.USER_CURRENT,
        )
    }

    private fun isEnabled(): Boolean {
        return Settings.System.getIntForUser(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            UserHandle.USER_CURRENT,
        ) != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
    }
}
