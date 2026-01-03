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
import com.android.systemui.statusbar.policy.NotificationSuppressController
import javax.inject.Inject

class NotificationSuppressTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background private val backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    private val controller: NotificationSuppressController,
    qsLogger: QSLogger
) : QSTileImpl<BooleanState>(
    host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
    statusBarStateController, activityStarter, qsLogger
), NotificationSuppressController.StateListener {

    companion object {
        const val TILE_SPEC = "notif_suppress"
    }

    private val iconActive = ResourceIcon.get(R.drawable.ic_qs_notification_suppress)
    private val iconInactive = ResourceIcon.get(R.drawable.ic_qs_notifications)
    private var label: String? = null

    override fun newTileState(): BooleanState {
        return BooleanState().apply {
            handlesLongClick = true
        }
    }

    override fun handleClick(expandable: Expandable?) {
        controller.expandDialog(expandable)
    }

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        val isSuppressed = controller.isSuppressed

        state.value = isSuppressed
        state.label = mContext.getString(R.string.quick_settings_notif_suppress_label)
        
        state.icon = if (isSuppressed) iconActive else iconInactive
        state.state = if (isSuppressed) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

        if (isSuppressed) {
            state.secondaryLabel = label ?: controller.getCurrentLabel()
        } else {
            state.secondaryLabel = null
        }
    }

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        if (listening) {
            controller.addListener(this)
        } else {
            controller.removeListener(this)
        }
    }

    override fun onStateChanged(suppressed: Boolean, label: String) {
        this.label = label
        refreshState()
    }

    override fun handleLongClick(expandable: Expandable?) {
        val infiniteIndex = controller.durations.indexOf(-1)
        if (infiniteIndex != -1) {
            if (controller.currentIndex == infiniteIndex && controller.isSuppressed) {
                controller.setDuration(0)
            } else {
                controller.setDuration(infiniteIndex)
            }
        }
     }
 
    override fun getLongClickIntent(): Intent? = null

    override fun getTileLabel(): CharSequence {
        return mContext.getString(R.string.quick_settings_notif_suppress_label)
    }

    override fun getMetricsCategory(): Int = MetricsEvent.QS_PANEL

    override fun handleDestroy() {
        super.handleDestroy()
        controller.removeListener(this)
    }
}
