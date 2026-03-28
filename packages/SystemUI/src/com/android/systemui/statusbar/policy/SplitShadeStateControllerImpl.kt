/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.content.res.Configuration
import android.content.res.Resources
import android.provider.Settings
import android.os.UserHandle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.res.R
import javax.inject.Inject

/**
 * Source of truth for split shade state: should or should not use split shade based on orientation,
 * screen width, flags, and user preference.
 */
@SysUISingleton
class SplitShadeStateControllerImpl @Inject constructor(
    private val context: Context,
    private val featureFlags: FeatureFlags
) : SplitShadeStateController {

    @Deprecated(
        message = "This is deprecated, please use ShadeInteractor#isSplitShade instead",
        replaceWith =
            ReplaceWith(
                "shadeInteractor.isSplitShade",
                "com.android.systemui.shade.domain.interactor.ShadeInteractor",
            ),
    )
    override fun shouldUseSplitNotificationShade(resources: Resources): Boolean {
        val splitShadeUserPreference = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.QS_SPLIT_SHADE,
            -1,
            UserHandle.USER_CURRENT
        )

        val defaultBehavior = resources.getBoolean(R.bool.config_use_split_notification_shade) ||
            (featureFlags.isEnabled(Flags.LOCKSCREEN_ENABLE_LANDSCAPE) &&
                resources.getBoolean(R.bool.force_config_use_split_notification_shade))

        return when (splitShadeUserPreference) {
            0 -> false
            1 -> resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || defaultBehavior
            else -> defaultBehavior
        }
    }
}
