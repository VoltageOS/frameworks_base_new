/*
 * Copyright (C) 2025 The VoltageOS Project
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

package com.android.internal.util.voltage.rotation;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

public class RotationController {

    private static final String TAG = "RotationController";
    private static final boolean DEBUG = false;

    public static final int ROTATION_DEFAULT = 0;
    public static final int ROTATION_PORTRAIT = 1;
    public static final int ROTATION_LANDSCAPE = 2;
    public static final int ROTATION_FULL_SENSOR = 3;

    private static final int MAX_ENTRIES = 1000;

    private final Context mContext;
    private final ConcurrentHashMap<String, Integer> mRotationMap = new ConcurrentHashMap<>();

    private static final String SEPARATOR = ",";
    private static final String VALUE_SEPARATOR = "=";

    public RotationController(Context context) {
        mContext = context;
        SettingsObserver observer = new SettingsObserver(new Handler(Looper.getMainLooper()));
        observer.observe();
    }

    public int getRotationForApp(String packageName) {
        if (packageName == null) {
            return ROTATION_DEFAULT;
        }
        return mRotationMap.getOrDefault(packageName, ROTATION_DEFAULT);
    }

    private boolean isValidRotation(int rotation) {
        return rotation >= ROTATION_PORTRAIT && rotation <= ROTATION_FULL_SENSOR;
    }

    private void update() {
        mRotationMap.clear();
        ContentResolver resolver = mContext.getContentResolver();

        String setting = Settings.System.getStringForUser(resolver,
                Settings.System.PER_APP_ROTATION, UserHandle.USER_CURRENT);

        if (TextUtils.isEmpty(setting)) {
            return;
        }

        final String[] entries = setting.split(SEPARATOR, -1);
        int entryCount = 0;
        for (String entry : entries) {
            if (TextUtils.isEmpty(entry)) {
                continue;
            }
            if (entryCount >= MAX_ENTRIES) {
                break;
            }

            final String[] split = entry.split(VALUE_SEPARATOR, 2);
            if (split.length != 2 || TextUtils.isEmpty(split[0])) {
                continue;
            }
            try {
                int rotation = Integer.parseInt(split[1]);
                if (isValidRotation(rotation)) {
                    mRotationMap.put(split[0], rotation);
                    entryCount++;
                }
            } catch (NumberFormatException e) {
                // Ignore malformed entry
            }
        }
        if (DEBUG) {
            Log.d(TAG, "Updated rotation map with " + mRotationMap.size() + " entries");
        }
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PER_APP_ROTATION), false, this, UserHandle.USER_ALL);
            update();
        }

        @Override
        public void onChange(boolean selfChange) {
            update();
        }
    }
}
