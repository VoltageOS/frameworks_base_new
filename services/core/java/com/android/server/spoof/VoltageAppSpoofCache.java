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

import com.android.server.NtServiceInjector;
import com.android.server.AxExtServiceFactory;

import org.json.JSONObject;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class VoltageAppSpoofCache {
    private static final int FLAG_HIDE_A11Y = 1;
    private static final int FLAG_ISOLATION = 2;
    private static final int FLAG_SHOW_REAL = 4;

    private static final Map<String, Integer> sFlags = new ConcurrentHashMap<>();
    private static volatile String sRaw = null;
    private static volatile String sLegacyRaw = null;

    private VoltageAppSpoofCache() {
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
        refreshIfNeeded();
        Integer v = sFlags.get(packageName);
        return v == null ? 0 : v;
    }

    private static void refreshIfNeeded() {
        String raw = null;
        String legacy = null;
        try {
            IAxSpoofManager mgr = AxExtServiceFactory.getSpoofManager();
            if (mgr != null) {
                raw = mgr.getAppStateConfig();
                legacy = mgr.getGamePropsConfig();
            }
        } catch (Throwable ignored) {
        }
        if (raw == null) {
            try {
                Context ctx = NtServiceInjector.getCtx();
                if (ctx != null) {
                    raw = Settings.Secure.getStringForUser(ctx.getContentResolver(),
                            Settings.Secure.SPOOF_APPSTATE_CONFIG, UserHandle.USER_SYSTEM);
                    legacy = Settings.Secure.getStringForUser(ctx.getContentResolver(),
                            Settings.Secure.SPOOF_GAMEPROPS_CONFIG, UserHandle.USER_SYSTEM);
                }
            } catch (Throwable ignored) {
            }
        }
        if (raw == null) {
            raw = "";
        }
        if (legacy == null) {
            legacy = "";
        }
        if (raw.equals(sRaw) && legacy.equals(sLegacyRaw)) {
            return;
        }
        synchronized (VoltageAppSpoofCache.class) {
            if (raw.equals(sRaw) && legacy.equals(sLegacyRaw)) {
                return;
            }
            sRaw = raw;
            sLegacyRaw = legacy;
            sFlags.clear();
            sFlags.putAll(parse(raw));
            Map<String, Integer> legacyFlags = parseLegacy(legacy);
            for (Map.Entry<String, Integer> e : legacyFlags.entrySet()) {
                if (!sFlags.containsKey(e.getKey())) {
                    sFlags.put(e.getKey(), e.getValue());
                }
            }
        }
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
