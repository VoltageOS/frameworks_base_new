/*
 * Copyright (C) 2026 VoltageOS
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

import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.internal.util.voltage.nirvana.NirvanaConstants
import com.android.internal.util.voltage.nirvana.NirvanaState
import com.android.internal.util.voltage.nirvana.NirvanaUsage
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import javax.inject.Inject

class NirvanaTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger
) : QSTileImpl<BooleanState>(
    host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
    statusBarStateController, activityStarter, qsLogger
) {
    private val settingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            refreshState()
        }
    }

    private val usageStatsManager = mContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    override fun newTileState(): BooleanState = BooleanState()

    override fun handleSetListening(listening: Boolean) {
        if (listening) {
            mContext.contentResolver.registerContentObserver(
                android.provider.Settings.Secure.getUriFor(NirvanaConstants.KEY_MANUAL_ACTIVE), false, settingsObserver
            )
            mContext.contentResolver.registerContentObserver(
                android.provider.Settings.Secure.getUriFor(NirvanaConstants.KEY_SCHEDULE_ENABLED), false, settingsObserver
            )
            refreshState()
        } else {
            mContext.contentResolver.unregisterContentObserver(settingsObserver)
        }
    }

    override fun getLongClickIntent(): Intent? {
        return Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName("com.android.settings", "com.android.settings.SubSettings")
            putExtra(":settings:show_fragment", "com.power.hub.fragments.NirvanaModeSettings")
            putExtra(":settings:show_fragment_title", mContext.getString(R.string.quick_settings_nirvana_label))
        }
    }

    override fun handleClick(expandable: Expandable?) {
        NirvanaState.toggleManualActive(mContext)
        refreshState()
    }

    override fun getTileLabel(): CharSequence {
        return mContext.getString(R.string.quick_settings_nirvana_label)
    }

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        val isActive = NirvanaState.shouldBeActive(mContext.contentResolver)
        state.value = isActive
        state.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        state.label = mContext.getString(R.string.quick_settings_nirvana_label)
        state.icon = ResourceIcon.get(R.drawable.ic_qs_nirvana)
        state.secondaryLabel = NirvanaUsage.formatDurationShort(
            NirvanaUsage.getDailyScreenTimeMillis(mContext, usageStatsManager)
        )
    }

    override fun getMetricsCategory(): Int = MetricsEvent.VOLTAGE

    companion object {
        const val TILE_SPEC = "nirvana"
    }
}
