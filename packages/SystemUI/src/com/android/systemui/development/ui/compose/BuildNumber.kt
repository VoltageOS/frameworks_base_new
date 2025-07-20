/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.development.ui.compose

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.communal.ui.compose.extensions.detectLongPressGesture
import com.android.systemui.development.ui.viewmodel.BuildNumberViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsDataUsageViewModel
import com.android.systemui.res.R

@Composable
fun BuildNumber(
    viewModelFactory: BuildNumberViewModel.Factory,
    textColor: Color,
    modifier: Modifier = Modifier,
    dataUsageViewModel: FooterActionsDataUsageViewModel? = null,
) {
    val viewModel = rememberViewModel(traceName = "BuildNumber") { viewModelFactory.create() }
    val buildNumber = viewModel.buildNumber

    // If data usage is enabled and available, show it instead of build number
    if (dataUsageViewModel != null) {
        val dataUsageText = dataUsageViewModel.dataUsageText.collectAsStateWithLifecycle().value
        val isVisible = dataUsageViewModel.isVisible.collectAsStateWithLifecycle().value
        
        if (isVisible && dataUsageText != null) {
            val haptics = LocalHapticFeedback.current
            
            Text(
                text = dataUsageText,
                modifier =
                    modifier
                        .focusable()
                        .wrapContentWidth()
                        .padding(start = 8.dp)
                        .combinedClickable(
                            onClick = { dataUsageViewModel.onDataUsageClick() },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                dataUsageViewModel.onDataUsageLongClick()
                            }
                        )
                        .semantics {
                            onLongClick("Open data usage settings") {
                                dataUsageViewModel.onDataUsageLongClick()
                                true
                            }
                        }
                        .basicMarquee(iterations = 1, initialDelayMillis = 2000)
                        .minimumInteractiveComponentSize(),
                color = textColor,
                maxLines = 1,
            )
            return
        }
    }

    // Show nothing (build number is always null)
    Spacer(modifier)
}
