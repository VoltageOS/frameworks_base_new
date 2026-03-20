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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.view.View
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
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
import java.util.Calendar
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
    private val packageManager = mContext.packageManager

    override fun newTileState(): BooleanState = BooleanState()

    override fun handleSetListening(listening: Boolean) {
        if (listening) {
            mContext.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(KEY_MANUAL_ACTIVE), false, settingsObserver
            )
            mContext.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(KEY_SCHEDULE_ENABLED), false, settingsObserver
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
        val currentState = isManualActive()
        Settings.Secure.putInt(mContext.contentResolver, KEY_MANUAL_ACTIVE, if (currentState) 0 else 1)
        
        val intent = Intent(ACTION_UPDATE_NIRVANA_SCHEDULE)
        intent.setPackage("com.android.settings")
        mContext.sendBroadcast(intent)
        
        refreshState()
    }

    override fun getTileLabel(): CharSequence {
        return mContext.getString(R.string.quick_settings_nirvana_label)
    }

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        val isManual = isManualActive()
        val isScheduled = isScheduleActive()
        
        val isActive = isManual || isScheduled

        state.value = isActive
        state.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        state.label = mContext.getString(R.string.quick_settings_nirvana_label)
        state.icon = ResourceIcon.get(R.drawable.ic_qs_nirvana)
        
        val screenTime = getDailyScreenTime()
        state.secondaryLabel = formatDuration(screenTime)
    }

    override fun getMetricsCategory(): Int = MetricsEvent.VOLTAGE

    private fun isManualActive(): Boolean {
        return Settings.Secure.getInt(mContext.contentResolver, KEY_MANUAL_ACTIVE, 0) == 1
    }

    private fun isScheduleActive(): Boolean {
        val enabled = Settings.Secure.getInt(mContext.contentResolver, KEY_SCHEDULE_ENABLED, 0) == 1
        if (!enabled) return false

        val start = Settings.Secure.getInt(mContext.contentResolver, KEY_START_TIME, 540) // 9:00 AM
        val end = Settings.Secure.getInt(mContext.contentResolver, KEY_END_TIME, 1020) // 5:00 PM
        
        val now = Calendar.getInstance()
        val currentMinutes = (now.get(Calendar.HOUR_OF_DAY) * 60) + now.get(Calendar.MINUTE)

        return if (end < start) {
            currentMinutes >= start || currentMinutes < end
        } else {
            currentMinutes in start until end
        }
    }

    /**
     * Calculates daily screen time matching the filtering logic in NirvanaStatsFragment.
     * Filters out: Launcher, Settings, SystemUI, and non-updated System Apps.
     */
    private fun getDailyScreenTime(): Long {
        try {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            val end = System.currentTimeMillis()

            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val launcherPkg = resolveInfo?.activityInfo?.packageName

            val stats = usageStatsManager.queryAndAggregateUsageStats(start, end)
            var totalTime = 0L

            stats.forEach { (pkg, stat) ->
                if (stat.totalTimeInForeground > 0) {
                    if (shouldIncludeApp(pkg, launcherPkg)) {
                        totalTime += stat.totalTimeInForeground
                    }
                }
            }
            
            return totalTime
        } catch (e: Exception) {
            return 0L
        }
    }

    private fun shouldIncludeApp(pkg: String, launcherPkg: String?): Boolean {
        if (pkg == launcherPkg) return false
        if (pkg == "com.android.settings" || pkg == "com.android.systemui") return false

        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                           (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            !isSystem
        } catch (e: Exception) {
            false
        }
    }

    private fun formatDuration(millis: Long): String {
        if (millis < 60000) return "< 1m"
        val h = millis / 3600000
        val m = (millis % 3600000) / 60000
        return if (h > 0) {
            String.format("%dh %02dm", h, m)
        } else {
            String.format("%dm", m)
        }
    }

    companion object {
        const val TILE_SPEC = "nirvana"
        
        private const val KEY_MANUAL_ACTIVE = "nirvana_mode_manual_active"
        private const val KEY_SCHEDULE_ENABLED = "nirvana_mode_schedule_enabled"
        private const val KEY_START_TIME = "nirvana_mode_start_time"
        private const val KEY_END_TIME = "nirvana_mode_end_time"
        private const val ACTION_UPDATE_NIRVANA_SCHEDULE = "com.power.hub.action.UPDATE_NIRVANA_SCHEDULE"
    }
}
