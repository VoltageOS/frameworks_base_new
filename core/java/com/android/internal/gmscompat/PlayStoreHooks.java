/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.gmscompat;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.app.compat.gms.GmsCompat;
import android.app.usage.StorageStats;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.ext.PackageId;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.LocaleList;
import android.os.RemoteException;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.gmscompat.sysservice.GmcPackageManager;
import com.android.internal.gmscompat.util.GmcActivityUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import static java.util.Objects.requireNonNull;

public final class PlayStoreHooks {
    private static final String TAG = "GmsCompat/PlayStore";

    static PackageManager packageManager;
    private static String obbDir;
    private static String playStoreObbDir;

    public static void init() {
        obbDir = Environment.getExternalStorageDirectory().getPath() + "/Android/obb";
        playStoreObbDir = obbDir + '/' + GmsInfo.PACKAGE_PLAY_STORE;
        File.mkdirsFailedHook = PlayStoreHooks::mkdirsFailed;
        Context ctx = GmsCompat.appContext();
        if (ctx != null) {
            packageManager = ctx.getPackageManager();
            InternalBroadcastReceiver.register(ctx);
        }
    }

    public static void adjustSessionParams(PackageInstaller.SessionParams params) {
        if (params == null) return;
        String pkg = params.appPackageName;
        if (pkg == null) return; // Or requireNonNull(params.appPackageName);

        switch (pkg) {
            case GmsInfo.PACKAGE_GMS_CORE:
            case GmsInfo.PACKAGE_PLAY_STORE:
                String updateRequestReason = "Play Store created PackageInstaller SessionParams for " + pkg;
                GmsCompatConfig newConfig = null;
                try {
                    IGms2Gca gmsInterface = GmsCompatApp.iGms2Gca();
                    if (gmsInterface != null) {
                        newConfig = gmsInterface.requestConfigUpdate(updateRequestReason);
                    }
                } catch (RemoteException e) {
                }

                if (newConfig != null) {
                    GmsCompatConfig currentConfig = GmsHooks.config();
                    if (currentConfig == null || currentConfig.version != newConfig.version) {
                        GmsHooks.setConfig(newConfig);
                    }
                }
                break;
        }

        GmsCompatConfig configForLimits = GmsHooks.config();
        if (configForLimits != null) {
            switch (pkg) {
                case GmsInfo.PACKAGE_GMS_CORE:
                    params.maxAllowedVersion = configForLimits.maxGmsCoreVersion;
                    break;
                case GmsInfo.PACKAGE_PLAY_STORE:
                    params.maxAllowedVersion = configForLimits.maxPlayStoreVersion;
                    break;
            }
        }
    }

    public static IntentSender wrapCommitStatusReceiver(PackageInstaller.Session session, IntentSender statusReceiver) {
        if (statusReceiver == null) return null;
        PendingIntent pi = PackageInstallerStatusForwarder.register((intent, extras) -> sendIntent(intent, statusReceiver));
        return (pi != null) ? pi.getIntentSender() : null;
    }

    public static void onActivityResumed(Activity activity) {
        if (activity == null) return;
        Intent pendingActionIntent = null;
        try {
            IGms2Gca gmsInterface = GmsCompatApp.iGms2Gca();
            if (gmsInterface != null) {
                pendingActionIntent = gmsInterface.maybeGetPlayStorePendingUserActionIntent();
            }
        } catch (RemoteException e) {
        }
        if (pendingActionIntent != null) {
            try {
                activity.startActivity(pendingActionIntent);
            } catch (Exception e) { /* Ignore */ }
        }
    }

    static class PackageInstallerStatusForwarder extends BroadcastReceiver {
        private Context context;
        private PendingIntent pendingIntent;
        private BiConsumer<Intent, Bundle> target;

        private static final AtomicLong lastId = new AtomicLong();

        static PendingIntent register(BiConsumer<Intent, Bundle> target) {
            if (target == null) return null;

            PackageInstallerStatusForwarder sf = new PackageInstallerStatusForwarder();
            Context context = GmsCompat.appContext();
            if (context == null) return null;

            sf.context = context;
            sf.target = target;

            String intentAction = context.getPackageName()
                + "." + PackageInstallerStatusForwarder.class.getName() + "."
                + lastId.getAndIncrement();

            Intent intent = new Intent(intentAction);
            intent.setPackage(context.getPackageName());

            try {
                 sf.pendingIntent = PendingIntent.getBroadcast(context, 0, intent,
                         PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);

                 context.registerReceiver(sf, new IntentFilter(intentAction), Context.RECEIVER_NOT_EXPORTED);
                 return sf.pendingIntent;
            } catch (Exception e) {
                 return null; // Failed to register
            }
        }

        public void onReceive(Context receiverContext, Intent intent) {
            if (intent == null || context == null || target == null || pendingIntent == null) {
                 if (context != null && pendingIntent != null) {
                     try {
                         pendingIntent.cancel();
                         context.unregisterReceiver(this);
                     } catch (Exception e) { /* Ignore */ }
                 }
                 return;
            }

            Bundle extras = intent.getExtras();
            if (extras == null) {
                 pendingIntent.cancel();
                 context.unregisterReceiver(this);
                 return; // Cannot proceed without extras
            }

            int status = getIntFromBundle(extras, PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);

            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirmationIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                String packageName = null;

                if (extras.containsKey(PackageInstaller.EXTRA_SESSION_ID)) {
                    int sessionId = getIntFromBundle(extras, PackageInstaller.EXTRA_SESSION_ID, -1);
                    if (sessionId != -1 && packageManager != null) {
                        PackageInstaller pkgInstaller = packageManager.getPackageInstaller();
                        if (pkgInstaller != null) {
                            PackageInstaller.SessionInfo si = pkgInstaller.getSessionInfo(sessionId);
                            if (si != null) {
                                packageName = si.getAppPackageName();
                            }
                        }
                    }
                }

                try {
                    IGms2Gca gmsInterface = GmsCompatApp.iGms2Gca();
                    if (gmsInterface != null) {
                        gmsInterface.onPlayStorePendingUserAction(confirmationIntent, packageName);
                    }
                } catch (RemoteException e) {
                }
                return;
            }

            try {
                pendingIntent.cancel();
                context.unregisterReceiver(this);
            } catch (Exception e) { /* Ignore */ }

            target.accept(intent, extras);
        }
    }

    public static void deletePackage(PackageManager pm, String packageName, IPackageDeleteObserver observer, int flags) {
        if (flags != 0 || pm == null || packageName == null || observer == null) {
            if (observer != null) {
                try {
                    observer.packageDeleted(packageName, PackageManager.DELETE_FAILED_INTERNAL_ERROR);
                } catch (RemoteException e) { /* Ignore */ }
            }
            if (flags != 0) throw new IllegalStateException("unexpected flags: " + flags);
            return;
        }

        PackageInstaller packageInstaller = pm.getPackageInstaller();
        if (packageInstaller == null) {
             try {
                 observer.packageDeleted(packageName, PackageManager.DELETE_FAILED_INTERNAL_ERROR);
             } catch (RemoteException e) { /* Ignore */ }
             return;
        }

        PendingIntent pi = PackageInstallerStatusForwarder.register((intent, extras) -> {
            // Optional: Log status if needed
        });

        if (pi == null) {
             try {
                 observer.packageDeleted(packageName, PackageManager.DELETE_FAILED_INTERNAL_ERROR);
             } catch (RemoteException e) { /* Ignore */ }
             return;
        }

        packageInstaller.uninstall(packageName, pi.getIntentSender());

        Context ctx = GmsCompat.appContext();
        if (ctx != null && ctx.getMainThreadHandler() != null) {
            ctx.getMainThreadHandler().postDelayed(() -> {
                try {
                    observer.packageDeleted(packageName, PackageManager.DELETE_FAILED_ABORTED);
                } catch (RemoteException e) {
                }
                resetPackageState(packageName);
            }, 100L);
        } else {
             // Fallback if context or handler is null, call immediately but might race
             try {
                 observer.packageDeleted(packageName, PackageManager.DELETE_FAILED_ABORTED);
             } catch (RemoteException e) { /* Ignore */ }
             resetPackageState(packageName);
        }
    }

    public static class InternalBroadcastReceiver extends BroadcastReceiver {
        private static final String ACTION_PREFIX = "GmsCompat.InternalBroadcastReceiver";
        private static final String ACTION_SEND_SELF_BROADCAST = ACTION_PREFIX + ".ACTION_SEND_SELF_BROADCAST";
        private static final String ACTION_REMOVE_PSEUDO_DISABLED_PKG = ACTION_PREFIX + ".ACTION_REMOVE_PSEUDO_DISABLED_PKG";
        private static final String EXTRA_INTENTS = "intents";

        static void register(Context ctx) {
            if (ctx == null) return;
            var f = new IntentFilter(ACTION_SEND_SELF_BROADCAST);
            f.addAction(ACTION_REMOVE_PSEUDO_DISABLED_PKG);
            try {
                ctx.registerReceiver(new InternalBroadcastReceiver(), f, Context.RECEIVER_NOT_EXPORTED);
            } catch (Exception e) { /* Ignore registration errors */ }
        }

        public static void sendManualBroadcasts(Context ctx, Intent... intents) {
            if (ctx == null || intents == null || intents.length == 0) return;
            var i = new Intent(ACTION_SEND_SELF_BROADCAST);
            i.putExtra(EXTRA_INTENTS, intents);
            sendBroadcast(ctx, i);
        }

        public static void removePseudoDisabledPackage(Context ctx, String pkgName) {
             if (ctx == null || pkgName == null) return;
            var i = new Intent(ACTION_REMOVE_PSEUDO_DISABLED_PKG);
            i.putExtra(Intent.EXTRA_PACKAGE_NAME, pkgName);
            sendBroadcast(ctx, i);
        }

        private static void sendBroadcast(Context ctx, Intent i) {
            if (ctx == null || i == null) return;
            i.setPackage(ctx.getPackageName());
            try {
                ctx.sendBroadcast(i);
            } catch (Exception e) { /* Ignore send errors */ }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (context == null || intent == null || intent.getAction() == null) return;

            switch (intent.getAction()) {
                case ACTION_SEND_SELF_BROADCAST: {
                    Intent[] intents = intent.getParcelableArrayExtra(EXTRA_INTENTS, Intent.class);
                    if (intents != null) {
                        sendManualSelfBroadcasts(intents);
                    }
                    break;
                }
                case ACTION_REMOVE_PSEUDO_DISABLED_PKG: {
                    String pkg = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
                    if (pkg != null) {
                        GmcPackageManager.removePseudoDisabledPackage(pkg);
                    }
                    break;
                }
            }
        }
    }

    static void sendManualSelfBroadcasts(Intent[] broadcasts) {
        Context context = GmsCompat.appContext();
        if (context == null || broadcasts == null || packageManager == null) return;

        ClassLoader cl = context.getClassLoader();
        String selfProcessName = Application.getProcessName();
        if (selfProcessName == null) return;

        for (Intent intent : broadcasts) {
            if (intent == null) continue;
            intent.setPackage(context.getPackageName());
            List<ResolveInfo> result = null;
            try {
                 result = packageManager.queryBroadcastReceivers(intent, PackageManager.GET_RESOLVED_FILTER);
            } catch (Exception e) { continue; } // Ignore query errors

            if (result == null) continue;

            for (ResolveInfo resolveInfo : result) {
                ActivityInfo receiver = resolveInfo.activityInfo;
                if (receiver == null) continue;

                String processName = receiver.processName;
                if (TextUtils.isEmpty(processName)) {
                    processName = context.getPackageName();
                }
                if (!processName.equals(selfProcessName)) {
                    continue;
                }
                String clsName = receiver.name;
                if (clsName == null) continue;

                try {
                    Class<?> cls = cl.loadClass(clsName);
                    BroadcastReceiver br = (BroadcastReceiver) cls.getDeclaredConstructor().newInstance();
                    br.onReceive(context, intent);
                } catch (ReflectiveOperationException | ClassCastException e) {
                    // Ignore instantiation/cast/receive errors
                }
            }
        }
    }

    public static void resetPackageState(String packageName) {
        if (packageName == null) return;
        updatePackageState(packageName, Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_ADDED);
    }

    public static void updatePackageState(String packageName, String... actions) {
        if (packageName == null || actions == null || actions.length == 0) return;
        Context ctx = GmsCompat.appContext();
        if (ctx == null) return;

        var intents = new Intent[actions.length];
        Uri uri = packageUri(packageName);
        for (int i = 0; i < intents.length; ++i) {
            if (actions[i] != null) {
                intents[i] = new Intent(actions[i], uri);
            }
        }
        InternalBroadcastReceiver.sendManualBroadcasts(ctx, intents);
    }

    public static void freeStorageAndNotify(String volumeUuid, long idealStorageSize,
            IPackageDataObserver observer) {
        if (volumeUuid != null) {
            throw new IllegalStateException("unexpected volumeUuid " + volumeUuid);
        }
        if (observer == null) return;

        Context ctx = GmsCompat.appContext();
        if (ctx == null) {
             try { observer.onRemoveCompleted(null, false); } catch (RemoteException e) {}
             return;
        }
        StorageManager sm = ctx.getSystemService(StorageManager.class);
        boolean success = false;
        if (sm != null) {
            try {
                sm.allocateBytes(StorageManager.UUID_DEFAULT, idealStorageSize);
                success = true;
            } catch (IOException e) {
                // Ignore IOExceptions
            }
        }
        try {
            String packageName = null;
            observer.onRemoveCompleted(packageName, success);
        } catch (RemoteException e) {
        }
    }

    public static StorageStats queryStatsForPackage(String packageName) throws PackageManager.NameNotFoundException {
        if (packageManager == null || packageName == null) {
             throw new PackageManager.NameNotFoundException("PackageManager or packageName is null");
        }
        ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
        String apkPath = appInfo.sourceDir;

        StorageStats stats = new StorageStats();
        if (apkPath != null) {
            File apkFile = new File(apkPath);
            if (apkFile.exists()) {
                 stats.codeBytes = apkFile.length();
            }
        }
        return stats;
    }

    public static void setApplicationEnabledSetting(String packageName, int newState) {
        if (packageName != null && newState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    && GmcActivityUtils.getMostRecentVisibleActivity() != null)
        {
            openAppSettings(packageName);
        }
    }

    public static void mkdirsFailed(File file) {
        if (file == null) return;
        String path = file.getPath();
        Context ctx = GmsCompat.appContext();
        if (ctx == null || path == null || obbDir == null || playStoreObbDir == null) return;

        if (path.startsWith(obbDir) && !path.startsWith(playStoreObbDir)) {
            GosPackageState ps = GosPackageState.getForSelf(ctx);
            if (ps == null) return;
            boolean hasObbAccess = ps.hasFlag(GosPackageStateFlag.ALLOW_ACCESS_TO_OBB_DIRECTORY);

            if (!hasObbAccess) {
                try {
                    IGms2Gca gmsInterface = GmsCompatApp.iGms2Gca();
                    if (gmsInterface != null) {
                        gmsInterface.showPlayStoreMissingObbPermissionNotification();
                    }
                } catch (RemoteException e) {
                }
            }
        }
    }

    static Uri packageUri(String packageName) {
        if (packageName == null) return null;
        return Uri.fromParts("package", packageName, null);
    }

    static int getIntFromBundle(Bundle b, String key, int defaultValue) {
        if (b == null || key == null || !b.containsKey(key)) {
            return defaultValue;
        }
        Object value = b.get(key);
        if (value instanceof Integer) {
            return ((Integer) value).intValue();
        }
        // Attempt conversion if it's a String representing an int
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    static void openAppSettings(String packageName) {
        if (packageName == null) return;
        Context ctx = GmsCompat.appContext();
        if (ctx == null) return;

        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri packageUri = packageUri(packageName);
        if (packageUri == null) return;
        i.setData(packageUri);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            ctx.startActivity(i);
        } catch (Exception e) { /* Ignore activity start errors */ }
    }

    static void sendIntent(Intent intent, IntentSender target) {
        if (intent == null || target == null) return;
        Context ctx = GmsCompat.appContext();
        if (ctx == null) return;
        try {
            target.sendIntent(ctx, 0, intent, null, null);
        } catch (IntentSender.SendIntentException e) {
            // Ignore send errors
        }
    }

    public static boolean isInstallAllowed(String pkgName, ContentResolver cr) {
        if (pkgName == null || cr == null) return true; // Default to allowed if info missing
        switch (pkgName) {
            case PackageId.GMS_CORE_NAME:
            case PackageId.PLAY_STORE_NAME:
            case PackageId.ANDROID_AUTO_NAME:
            case PackageId.PIXEL_HEALTH_NAME:
                try {
                    return Settings.Global.getInt(cr, "gmscompat_play_store_can_install_" + pkgName, 0) == 1;
                } catch (Exception e) { return false; } // Default to not allowed on error
        }
        return true;
    }

    @Nullable
    public static LocaleList overrideApplicationLocales(LocaleList actualLocales, @Nullable String targetPackage) {
        Context ctx = GmsCompat.appContext();
        if (ctx == null || actualLocales == null) return null;

        ContentResolver resolver = ctx.getContentResolver();
        if (resolver == null) return null;

        String settingRegex = null;
        try {
             settingRegex = Settings.Global.getString(resolver, "gmscompat_play_store_fetch_all_locales");
        } catch (Exception e) { return null; }

        if (settingRegex == null) {
            return null;
        }
        String pkgName = targetPackage != null ? targetPackage : ctx.getPackageName();
        if (pkgName == null) return null;

        try {
            if (!pkgName.matches(settingRegex)) {
                return null;
            }
        } catch (Exception e) { return null; } // Invalid regex pattern

        int numActualLocales = actualLocales.size();
        ArraySet<Locale> actualLocalesSet = new ArraySet<>(numActualLocales);

        Locale[] allLocales = Locale.getAvailableLocales();
        if (allLocales == null) return null;
        var res = new ArrayList<Locale>(allLocales.length);

        for (int i = 0; i < numActualLocales; ++i) {
            Locale l = actualLocales.get(i);
            if (l != null) {
                res.add(l);
                actualLocalesSet.add(l);
            }
        }

        for (int i = 0; i < allLocales.length; ++i) {
            Locale l = allLocales[i];
            if (l != null && !actualLocalesSet.contains(l)) {
                res.add(l);
            }
        }
        if (res.isEmpty()) return null;
        return new LocaleList(res.toArray(new Locale[0]));
    }

    private PlayStoreHooks() {}
}
