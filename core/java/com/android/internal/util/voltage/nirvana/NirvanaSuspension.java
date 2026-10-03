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

import android.app.ActivityThread;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.SuspendDialogInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NirvanaSuspension {
    private NirvanaSuspension() {}

    private static final String TAG = "NirvanaSuspension";

    public static boolean isPackageSuspendedForUser(Context context, String pkg, int userId) {
        try {
            IPackageManager pm = ActivityThread.getPackageManager();
            if (pm == null) {
                return false;
            }
            return pm.isPackageSuspendedForUser(pkg, userId);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isSuspendedBySystemUi(Context context, String pkg, int userId) {
        return NirvanaConstants.SYSTEMUI_SUSPENDER.equals(getSuspendingPackage(context, pkg, userId));
    }

    public static boolean isSuspendedByAnyNirvana(Context context, String pkg, int userId) {
        String owner = getSuspendingPackage(context, pkg, userId);
        return NirvanaConstants.SYSTEMUI_SUSPENDER.equals(owner)
                || NirvanaConstants.LEGACY_SUSPENDER.equals(owner);
    }

    public static String getSuspendingPackage(Context context, String pkg, int userId) {
        try {
            IPackageManager pm = ActivityThread.getPackageManager();
            if (pm == null) {
                return null;
            }
            return pm.getSuspendingPackage(pkg, userId);
        } catch (Exception e) {
            return null;
        }
    }

    public static Set<String> getTrackedState(ContentResolver resolver) {
        String raw = Settings.Secure.getStringForUser(resolver,
                NirvanaConstants.KEY_TRACKED_STATE, UserHandle.USER_CURRENT);
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

    public static void saveTrackedState(ContentResolver resolver, Set<String> state) {
        StringBuilder sb = new StringBuilder();
        for (String key : state) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(key);
        }
        Settings.Secure.putStringForUser(resolver,
                NirvanaConstants.KEY_TRACKED_STATE, sb.toString(), UserHandle.USER_CURRENT);
    }

    public static void applyBatchSuspension(Context context, String[] pkgs,
            boolean suspend, int userId, SuspendDialogInfo dialogInfo) {
        try {
            String[] finalPkgs = pkgs;
            if (suspend) {
                ArrayList<String> filtered = new ArrayList<>();
                for (String pkg : pkgs) {
                    if (!isPackageSuspendedForUser(context, pkg, userId)) {
                        filtered.add(pkg);
                    }
                }
                finalPkgs = filtered.toArray(new String[0]);
            }
            if (finalPkgs.length == 0) {
                return;
            }
            IPackageManager pm = ActivityThread.getPackageManager();
            if (pm == null) {
                return;
            }
            pm.setPackagesSuspendedAsUser(finalPkgs, suspend, null, null, dialogInfo, 0,
                    NirvanaConstants.SYSTEMUI_SUSPENDER, userId, userId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to change suspension state for user " + userId, e);
        }
    }

    public static void reconcileState(Context context, SuspendDialogInfo dialogInfo) {
        ContentResolver resolver = context.getContentResolver();
        boolean active = NirvanaState.shouldBeActive(resolver);
        Set<String> selectedPackages = NirvanaState.getSelectedApps(resolver);
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager == null) {
            return;
        }
        List<UserHandle> profiles = userManager.getUserProfiles();
        Set<String> trackedState = getTrackedState(resolver);
        Set<String> newTrackedState = new HashSet<>();
        if (active) {
            for (UserHandle userHandle : profiles) {
                int userId = userHandle.getIdentifier();
                ArrayList<String> packagesToSuspend = new ArrayList<>();
                for (String pkg : selectedPackages) {
                    String key = userId + "|" + pkg;
                    boolean isSuspended = isPackageSuspendedForUser(context, pkg, userId);
                    boolean owned = isSuspendedBySystemUi(context, pkg, userId);
                    if (isSuspended) {
                        if (owned) {
                            newTrackedState.add(key);
                        }
                    } else {
                        packagesToSuspend.add(pkg);
                        newTrackedState.add(key);
                    }
                }
                if (!packagesToSuspend.isEmpty()) {
                    applyBatchSuspension(context,
                            packagesToSuspend.toArray(new String[0]), true, userId, dialogInfo);
                }
            }
            saveTrackedState(resolver, newTrackedState);
        } else {
            Map<Integer, ArrayList<String>> mapUserToPkgs = new HashMap<>();
            for (String key : trackedState) {
                String[] parts = key.split("\\|");
                if (parts.length != 2) {
                    continue;
                }
                int userId;
                try {
                    userId = Integer.parseInt(parts[0]);
                } catch (NumberFormatException e) {
                    continue;
                }
                String pkg = parts[1];
                if (isPackageSuspendedForUser(context, pkg, userId)
                        && isSuspendedBySystemUi(context, pkg, userId)) {
                    ArrayList<String> list = mapUserToPkgs.get(userId);
                    if (list == null) {
                        list = new ArrayList<>();
                        mapUserToPkgs.put(userId, list);
                    }
                    list.add(pkg);
                }
            }
            for (Map.Entry<Integer, ArrayList<String>> entry : mapUserToPkgs.entrySet()) {
                applyBatchSuspension(context,
                        entry.getValue().toArray(new String[0]), false, entry.getKey(), null);
            }
            saveTrackedState(resolver, Collections.emptySet());
        }
    }

    public static void enforcePackages(Context context, List<String> packages,
            SuspendDialogInfo dialogInfo) {
        ContentResolver resolver = context.getContentResolver();
        Set<String> selected = NirvanaState.getSelectedApps(resolver);
        ArrayList<String> targets = new ArrayList<>();
        for (String pkg : packages) {
            if (selected.contains(pkg)) {
                targets.add(pkg);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager == null) {
            return;
        }
        for (UserHandle userHandle : userManager.getUserProfiles()) {
            int userId = userHandle.getIdentifier();
            ArrayList<String> toSuspend = new ArrayList<>();
            for (String pkg : targets) {
                if (!isPackageSuspendedForUser(context, pkg, userId)) {
                    toSuspend.add(pkg);
                }
            }
            if (!toSuspend.isEmpty()) {
                applyBatchSuspension(context,
                        toSuspend.toArray(new String[0]), true, userId, dialogInfo);
                Set<String> currentTracked = getTrackedState(resolver);
                Set<String> updated = new HashSet<>(currentTracked);
                for (String pkg : toSuspend) {
                    updated.add(userId + "|" + pkg);
                }
                saveTrackedState(resolver, updated);
            }
        }
    }

    public static void validateTrackedState(Context context) {
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager == null) {
            return;
        }
        List<UserHandle> profiles = userManager.getUserProfiles();
        ContentResolver resolver = context.getContentResolver();
        Set<String> currentTracked = getTrackedState(resolver);
        Set<String> validTracked = new HashSet<>();
        for (String key : currentTracked) {
            String[] parts = key.split("\\|");
            if (parts.length != 2) {
                continue;
            }
            int userId;
            try {
                userId = Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            String pkg = parts[1];
            boolean userExists = false;
            for (UserHandle handle : profiles) {
                if (handle.getIdentifier() == userId) {
                    userExists = true;
                    break;
                }
            }
            if (!userExists) {
                continue;
            }
            if (isPackageSuspendedForUser(context, pkg, userId)
                    && isSuspendedBySystemUi(context, pkg, userId)) {
                validTracked.add(key);
            }
        }
        if (validTracked.size() != currentTracked.size()) {
            saveTrackedState(resolver, validTracked);
        }
    }

    public static int migrateLegacySuspensions(Context context) {
        int adopted = 0;
        try {
            IPackageManager pm = ActivityThread.getPackageManager();
            if (pm == null) {
                return 0;
            }
            UserManager userManager = context.getSystemService(UserManager.class);
            if (userManager == null) {
                return 0;
            }
            ContentResolver resolver = context.getContentResolver();
            Set<String> tracked = getTrackedState(resolver);
            Set<String> updated = new HashSet<>(tracked);
            boolean changed = false;
            PackageManager packageManager = context.getPackageManager();
            List<android.content.pm.PackageInfo> allPackages = packageManager.getInstalledPackages(
                    PackageManager.MATCH_ANY_USER);
            for (UserHandle userHandle : userManager.getUserProfiles()) {
                int userId = userHandle.getIdentifier();
                for (android.content.pm.PackageInfo info : allPackages) {
                    String pkg = info.packageName;
                    String owner = getSuspendingPackage(context, pkg, userId);
                    if (NirvanaConstants.LEGACY_SUSPENDER.equals(owner)) {
                        try {
                            pm.setPackagesSuspendedAsUser(new String[]{pkg}, false,
                                    null, null, null, 0,
                                    NirvanaConstants.SYSTEMUI_SUSPENDER, userId, userId);
                            adopted++;
                        } catch (Exception e) {
                            Log.e(TAG, "Legacy unsuspend failed for " + pkg, e);
                        }
                        if (updated.remove(userId + "|" + pkg)) {
                            changed = true;
                        }
                    }
                }
            }
            if (changed) {
                saveTrackedState(resolver, updated);
            }
        } catch (Exception e) {
            Log.e(TAG, "migrateLegacySuspensions failed", e);
        }
        return adopted;
    }

    public static int forceUnsuspendAll(Context context) {
        int count = 0;
        try {
            UserManager userManager = context.getSystemService(UserManager.class);
            PackageManager packageManager = context.getPackageManager();
            IPackageManager pm = ActivityThread.getPackageManager();
            if (userManager == null || pm == null) {
                return 0;
            }
            List<android.content.pm.PackageInfo> allPackages =
                    packageManager.getInstalledPackages(PackageManager.MATCH_ANY_USER);
            for (UserHandle userHandle : userManager.getUserProfiles()) {
                int userId = userHandle.getIdentifier();
                ArrayList<String> suspended = new ArrayList<>();
                for (android.content.pm.PackageInfo info : allPackages) {
                    if (isPackageSuspendedForUser(context, info.packageName, userId)) {
                        suspended.add(info.packageName);
                    }
                }
                if (!suspended.isEmpty()) {
                    try {
                        pm.setPackagesSuspendedAsUser(
                                suspended.toArray(new String[0]), false,
                                null, null, null, 0,
                                NirvanaConstants.SYSTEMUI_SUSPENDER, userId, userId);
                        count += suspended.size();
                    } catch (Exception e) {
                        Log.e(TAG, "Force-unsuspend failed for user " + userId, e);
                    }
                }
            }
            saveTrackedState(context.getContentResolver(), Collections.emptySet());
        } catch (Exception e) {
            Log.e(TAG, "forceUnsuspendAll failed", e);
        }
        return count;
    }

    public static void unsuspendPackageGlobally(Context context, String pkg) {
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager == null) {
            return;
        }
        for (UserHandle userHandle : userManager.getUserProfiles()) {
            int userId = userHandle.getIdentifier();
            if (isPackageSuspendedForUser(context, pkg, userId)
                    && isSuspendedBySystemUi(context, pkg, userId)) {
                applyBatchSuspension(context, new String[]{pkg}, false, userId, null);
            }
        }
        ContentResolver resolver = context.getContentResolver();
        Set<String> current = getTrackedState(resolver);
        Set<String> updated = new HashSet<>();
        for (String key : current) {
            if (!key.endsWith("|" + pkg)) {
                updated.add(key);
            }
        }
        saveTrackedState(resolver, updated);
    }

    public static void scheduleNextAlarm(Context context) {
        ContentResolver resolver = context.getContentResolver();
        if (!NirvanaState.isScheduleEnabled(resolver)) {
            return;
        }
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) {
            return;
        }
        Intent intent = NirvanaState.buildUpdateIntent();
        PendingIntent pi = PendingIntent.getBroadcast(context,
                NirvanaConstants.SCHEDULE_ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Calendar now = Calendar.getInstance();
        int currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        long nextStart = getNextOccurrence(now, NirvanaState.getStartTime(resolver), currentMinutes);
        long nextEnd = getNextOccurrence(now, NirvanaState.getEndTime(resolver), currentMinutes);
        long triggerTime = Math.min(nextStart, nextEnd);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi);
    }

    private static long getNextOccurrence(Calendar now, int targetMinutes, int currentMinutes) {
        Calendar c = (Calendar) now.clone();
        c.set(Calendar.HOUR_OF_DAY, targetMinutes / 60);
        c.set(Calendar.MINUTE, targetMinutes % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (targetMinutes <= currentMinutes) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    public static void cancelAlarms(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) {
            return;
        }
        Intent intent = NirvanaState.buildUpdateIntent();
        PendingIntent pi = PendingIntent.getBroadcast(context,
                NirvanaConstants.SCHEDULE_ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }
}
