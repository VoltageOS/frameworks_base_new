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
package com.android.server;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.util.voltage.HideAppListCache;

import java.util.HashSet;
import java.util.Set;

public class HideAppListService extends SystemService {
    private static final String TAG = "HideAppListService";

    private final Context mContext;
    private final Handler mHandler = new Handler();

    public HideAppListService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        Slog.i(TAG, "Starting HideAppListService");
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
            HideAppListCache.init(mContext);

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            filter.addDataScheme("package");
            mContext.registerReceiver(new PackageUninstallReceiver(), filter);
        }
    }

    private class PackageUninstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String packageName = intent.getData().getSchemeSpecificPart();
            if (packageName != null) {
                Slog.i(TAG, "Package uninstalled: " + packageName);
                removeFromHideAppList(packageName);
            }
        }
    }

    private void removeFromHideAppList(String packageName) {
        Set<String> current = HideAppListCache.getSnapshot();
        if (current.isEmpty() || !current.contains(packageName)) {
            return;
        }

        ContentResolver cr = mContext.getContentResolver();
        Set<String> updated = new HashSet<>(current);
        updated.remove(packageName);
        Slog.i(TAG, "Removing uninstalled package from hide list: " + packageName);
        Settings.Secure.putString(cr, Settings.Secure.HIDE_APPLIST,
                String.join(",", updated));
    }
}
