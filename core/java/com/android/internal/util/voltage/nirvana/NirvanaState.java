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

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class NirvanaState {
    private NirvanaState() {}

    public static boolean isManualActive(ContentResolver resolver) {
        return Settings.Secure.getIntForUser(resolver,
                NirvanaConstants.KEY_MANUAL_ACTIVE, 0, UserHandle.USER_CURRENT) == 1;
    }

    public static void setManualActive(ContentResolver resolver, boolean active) {
        Settings.Secure.putIntForUser(resolver,
                NirvanaConstants.KEY_MANUAL_ACTIVE, active ? 1 : 0, UserHandle.USER_CURRENT);
    }

    public static boolean isScheduleEnabled(ContentResolver resolver) {
        return Settings.Secure.getIntForUser(resolver,
                NirvanaConstants.KEY_SCHEDULE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
    }

    public static void setScheduleEnabled(ContentResolver resolver, boolean enabled) {
        Settings.Secure.putIntForUser(resolver,
                NirvanaConstants.KEY_SCHEDULE_ENABLED, enabled ? 1 : 0, UserHandle.USER_CURRENT);
    }

    public static int getStartTime(ContentResolver resolver) {
        return Settings.Secure.getIntForUser(resolver,
                NirvanaConstants.KEY_START_TIME,
                NirvanaConstants.DEFAULT_START, UserHandle.USER_CURRENT);
    }

    public static int getEndTime(ContentResolver resolver) {
        return Settings.Secure.getIntForUser(resolver,
                NirvanaConstants.KEY_END_TIME,
                NirvanaConstants.DEFAULT_END, UserHandle.USER_CURRENT);
    }

    public static void saveSchedule(ContentResolver resolver, int start, int end) {
        Settings.Secure.putIntForUser(resolver,
                NirvanaConstants.KEY_START_TIME, start, UserHandle.USER_CURRENT);
        Settings.Secure.putIntForUser(resolver,
                NirvanaConstants.KEY_END_TIME, end, UserHandle.USER_CURRENT);
    }

    public static Set<String> getSelectedApps(ContentResolver resolver) {
        String raw = Settings.Secure.getStringForUser(resolver,
                NirvanaConstants.KEY_APPS, UserHandle.USER_CURRENT);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new HashSet<>();
        for (String entry : raw.split(",")) {
            String pkg = entry.trim();
            if (!pkg.isEmpty()) {
                out.add(pkg);
            }
        }
        return out;
    }

    public static void saveSelectedApps(ContentResolver resolver, Set<String> apps) {
        StringBuilder sb = new StringBuilder();
        for (String pkg : apps) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(pkg);
        }
        Settings.Secure.putStringForUser(resolver,
                NirvanaConstants.KEY_APPS, sb.toString(), UserHandle.USER_CURRENT);
    }

    public static boolean shouldScheduleBeActive(ContentResolver resolver) {
        Calendar now = Calendar.getInstance();
        int currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int start = getStartTime(resolver);
        int end = getEndTime(resolver);
        if (end < start) {
            return currentMinutes >= start || currentMinutes < end;
        }
        return currentMinutes >= start && currentMinutes < end;
    }

    public static boolean shouldBeActive(ContentResolver resolver) {
        return isManualActive(resolver)
                || (isScheduleEnabled(resolver) && shouldScheduleBeActive(resolver));
    }

    public static Intent buildUpdateIntent() {
        Intent intent = new Intent(NirvanaConstants.ACTION_UPDATE);
        intent.setPackage(NirvanaConstants.TARGET_PACKAGE);
        return intent;
    }

    public static void sendUpdateBroadcast(Context context) {
        context.sendBroadcastAsUser(buildUpdateIntent(), UserHandle.CURRENT);
    }

    public static void toggleManualActive(Context context) {
        ContentResolver resolver = context.getContentResolver();
        setManualActive(resolver, !isManualActive(resolver));
        sendUpdateBroadcast(context);
    }
}
