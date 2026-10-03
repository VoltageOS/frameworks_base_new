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

package com.android.systemui.navigationbar.gestural.pie;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

public class PieItemRepository {
    private static final String TAG = "PieMenuRepository";
    private final Context mContext;
    private final Executor mBgExecutor;
    private final Map<String, List<PieItem>> mCache = new HashMap<>();

    public PieItemRepository(Context context, Executor bgExecutor) {
        mContext = context;
        mBgExecutor = bgExecutor;
    }

    public synchronized void invalidate() {
        mCache.clear();
    }

    public synchronized List<PieItem> getItems(boolean left, boolean vertical) {
        String key = keyFor(left, vertical);
        List<PieItem> cached = mCache.get(key);
        if (cached != null) {
            return cached;
        }
        String raw = Settings.System.getStringForUser(mContext.getContentResolver(),
                key, UserHandle.USER_CURRENT);
        List<PieItem> parsed;
        try {
            parsed = parseRaw(raw, mContext.getPackageManager());
        } catch (Exception e) {
            Log.w(TAG, "getItems failed for " + key, e);
            parsed = new ArrayList<>();
        }
        mCache.put(key, parsed);
        preloadIconsAsync(parsed);
        return parsed;
    }

    public synchronized int getCachedCount(boolean left, boolean vertical) {
        List<PieItem> cached = mCache.get(keyFor(left, vertical));
        return cached == null ? -1 : cached.size();
    }

    public void dump(PrintWriter pw) {
        pw.println("  PieItemRepository:");
        dumpSlot(pw, true, false);
        dumpSlot(pw, false, false);
        dumpSlot(pw, true, true);
        dumpSlot(pw, false, true);
    }

    private void dumpSlot(PrintWriter pw, boolean left, boolean vertical) {
        List<PieItem> items;
        synchronized (this) {
            items = mCache.get(keyFor(left, vertical));
        }
        if (items == null) {
            String raw = null;
            try {
                raw = Settings.System.getStringForUser(mContext.getContentResolver(),
                        keyFor(left, vertical), UserHandle.USER_CURRENT);
                items = parseRaw(raw, mContext.getPackageManager());
            } catch (Exception e) {
                Log.w(TAG, "dump parse failed", e);
                items = new ArrayList<>();
            }
        }
        pw.println("    pie left=" + left + " vertical=" + vertical + " count=" + items.size());
        for (int i = 0; i < items.size(); i++) {
            pw.println("      [" + i + "] " + items.get(i).toString());
        }
    }

    private void preloadIconsAsync(List<PieItem> items) {
        if (mBgExecutor == null || items.isEmpty()) {
            return;
        }
        mBgExecutor.execute(() -> {
            PackageManager pm = mContext.getPackageManager();
            for (int i = 0; i < items.size(); i++) {
                PieItem item = items.get(i);
                if (item.icon != null) {
                    continue;
                }
                try {
                    Drawable d = null;
                    if (item.type == PieItem.TYPE_APP) {
                        d = pm.getApplicationIcon(item.packageName);
                    } else if (item.type == PieItem.TYPE_ACTIVITY) {
                        d = pm.getActivityIcon(new android.content.ComponentName(
                                item.packageName, item.className));
                    }
                    if (d != null) {
                        item.icon = d;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "icon load failed: " + item.toString(), e);
                }
            }
        });
    }

    static String keyFor(boolean left, boolean vertical) {
        if (left) {
            return vertical ? Settings.System.LEFT_VERTICAL_BACK_SWIPE_PIE_ITEMS
                    : Settings.System.LEFT_LONG_BACK_SWIPE_PIE_ITEMS;
        }
        return vertical ? Settings.System.RIGHT_VERTICAL_BACK_SWIPE_PIE_ITEMS
                : Settings.System.RIGHT_LONG_BACK_SWIPE_PIE_ITEMS;
    }

    public static List<PieItem> parseRaw(String raw, PackageManager pm) {
        List<PieItem> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        String[] slots = raw.split(";", -1);
        for (int i = 0; i < slots.length && out.size() < PieItem.MAX_SLOTS; i++) {
            String slot = slots[i];
            if (slot == null || slot.isEmpty()) {
                continue;
            }
            PieItem item = parseSlot(slot, pm);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    static PieItem parseSlot(String slot, PackageManager pm) {
        String[] parts;
        try {
            parts = slot.split(":", 3);
        } catch (Exception e) {
            Log.w(TAG, "parseSlot split failed", e);
            return null;
        }
        if (parts.length < 2) {
            return null;
        }
        String type = parts[0];
        String payload = parts[1];
        String label = parts.length > 2 ? sanitizeLabel(parts[2]) : "";
        try {
            if (type.equals("app")) {
                if (payload.isEmpty()) {
                    return null;
                }
                if (pm != null) {
                    try {
                        pm.getPackageInfo(payload, 0);
                    } catch (Exception e) {
                        Log.w(TAG, "drop uninstalled app:" + payload, e);
                        return null;
                    }
                }
                if (label.isEmpty()) {
                    label = payload;
                }
                return new PieItem(PieItem.TYPE_APP, payload, "", -1, label);
            } else if (type.equals("activity")) {
                String[] comp = payload.split("/", 2);
                if (comp.length != 2 || comp[0].isEmpty() || comp[1].isEmpty()) {
                    return null;
                }
                if (pm != null) {
                    try {
                        pm.getPackageInfo(comp[0], 0);
                    } catch (Exception e) {
                        Log.w(TAG, "drop uninstalled activity pkg:" + comp[0], e);
                        return null;
                    }
                }
                if (label.isEmpty()) {
                    label = comp[0];
                }
                return new PieItem(PieItem.TYPE_ACTIVITY, comp[0], comp[1], -1, label);
            } else if (type.equals("action")) {
                int id = Integer.parseInt(payload);
                if (id < 0 || id > 30) {
                    return null;
                }
                if (label.isEmpty()) {
                    label = Integer.toString(id);
                }
                return new PieItem(PieItem.TYPE_ACTION, "", "", id, label);
            }
        } catch (Exception e) {
            Log.w(TAG, "drop malformed slot", e);
            return null;
        }
        return null;
    }

    static String sanitizeLabel(String label) {
        if (label == null) {
            return "";
        }
        String s = label.replace(";", "").replace("\n", "").replace("\r", "").trim();
        if (s.length() > 40) {
            s = s.substring(0, 40);
        }
        return s;
    }

    public static String filterOutPackage(String raw, String packageName) {
        if (raw == null || raw.isEmpty() || packageName == null || packageName.isEmpty()) {
            return raw == null ? "" : raw;
        }
        String[] slots = raw.split(";", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < slots.length; i++) {
            String slot = slots[i];
            if (slot == null || slot.isEmpty()) {
                continue;
            }
            String[] parts = slot.split(":", 3);
            if (parts.length < 2) {
                continue;
            }
            String type = parts[0];
            String payload = parts[1];
            boolean drop = false;
            if (type.equals("app")) {
                drop = payload.equals(packageName);
            } else if (type.equals("activity")) {
                String[] comp = payload.split("/", 2);
                drop = comp.length > 0 && comp[0].equals(packageName);
            }
            if (!drop) {
                if (out.length() > 0) {
                    out.append(";");
                }
                out.append(slot);
            }
        }
        return out.toString();
    }
}
