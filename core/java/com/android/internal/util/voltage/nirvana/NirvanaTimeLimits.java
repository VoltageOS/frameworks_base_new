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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.SuspendDialogInfo;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import java.time.Duration;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NirvanaTimeLimits {
    private NirvanaTimeLimits() {}

    private static final String TAG = "NirvanaTimeLimits";

    public interface DialogInfoFactory {
        SuspendDialogInfo forPackage(Context context, String packageName);
    }

    public static Map<String, Integer> getLimits(ContentResolver resolver) {
        String raw = Settings.Secure.getStringForUser(resolver,
                NirvanaConstants.KEY_LIMITS, UserHandle.USER_CURRENT);
        if (raw == null || raw.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Integer> limits = new LinkedHashMap<>();
        for (String entry : raw.split(",")) {
            String[] parts = entry.split(":");
            if (parts.length != 2) {
                continue;
            }
            String pkg = parts[0].trim();
            int minutes;
            try {
                minutes = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (pkg.isEmpty() || minutes <= 0) {
                continue;
            }
            limits.put(pkg, minutes);
        }
        return limits;
    }

    public static int getLimitMinutes(ContentResolver resolver, String pkg) {
        Integer value = getLimits(resolver).get(pkg);
        return value == null ? 0 : value;
    }

    private static void saveLimits(ContentResolver resolver, Map<String, Integer> limits) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : limits.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(entry.getKey()).append(':').append(entry.getValue());
        }
        Settings.Secure.putStringForUser(resolver,
                NirvanaConstants.KEY_LIMITS, sb.toString(), UserHandle.USER_CURRENT);
    }

    public static void setLimit(Context context, String pkg, int minutes,
            DialogInfoFactory factory) {
        ContentResolver resolver = context.getContentResolver();
        Map<String, Integer> limits = new LinkedHashMap<>(getLimits(resolver));
        if (minutes <= 0) {
            limits.remove(pkg);
        } else {
            limits.put(pkg, minutes);
        }
        saveLimits(resolver, limits);
        if (minutes <= 0) {
            releaseForAllUsers(context, pkg);
        }
        refresh(context, factory);
    }

    public static void clearAllLimits(Context context, DialogInfoFactory factory) {
        ContentResolver resolver = context.getContentResolver();
        for (String pkg : getLimits(resolver).keySet()) {
            releaseForAllUsers(context, pkg);
        }
        saveLimits(resolver, new LinkedHashMap<>());
        refresh(context, factory);
    }

    public static void onBoot(Context context, DialogInfoFactory factory) {
        rollBlockedStateIfNeeded(context);
        refresh(context, factory);
    }

    public static void onPackagesChanged(Context context, DialogInfoFactory factory) {
        refresh(context, factory);
    }

    public static void onDailyReset(Context context, DialogInfoFactory factory) {
        releaseBlocked(context);
        clearBlockedState(context);
        refresh(context, factory);
    }

    public static void onLimitReached(Context context, String pkg, int userId,
            DialogInfoFactory factory) {
        rollBlockedStateIfNeeded(context);
        if (getLimitMinutes(context.getContentResolver(), pkg) <= 0) {
            return;
        }
        if (isOwnedByNirvana(context, pkg)) {
            return;
        }
        if (suspendForLimit(context, pkg, userId, factory)) {
            Set<String> blocked = new HashSet<>(getBlockedToday(context));
            blocked.add(userId + "|" + pkg);
            saveBlockedToday(context, blocked);
        }
        scheduleDailyReset(context);
    }

    public static void refresh(Context context, DialogInfoFactory factory) {
        rollBlockedStateIfNeeded(context);
        ContentResolver resolver = context.getContentResolver();
        Map<String, Integer> limits = pruneUninstalled(context, getLimits(resolver));
        unregisterAllObservers(context);
        if (limits.isEmpty()) {
            cancelDailyReset(context);
            return;
        }
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager == null) {
            return;
        }
        boolean nirvanaActive = NirvanaState.shouldBeActive(resolver);
        Set<String> nirvanaApps = NirvanaState.getSelectedApps(resolver);
        Set<String> blocked = new HashSet<>(getBlockedToday(context));
        Set<String> observers = new HashSet<>();
        List<UserHandle> profiles = userManager.getUserProfiles();
        for (UserHandle userHandle : profiles) {
            int userId = userHandle.getIdentifier();
            Context userContext = contextForUser(context, userHandle);
            if (userContext == null) {
                continue;
            }
            UsageStatsManager usageStatsManager =
                    userContext.getSystemService(UsageStatsManager.class);
            if (usageStatsManager == null) {
                continue;
            }
            Map<String, Long> usage =
                    NirvanaUsage.queryTodaySummary(usageStatsManager).usageByPackage;
            for (Map.Entry<String, Integer> limit : limits.entrySet()) {
                String pkg = limit.getKey();
                int minutes = limit.getValue();
                if (!isInstalledForUser(userContext, pkg)) {
                    continue;
                }
                if (nirvanaActive && nirvanaApps.contains(pkg)) {
                    continue;
                }
                String key = userId + "|" + pkg;
                long limitMillis = minutes * 60000L;
                Long usedMillis = usage.get(pkg);
                long used = usedMillis == null ? 0L : usedMillis;
                if (blocked.contains(key) || used >= limitMillis) {
                    if (suspendForLimit(context, pkg, userId, factory)) {
                        blocked.add(key);
                    }
                    continue;
                }
                int observerId = observerIdFor(userId, pkg);
                try {
                    usageStatsManager.registerAppUsageLimitObserver(observerId,
                            new String[]{pkg}, Duration.ofMillis(limitMillis),
                            Duration.ofMillis(used),
                            buildCallbackIntent(context, pkg, userId, observerId));
                    observers.add(userId + "|" + pkg + "|" + observerId);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to register usage limit observer for " + pkg, e);
                }
            }
        }
        saveBlockedToday(context, blocked);
        saveObservers(context, observers);
        scheduleDailyReset(context);
    }

    private static boolean isOwnedByNirvana(Context context, String pkg) {
        ContentResolver resolver = context.getContentResolver();
        return NirvanaState.shouldBeActive(resolver)
                && NirvanaState.getSelectedApps(resolver).contains(pkg);
    }

    private static boolean suspendForLimit(Context context, String pkg, int userId,
            DialogInfoFactory factory) {
        if (isOwnedByNirvana(context, pkg)) {
            return false;
        }
        try {
            if (NirvanaSuspension.isPackageSuspendedForUser(context, pkg, userId)) {
                return NirvanaSuspension.isSuspendedBySystemUi(context, pkg, userId);
            }
            SuspendDialogInfo info = factory == null ? null : factory.forPackage(context, pkg);
            NirvanaSuspension.applyBatchSuspension(context,
                    new String[]{pkg}, true, userId, info);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to suspend " + pkg + " for user " + userId, e);
            return false;
        }
    }

    private static void releaseBlocked(Context context) {
        for (String entry : getBlockedRaw(context)) {
            String[] parts = entry.split("\\|");
            if (parts.length != 2) {
                continue;
            }
            int userId;
            try {
                userId = Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            releaseForUser(context, parts[1], userId);
        }
    }

    private static void releaseForAllUsers(Context context, String pkg) {
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager != null) {
            for (UserHandle userHandle : userManager.getUserProfiles()) {
                releaseForUser(context, pkg, userHandle.getIdentifier());
            }
        }
        Set<String> blocked = new HashSet<>();
        for (String entry : getBlockedRaw(context)) {
            if (!entry.endsWith("|" + pkg)) {
                blocked.add(entry);
            }
        }
        saveBlockedToday(context, blocked);
    }

    private static void releaseForUser(Context context, String pkg, int userId) {
        if (isOwnedByNirvana(context, pkg)) {
            return;
        }
        try {
            if (NirvanaSuspension.isPackageSuspendedForUser(context, pkg, userId)
                    && NirvanaSuspension.isSuspendedBySystemUi(context, pkg, userId)) {
                NirvanaSuspension.applyBatchSuspension(context,
                        new String[]{pkg}, false, userId, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to unsuspend " + pkg + " for user " + userId, e);
        }
    }

    private static Map<String, Integer> pruneUninstalled(Context context,
            Map<String, Integer> limits) {
        if (limits.isEmpty()) {
            return limits;
        }
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager == null) {
            return limits;
        }
        Map<String, Integer> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : limits.entrySet()) {
            boolean installed = false;
            for (UserHandle userHandle : userManager.getUserProfiles()) {
                Context userContext = contextForUser(context, userHandle);
                if (isInstalledForUser(userContext, entry.getKey())) {
                    installed = true;
                    break;
                }
            }
            if (installed) {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        if (kept.size() != limits.size()) {
            saveLimits(context.getContentResolver(), kept);
            Set<String> blocked = new HashSet<>();
            for (String entry : getBlockedRaw(context)) {
                for (String pkg : kept.keySet()) {
                    if (entry.endsWith("|" + pkg)) {
                        blocked.add(entry);
                        break;
                    }
                }
            }
            saveBlockedToday(context, blocked);
        }
        return kept;
    }

    private static void unregisterAllObservers(Context context) {
        Set<String> saved = getObservers(context);
        if (saved.isEmpty()) {
            return;
        }
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager == null) {
            saveObservers(context, new HashSet<>());
            return;
        }
        for (String entry : saved) {
            String[] parts = entry.split("\\|");
            if (parts.length != 3) {
                continue;
            }
            int userId;
            int observerId;
            try {
                userId = Integer.parseInt(parts[0]);
                observerId = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            Context userContext = contextForUser(context, UserHandle.of(userId));
            if (userContext == null) {
                continue;
            }
            UsageStatsManager usageStatsManager =
                    userContext.getSystemService(UsageStatsManager.class);
            if (usageStatsManager == null) {
                continue;
            }
            try {
                usageStatsManager.unregisterAppUsageLimitObserver(observerId);
            } catch (Exception ignored) {
            }
        }
        saveObservers(context, new HashSet<>());
    }

    private static int observerIdFor(int userId, String pkg) {
        return NirvanaConstants.OBSERVER_ID_BASE
                + (((userId * 31) + pkg.hashCode()) & NirvanaConstants.OBSERVER_ID_MASK);
    }

    private static PendingIntent buildCallbackIntent(Context context, String pkg,
            int userId, int observerId) {
        Intent intent = new Intent(NirvanaConstants.ACTION_LIMIT_REACHED);
        intent.setPackage(NirvanaConstants.TARGET_PACKAGE);
        intent.setData(Uri.parse("nirvana-limit://" + userId + "/" + pkg));
        intent.putExtra(NirvanaConstants.EXTRA_LIMIT_PACKAGE, pkg);
        intent.putExtra(NirvanaConstants.EXTRA_LIMIT_USER, userId);
        return PendingIntent.getBroadcast(context, observerId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void scheduleDailyReset(Context context) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                nextMidnight(), dailyResetIntent(context));
    }

    private static void cancelDailyReset(Context context) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }
        alarmManager.cancel(dailyResetIntent(context));
    }

    private static PendingIntent dailyResetIntent(Context context) {
        Intent intent = new Intent(NirvanaConstants.ACTION_DAILY_RESET);
        intent.setPackage(NirvanaConstants.TARGET_PACKAGE);
        return PendingIntent.getBroadcast(context,
                NirvanaConstants.DAILY_RESET_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static long nextMidnight() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 5);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        return calendar.getTimeInMillis();
    }

    private static void rollBlockedStateIfNeeded(Context context) {
        ContentResolver resolver = context.getContentResolver();
        int storedDay = Settings.Secure.getIntForUser(resolver,
                NirvanaConstants.KEY_BLOCKED_DAY, 0, UserHandle.USER_CURRENT);
        if (storedDay != currentDayStamp()) {
            releaseBlocked(context);
            clearBlockedState(context);
        }
    }

    private static int currentDayStamp() {
        Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR);
    }

    private static Set<String> getBlockedToday(Context context) {
        ContentResolver resolver = context.getContentResolver();
        int storedDay = Settings.Secure.getIntForUser(resolver,
                NirvanaConstants.KEY_BLOCKED_DAY, 0, UserHandle.USER_CURRENT);
        if (storedDay != currentDayStamp()) {
            return new HashSet<>();
        }
        return getBlockedRaw(context);
    }

    private static Set<String> getBlockedRaw(Context context) {
        String raw = Settings.Secure.getStringForUser(context.getContentResolver(),
                NirvanaConstants.KEY_BLOCKED_TODAY, UserHandle.USER_CURRENT);
        if (raw == null || raw.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> out = new HashSet<>();
        for (String entry : raw.split(",")) {
            String key = entry.trim();
            if (!key.isEmpty()) {
                out.add(key);
            }
        }
        return out;
    }

    private static void saveBlockedToday(Context context, Set<String> blocked) {
        StringBuilder sb = new StringBuilder();
        for (String key : blocked) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(key);
        }
        ContentResolver resolver = context.getContentResolver();
        Settings.Secure.putStringForUser(resolver,
                NirvanaConstants.KEY_BLOCKED_TODAY, sb.toString(), UserHandle.USER_CURRENT);
        Settings.Secure.putIntForUser(resolver,
                NirvanaConstants.KEY_BLOCKED_DAY, currentDayStamp(), UserHandle.USER_CURRENT);
    }

    private static void clearBlockedState(Context context) {
        ContentResolver resolver = context.getContentResolver();
        Settings.Secure.putStringForUser(resolver,
                NirvanaConstants.KEY_BLOCKED_TODAY, "", UserHandle.USER_CURRENT);
        Settings.Secure.putIntForUser(resolver,
                NirvanaConstants.KEY_BLOCKED_DAY, currentDayStamp(), UserHandle.USER_CURRENT);
    }

    private static Set<String> getObservers(Context context) {
        String raw = Settings.Secure.getStringForUser(context.getContentResolver(),
                NirvanaConstants.KEY_OBSERVERS, UserHandle.USER_CURRENT);
        if (raw == null || raw.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> out = new HashSet<>();
        for (String entry : raw.split(",")) {
            String key = entry.trim();
            if (!key.isEmpty()) {
                out.add(key);
            }
        }
        return out;
    }

    private static void saveObservers(Context context, Set<String> observers) {
        StringBuilder sb = new StringBuilder();
        for (String key : observers) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(key);
        }
        Settings.Secure.putStringForUser(context.getContentResolver(),
                NirvanaConstants.KEY_OBSERVERS, sb.toString(), UserHandle.USER_CURRENT);
    }

    private static Context contextForUser(Context context, UserHandle userHandle) {
        try {
            if (userHandle.getIdentifier() == UserHandle.myUserId()) {
                return context;
            }
            return context.createContextAsUser(userHandle, 0);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isInstalledForUser(Context userContext, String pkg) {
        if (userContext == null) {
            return false;
        }
        try {
            userContext.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static Map<String, Long> queryUsageForToday(Context context, int userId) {
        try {
            UserHandle handle = UserHandle.of(userId);
            Context userContext = contextForUser(context, handle);
            if (userContext == null) {
                return new HashMap<>();
            }
            UsageStatsManager manager = userContext.getSystemService(UsageStatsManager.class);
            if (manager == null) {
                return new HashMap<>();
            }
            return new HashMap<>(NirvanaUsage.queryTodaySummary(manager).usageByPackage);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
