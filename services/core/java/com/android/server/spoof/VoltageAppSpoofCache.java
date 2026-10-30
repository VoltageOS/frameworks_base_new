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
package com.android.server.spoof;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

import org.json.JSONObject;

import java.util.Collections;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class VoltageAppSpoofCache {
    private static final String TAG = "VoltageAppSpoofCache";

    private static final int FLAG_HIDE_A11Y = 1;
    private static final int FLAG_ISOLATION = 2;
    private static final int FLAG_SHOW_REAL = 4;

    private static volatile Map<String, Integer> sFlags = Collections.emptyMap();
    private static volatile String sRaw = null;

    private static Context sContext;
    private static boolean sInitialized;

    private VoltageAppSpoofCache() {
    }

    /** Call once from AMS.systemReady(), after the settings provider is installed. */
    public static synchronized void init(Context context) {
        if (sInitialized) {
            return;
        }
        sInitialized = true;
        sContext = context;

        Handler handler = BackgroundThread.getHandler();
        ContentObserver observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                reload();
            }
        };
        ContentResolver cr = context.getContentResolver();
        cr.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.SPOOF_APPSTATE_CONFIG),
                false, observer, UserHandle.USER_ALL);
        cr.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.SPOOF_GAMEPROPS_CONFIG),
                false, observer, UserHandle.USER_ALL);
        handler.post(VoltageAppSpoofCache::reload);
    }

    private static void reload() {
        try {
            ContentResolver cr = sContext.getContentResolver();
            String raw = Settings.Secure.getStringForUser(cr,
                    Settings.Secure.SPOOF_APPSTATE_CONFIG, UserHandle.USER_SYSTEM);
            String legacy = Settings.Secure.getStringForUser(cr,
                    Settings.Secure.SPOOF_GAMEPROPS_CONFIG, UserHandle.USER_SYSTEM);

            Map<String, Integer> next = new HashMap<>(parse(raw));
            for (Map.Entry<String, Integer> e : parseLegacy(legacy).entrySet()) {
                next.putIfAbsent(e.getKey(), e.getValue());
            }
            sFlags = next;
            sRaw = raw;
        } catch (Exception e) {
            Slog.w(TAG, "reload failed, keeping previous snapshot", e);
        }
    }

    /** Raw appstate JSON, for the AMS binder getter. */
    public static String getRawConfig() {
        return sRaw;
    }

    public static boolean isAccessibilityHidden(String packageName) {
        return (getFlags(packageName) & FLAG_HIDE_A11Y) != 0;
    }

    public static boolean isIsolated(String packageName) {
        return (getFlags(packageName) & FLAG_ISOLATION) != 0;
    }

    public static boolean isShowRealSettings(String packageName) {
        return (getFlags(packageName) & FLAG_SHOW_REAL) != 0;
    }

    public static boolean isIsolatedForPackages(String[] packages) {
        if (packages == null) {
            return false;
        }
        for (String pkg : packages) {
            if (pkg != null && isIsolated(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static int getFlags(String packageName) {
        if (packageName == null) {
            return 0;
        }
        Integer v = sFlags.get(packageName);
        return v == null ? 0 : v;
    }

    static Map<String, Integer> parse(String raw) {
        Map<String, Integer> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject games = root.optJSONObject("apps");
            if (games == null) {
                return out;
            }
            Iterator<String> keys = games.keys();
            while (keys.hasNext()) {
                String pkg = keys.next();
                JSONObject entry = games.optJSONObject(pkg);
                if (entry == null) {
                    continue;
                }
                int flags = 0;
                if (entry.optBoolean("hideAccessibility", false)) {
                    flags |= FLAG_HIDE_A11Y;
                }
                if (entry.optBoolean("isolation", false)) {
                    flags |= FLAG_ISOLATION;
                }
                if (entry.optBoolean("showRealSettings", false)) {
                    flags |= FLAG_SHOW_REAL;
                }
                if (flags != 0) {
                    out.put(pkg, flags);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static Map<String, Integer> parseLegacy(String raw) {
        Map<String, Integer> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject games = root.optJSONObject("games");
            if (games == null) {
                return out;
            }
            Iterator<String> keys = games.keys();
            while (keys.hasNext()) {
                String pkg = keys.next();
                JSONObject entry = games.optJSONObject(pkg);
                if (entry == null) {
                    continue;
                }
                int flags = 0;
                if (entry.has("props")) {
                    if (entry.optBoolean("hideAccessibility", false)) {
                        flags |= FLAG_HIDE_A11Y;
                    }
                    if (entry.optBoolean("isolation", false)) {
                        flags |= FLAG_ISOLATION;
                    }
                    if (entry.optBoolean("showRealSettings", false)) {
                        flags |= FLAG_SHOW_REAL;
                    }
                }
                if (flags != 0) {
                    out.put(pkg, flags);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
