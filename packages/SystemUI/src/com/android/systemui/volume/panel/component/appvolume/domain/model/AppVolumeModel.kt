/*
 * Copyright (C) 2026 Halcyon Project
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

package com.android.systemui.volume.panel.component.appvolume.domain.model

import com.android.systemui.common.shared.model.Icon

/** Represents the volume state of an application. */
data class AppVolumeModel(
    val packageName: String,
    val label: String,
    val icon: Icon.Loaded?,
    val volume: Float,
    val isMuted: Boolean,
    val isActive: Boolean,
)
