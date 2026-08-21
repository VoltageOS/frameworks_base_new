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

package com.android.systemui.volume.panel.component.appvolume.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.volume.panel.component.appvolume.shared.model.AppVolumePanelGlobalState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@SysUISingleton
class AppVolumePanelGlobalStateInteractor @Inject constructor() {

    private val mutableGlobalState = MutableStateFlow(AppVolumePanelGlobalState())
    val globalState: StateFlow<AppVolumePanelGlobalState> = mutableGlobalState.asStateFlow()

    fun setVisible(isVisible: Boolean) {
        mutableGlobalState.update { it.copy(isVisible = isVisible) }
    }
}
