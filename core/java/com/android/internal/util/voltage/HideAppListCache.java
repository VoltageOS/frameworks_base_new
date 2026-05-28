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
package com.android.internal.util.voltage;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Slog;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class HideAppListCache {

    private static final String TAG = "HideAppListCache";

    private static volatile Set<String> sCachedApps = Collections.emptySet();
    private static volatile boolean sObserverRegistered = false;

    private HideAppListCache() {}

    public static void init(Context context) {
        if (sObserverRegistered) return;
        sObserverRegistered = true;

        final ContentResolver cr = context.getContentResolver();

        reload(cr);

        final Uri uri = Settings.Secure.getUriFor(Settings.Secure.HIDE_APPLIST);
        cr.registerContentObserver(uri, false,
                new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        reload(cr);
                    }
                });
    }

    private static void reload(ContentResolver cr) {
        try {
            final String raw = Settings.Secure.getString(cr, Settings.Secure.HIDE_APPLIST);
            if (raw == null || raw.isEmpty() || raw.equals(",")) {
                sCachedApps = Collections.emptySet();
            } else {
                sCachedApps = Collections.unmodifiableSet(
                        new HashSet<>(Arrays.asList(raw.split(","))));
            }
            Slog.d(TAG, "Cache reloaded: " + sCachedApps.size() + " hidden app(s)");
        } catch (Exception e) {
            Slog.e(TAG, "Failed to reload hidden-app cache", e);
        }
    }

    public static boolean shouldHide(String packageName) {
        if (packageName == null) return false;
        final Set<String> apps = sCachedApps; // single volatile read
        return !apps.isEmpty() && apps.contains(packageName);
    }

    public static Set<String> getSnapshot() {
        return sCachedApps;
    }

    public static void invalidate(ContentResolver cr) {
        reload(cr);
    }
}
