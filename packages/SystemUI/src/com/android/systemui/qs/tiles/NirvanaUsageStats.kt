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

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Calendar

object NirvanaUsageStats {
    private const val LOOKBACK_WINDOW_MILLIS = 24 * 60 * 60 * 1000L

    fun getStartOfTodayMillis(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun getDailyScreenTimeMillis(context: Context, usageStatsManager: UsageStatsManager): Long {
        val pm = context.packageManager
        val launcherPkg = resolveLauncherPackage(context)
        return queryTodayUsage(usageStatsManager).entries
            .filter { it.value > 0 }
            .filter { shouldIncludeApp(pm, it.key, launcherPkg) }
            .sumOf { it.value }
    }

    fun resolveLauncherPackage(context: Context): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager
            .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    fun shouldIncludeApp(pm: PackageManager, pkg: String, launcherPkg: String?): Boolean {
        return try {
            if (pkg == launcherPkg) return false
            if (pkg == "com.android.settings" || pkg == "com.android.systemui") return false
            pm.getLaunchIntentForPackage(pkg) != null
        } catch (e: Exception) {
            false
        }
    }

    fun queryTodayUsage(usageStatsManager: UsageStatsManager): Map<String, Long> {
        val start = getStartOfTodayMillis()
        val end = System.currentTimeMillis()
        if (end <= start) return emptyMap()

        val usageByPackage = HashMap<String, Long>()
        val activePackages = HashMap<String, Long>()

        fun addUsage(packageName: String, sessionEnd: Long) {
            val sessionStart = activePackages.remove(packageName) ?: return
            val boundedEnd = sessionEnd.coerceAtMost(end)
            if (boundedEnd <= sessionStart) return
            usageByPackage[packageName] =
                (usageByPackage[packageName] ?: 0L) + (boundedEnd - sessionStart)
        }

        fun closeAllActiveSessions(sessionEnd: Long) {
            if (activePackages.isEmpty()) return
            activePackages.keys.toList().forEach { packageName ->
                addUsage(packageName, sessionEnd)
            }
        }

        val queryStart = (start - LOOKBACK_WINDOW_MILLIS).coerceAtLeast(0L)
        val events = usageStatsManager.queryEvents(queryStart, end)
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val timestamp = event.timeStamp

            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                UsageEvents.Event.ACTIVITY_RESUMED,
                -> {
                    event.packageName?.let { packageName ->
                        activePackages.keys
                            .filter { it != packageName }
                            .toList()
                            .forEach { addUsage(it, timestamp) }
                        activePackages.putIfAbsent(packageName, timestamp.coerceAtLeast(start))
                    }
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                -> {
                    event.packageName?.let { packageName ->
                        addUsage(packageName, timestamp)
                    }
                }

                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    closeAllActiveSessions(timestamp)
                }
            }
        }

        closeAllActiveSessions(end)
        return usageByPackage
    }
}
