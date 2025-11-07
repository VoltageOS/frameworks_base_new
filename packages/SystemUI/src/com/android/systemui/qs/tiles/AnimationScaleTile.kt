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
package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.systemui.animation.Expandable
import com.android.systemui.common.slider.AnimationScaleInteractor
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

class AnimationScaleTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background private val backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val interactor: AnimationScaleInteractor
) : QSTileImpl<BooleanState>(
    host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
    statusBarStateController, activityStarter, qsLogger
) {

    companion object {
        const val TILE_SPEC = "animation_scale"
    }

    private val tileScope = CoroutineScope(Dispatchers.Main.immediate)
    private var listeningJob: Job? = null
    
    private val iconActive = ResourceIcon.get(R.drawable.ic_qs_animation_scale)
    private val iconInactive = ResourceIcon.get(R.drawable.ic_qs_animation_scale_off)

    override fun newTileState(): BooleanState = BooleanState().apply {
        handlesLongClick = true
    }

    override fun handleClick(expandable: Expandable?) {
        val currentLevel = interactor.getCurrentLevel()
        if (currentLevel > 0f) {
            interactor.setLevel(0f)
        } else {
            interactor.setLevel(0.4f)
        }
    }

    override fun handleLongClick(expandable: Expandable?) {
        val devSettingsEnabled = Settings.Global.getInt(
            mContext.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0
        ) == 1
        
        if (devSettingsEnabled) {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            mActivityStarter.postStartActivityDismissingKeyguard(intent, 0)
        } else {
            mHandler.post {
                android.widget.Toast.makeText(
                    mContext,
                    "Enable Developer Options in Settings first",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        val currentLevel = interactor.getCurrentLevel()
        val isActive = currentLevel > 0f

        state.value = isActive
        state.label = mContext.getString(R.string.quick_settings_animation_scale_label)
        
        state.icon = if (isActive) iconActive else iconInactive
        state.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        
        val labelValue = interactor.label.value
        state.secondaryLabel = labelValue.substringAfter("•", "").trim()
        
        state.contentDescription = if (isActive) {
            "$labelValue"
        } else {
            mContext.getString(R.string.quick_settings_animation_scale_off)
        }
    }
    
    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        if (listening) {
            listeningJob = tileScope.launch {
                interactor.label.collect {
                    refreshState()
                }
            }
        } else {
            listeningJob?.cancel()
        }
    }

    override fun getLongClickIntent(): Intent = 
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

    override fun getTileLabel(): CharSequence = 
        mContext.getString(R.string.quick_settings_animation_scale_label)

    override fun getMetricsCategory(): Int = MetricsEvent.QS_PANEL

    override fun handleDestroy() {
        super.handleDestroy()
        listeningJob?.cancel()
    }
}
