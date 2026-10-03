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
package com.android.internal.util.voltage.nirvana;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public final class NirvanaUsage {
    private NirvanaUsage() {}

    private static final long LOOKBACK_WINDOW_MILLIS = 24L * 60L * 60L * 1000L;

    public static final class DailySummary {
        public final Map<String, Long> usageByPackage;
        public final Map<String, Integer> notificationCountByPackage;
        public final int unlockCount;

        public DailySummary(Map<String, Long> usageByPackage,
                Map<String, Integer> notificationCountByPackage, int unlockCount) {
            this.usageByPackage = usageByPackage;
            this.notificationCountByPackage = notificationCountByPackage;
            this.unlockCount = unlockCount;
        }
    }

    public static long getStartOfTodayMillis() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    public static DailySummary queryTodaySummary(UsageStatsManager usageStatsManager) {
        return querySummary(usageStatsManager, getStartOfTodayMillis(), System.currentTimeMillis());
    }

    public static DailySummary querySummary(
            UsageStatsManager usageStatsManager, long start, long end) {
        if (usageStatsManager == null || end <= start) {
            return new DailySummary(Collections.emptyMap(), Collections.emptyMap(), 0);
        }
        Map<String, Long> usageByPackage = new HashMap<>();
        Map<String, Integer> notificationCountByPackage = new HashMap<>();
        HashSet<String> activeTokens = new HashSet<>();
        Map<String, Integer> activeTokenCount = new HashMap<>();
        Map<String, Long> sessionStartByPackage = new HashMap<>();
        HashSet<String> everResumed = new HashSet<>();
        boolean[] keyguardShowing = new boolean[]{true};
        int[] unlockCount = new int[]{0};

        long queryStart = Math.max(0L, start - LOOKBACK_WINDOW_MILLIS);
        UsageEvents events = usageStatsManager.queryEvents(queryStart, end);
        if (events == null) {
            return new DailySummary(usageByPackage, notificationCountByPackage, 0);
        }
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            long timestamp = event.getTimeStamp();
            int type = event.getEventType();
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                String pkg = event.getPackageName();
                if (pkg != null) {
                    everResumed.add(pkg);
                    String token = pkg + "|" + event.getClassName() + "|" + event.getInstanceId();
                    if (activeTokens.add(token)) {
                        int count = activeTokenCount.containsKey(pkg)
                                ? activeTokenCount.get(pkg) + 1 : 1;
                        activeTokenCount.put(pkg, count);
                        if (count == 1) {
                            sessionStartByPackage.put(pkg, timestamp);
                        }
                    }
                }
            } else if (type == UsageEvents.Event.ACTIVITY_PAUSED
                    || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                String pkg = event.getPackageName();
                if (pkg != null) {
                    String token = pkg + "|" + event.getClassName() + "|" + event.getInstanceId();
                    if (!activeTokens.remove(token)) {
                        if (!activeTokenCount.containsKey(pkg) && everResumed.add(pkg)) {
                            accrue(usageByPackage, pkg, start, timestamp, start, end);
                        }
                    } else {
                        int count = activeTokenCount.containsKey(pkg)
                                ? activeTokenCount.get(pkg) - 1 : 0;
                        if (count <= 0) {
                            activeTokenCount.remove(pkg);
                            Long begin = sessionStartByPackage.remove(pkg);
                            if (begin != null) {
                                accrue(usageByPackage, pkg, begin, timestamp, start, end);
                            }
                        } else {
                            activeTokenCount.put(pkg, count);
                        }
                    }
                }
            } else if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                    || type == UsageEvents.Event.DEVICE_SHUTDOWN) {
                if (!sessionStartByPackage.isEmpty() || !activeTokens.isEmpty()) {
                    for (Map.Entry<String, Long> entry : sessionStartByPackage.entrySet()) {
                        accrue(usageByPackage, entry.getKey(), entry.getValue(), timestamp,
                                start, end);
                    }
                    sessionStartByPackage.clear();
                    activeTokenCount.clear();
                    activeTokens.clear();
                }
            } else if (type == UsageEvents.Event.KEYGUARD_SHOWN) {
                keyguardShowing[0] = true;
            } else if (type == UsageEvents.Event.KEYGUARD_HIDDEN) {
                if (keyguardShowing[0]) {
                    keyguardShowing[0] = false;
                    if (timestamp >= start) {
                        unlockCount[0]++;
                    }
                }
            } else if (type == UsageEvents.Event.NOTIFICATION_INTERRUPTION) {
                String pkg = event.getPackageName();
                if (pkg != null && timestamp >= start) {
                    Integer count = notificationCountByPackage.get(pkg);
                    notificationCountByPackage.put(pkg, count == null ? 1 : count + 1);
                }
            }
        }
        for (Map.Entry<String, Long> entry : sessionStartByPackage.entrySet()) {
            accrue(usageByPackage, entry.getKey(), entry.getValue(), end, start, end);
        }
        return new DailySummary(usageByPackage, notificationCountByPackage, unlockCount[0]);
    }

    private static void accrue(Map<String, Long> usageByPackage, String pkg,
            long sessionBegin, long sessionEnd, long start, long end) {
        long boundedStart = Math.max(sessionBegin, start);
        long boundedEnd = Math.min(sessionEnd, end);
        if (boundedEnd <= boundedStart) {
            return;
        }
        Long current = usageByPackage.get(pkg);
        usageByPackage.put(pkg, (current == null ? 0L : current) + (boundedEnd - boundedStart));
    }

    public static String resolveLauncherPackage(Context context) {
        try {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            if (context.getPackageManager().resolveActivity(homeIntent,
                    PackageManager.MATCH_DEFAULT_ONLY) == null) {
                return null;
            }
            return context.getPackageManager().resolveActivity(homeIntent,
                    PackageManager.MATCH_DEFAULT_ONLY).activityInfo.packageName;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean shouldIncludeApp(PackageManager pm, String pkg, String launcherPkg) {
        try {
            if (pkg == null) {
                return false;
            }
            if (pkg.equals(launcherPkg)) {
                return false;
            }
            if ("com.android.settings".equals(pkg) || "com.android.systemui".equals(pkg)) {
                return false;
            }
            return pm.getLaunchIntentForPackage(pkg) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static long getDailyScreenTimeMillis(Context context,
            UsageStatsManager usageStatsManager) {
        try {
            PackageManager pm = context.getPackageManager();
            String launcherPkg = resolveLauncherPackage(context);
            DailySummary summary = queryTodaySummary(usageStatsManager);
            long total = 0L;
            for (Map.Entry<String, Long> entry : summary.usageByPackage.entrySet()) {
                if (entry.getValue() != null
                        && entry.getValue() > 0
                        && shouldIncludeApp(pm, entry.getKey(), launcherPkg)) {
                    total += entry.getValue();
                }
            }
            return total;
        } catch (Exception e) {
            return 0L;
        }
    }

    public static String formatDurationShort(long millis) {
        if (millis < 60000) {
            return "0m";
        }
        long h = millis / 3600000;
        long m = (millis % 3600000) / 60000;
        if (h > 0) {
            return h + "h " + m + "m";
        }
        return m + "m";
    }
}
