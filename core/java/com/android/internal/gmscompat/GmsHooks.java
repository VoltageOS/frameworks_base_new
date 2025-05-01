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

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.app.ApplicationErrorReport;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.app.Service;
import android.app.compat.gms.GmsCompat;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Bundle;
import android.os.DeadSystemRuntimeException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerExemptionManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Downloads;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.webkit.WebView;

import com.android.internal.gmscompat.client.GmsCompatClientService;
import com.android.internal.gmscompat.flags.GmsFlag;
import com.android.internal.gmscompat.flags.GmsFlagOverrides;
import com.android.internal.gmscompat.gcarriersettings.GCarrierSettingsApp;
import com.android.internal.gmscompat.gcarriersettings.TestCarrierConfigService;
import com.android.internal.gmscompat.sysservice.GmcPackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static com.android.internal.gmscompat.GmsInfo.PACKAGE_GMS_CORE;

public final class GmsHooks {
    private static final String TAG = "GmsCompat/Hooks";

    private static volatile GmsCompatConfig config;

    public static final String PERSISTENT_GmsCore_PROCESS = PACKAGE_GMS_CORE + ".persistent";
    public static boolean inPersistentGmsCoreProcess;
    public static final String UI_GmsCore_PROCESS = PACKAGE_GMS_CORE + ".ui";

    public static GmsCompatConfig config() {
        return config;
    }

    public static void init(Context ctx, String packageName, String processName) {
        if (!packageName.equals(processName)) {
            WebView.setDataDirectorySuffix("process-shim--" + processName);
        }

        if (GmsCompat.isGmsCore()) {
            inPersistentGmsCoreProcess = processName.equals(PERSISTENT_GmsCore_PROCESS);
        }

        GmsCompatLib.init(ctx, processName);

        if (GmsCompat.isPlayStore()) {
            PlayStoreHooks.init();
        }

        if (GmsCompat.isGCarrierSettings()) {
            GCarrierSettingsApp.init();
        }

        configUpdateLock = new Object();
        tlPermissionsToSpoof = new ThreadLocal<>();

        synchronized (configUpdateLock) {
            GmsCompatConfig config = GmsCompatApp.connect(ctx, processName);
            setConfig(config);
        }

        Thread.setUncaughtExceptionPreHandler(new UncaughtExceptionPreHandler());

        if (inPersistentGmsCoreProcess) {
            GmsFlagOverrides.init(ctx);
        }

        GmcPackageManager.init(ctx);
    }

    static Object configUpdateLock;

    static void setConfig(GmsCompatConfig c) {
        synchronized (configUpdateLock) {
            config = c;
        }
    }

    static class UncaughtExceptionPreHandler implements Thread.UncaughtExceptionHandler {
        final Thread.UncaughtExceptionHandler orig = Thread.getUncaughtExceptionPreHandler();

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            Context ctx = GmsCompat.appContext();

            ApplicationErrorReport aer = new ApplicationErrorReport();
            aer.type = ApplicationErrorReport.TYPE_CRASH;
            aer.crashInfo = new ApplicationErrorReport.ParcelableCrashInfo(e);

            ApplicationInfo ai = ctx.getApplicationInfo();
            aer.packageName = ai.packageName;
            aer.applicationInfo = ai;
            aer.processName = Application.getProcessName();

            if (!shouldSkipException(e)) {
                try {
                    IGms2Gca gmsInterface = GmsCompatApp.iGms2Gca();
                    if (gmsInterface != null) {
                        gmsInterface.onUncaughtException(aer);
                    }
                } catch (RemoteException re) {
                }
            }

            if (orig != null) {
                orig.uncaughtException(t, e);
            }
        }

        private static boolean shouldSkipException(Throwable e) {
            for (;;) {
                if (e == null) {
                    return false;
                }
                boolean skip = e instanceof DeadSystemRuntimeException;
                if (skip) {
                    return true;
                }
                e = e.getCause();
            }
        }
    }

    public static boolean isHiddenSystemService(String name) {
        switch (name) {
            case Context.WIFI_SCANNING_SERVICE:
                return !GmsCompat.isAndroidAuto();
            case Context.CONTEXTHUB_SERVICE:
            case Context.APP_INTEGRITY_SERVICE:
            case Context.PERSISTENT_DATA_BLOCK_SERVICE:
            case Context.FONT_SERVICE:
                return true;
        }
        return false;
    }

    @SuppressLint("HardwareIds")
    public static String getSerial() {
        String ssaid = Settings.Secure.getString(GmsCompat.appContext().getContentResolver(),
                Settings.Secure.ANDROID_ID);
        if (ssaid == null) {
            ssaid = "0000000000000000"; // Default if null
        }
        return ssaid.toUpperCase();
    }

    static class RecentBinderPid implements Comparable<RecentBinderPid> {
        int pid;
        int uid;
        long lastSeen;
        volatile String[] packageNames;

        static final int MAX_MAP_SIZE = 50;
        static final int MAP_SIZE_TRIM_TO = 40;
        static final SparseArray<RecentBinderPid> map = new SparseArray(MAX_MAP_SIZE + 1);

        public int compareTo(RecentBinderPid b) {
            return Long.compare(b.lastSeen, lastSeen);
        }
    }

    public static void onBinderTransaction(int pid, int uid) {
        SparseArray<RecentBinderPid> map = RecentBinderPid.map;
        synchronized (map) {
            RecentBinderPid rbp = map.get(pid);
            if (rbp != null) {
                if (rbp.uid != uid) {
                    rbp = null;
                }
            }
            if (rbp == null) {
                rbp = new RecentBinderPid();
                rbp.pid = pid;
                rbp.uid = uid;
                map.put(pid, rbp);
            }
            rbp.lastSeen = SystemClock.uptimeMillis();

            int mapSize = map.size();
            if (mapSize <= RecentBinderPid.MAX_MAP_SIZE) {
                return;
            }
            RecentBinderPid[] arr = new RecentBinderPid[mapSize];
            for (int i = 0; i < mapSize; ++i) {
                arr[i] = map.valueAt(i);
            }
            Arrays.sort(arr);
            map.clear();
            for (int i = 0; i < RecentBinderPid.MAP_SIZE_TRIM_TO; ++i) {
                RecentBinderPid e = arr[i];
                map.put(e.pid, e);
            }
        }
    }

    public static ArrayList<RunningAppProcessInfo> addRecentlyBoundPids(Context context,
                                                                        List<RunningAppProcessInfo> orig) {
        final RecentBinderPid[] binderPids;
        final int binderPidsCount;
        {
            SparseArray<RecentBinderPid> map = RecentBinderPid.map;
            synchronized (map) {
                binderPidsCount = map.size();
                binderPids = new RecentBinderPid[binderPidsCount];
                for (int i = 0; i < binderPidsCount; ++i) {
                    binderPids[i] = map.valueAt(i);
                }
            }
        }
        PackageManager pm = context.getPackageManager();
        ArrayList<RunningAppProcessInfo> res = new ArrayList<>(orig.size() + binderPidsCount);
        res.addAll(orig);
        for (int i = 0; i < binderPidsCount; ++i) {
            RecentBinderPid rbp = binderPids[i];
            String[] pkgs = rbp.packageNames;
            if (pkgs == null) {
                if (UserHandle.getUserId(rbp.uid) != UserHandle.myUserId()) {
                    continue;
                }
                try {
                    pkgs = pm.getPackagesForUid(rbp.uid);
                } catch (Exception e) {
                    // Ignore if package manager fails
                    pkgs = null;
                }
                if (pkgs == null || pkgs.length == 0) {
                    continue;
                }
                rbp.packageNames = pkgs;
            }
            RunningAppProcessInfo pi = new RunningAppProcessInfo();
            pi.pid = rbp.pid;
            pi.uid = rbp.uid;
            pi.processName = pkgs[0];
            pi.pkgList = pkgs;
            pi.importance = RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
            res.add(pi);
        }
        return res;
    }

    public static Cursor maybeModifyQueryResult(Uri uri,
            @Nullable String[] projection, @Nullable Bundle queryArgs, @Nullable Cursor origCursor) {
        String uriString = uri.toString();

        Consumer<ArrayMap<String, String>> mutator = null;
        if (uriString.startsWith(GmsFlag.PHENOTYPE_URI_PREFIX)) {
            List<String> path = uri.getPathSegments();
            if (path == null || path.size() != 1) {
                return null;
            }

            String namespace = path.get(0);
            GmsCompatConfig currentConfig = config();
            if (currentConfig == null || currentConfig.forceDefaultFlagsMap == null) {
                return null;
            }

            ArrayList<String> forceDefaultFlagsRegexes = currentConfig.forceDefaultFlagsMap.get(namespace);
            if (forceDefaultFlagsRegexes == null || forceDefaultFlagsRegexes.isEmpty()) {
                return null;
            }

            mutator = map -> {
                int patternCnt = forceDefaultFlagsRegexes.size();
                Pattern[] patterns = new Pattern[patternCnt];
                for (int i = 0; i < patternCnt; ++i) {
                    try {
                        patterns[i] = Pattern.compile(forceDefaultFlagsRegexes.get(i));
                    } catch (Exception e) { patterns[i] = null; } // Ignore invalid patterns
                }
                ArrayMap<String, String> filteredMap = new ArrayMap<>(map.size());

                outer:
                for (int entryIdx = 0, entryCnt = map.size(); entryIdx < entryCnt; ++entryIdx) {
                    String key = map.keyAt(entryIdx);
                    if (key == null) continue; // Skip null keys
                    for (int patternIdx = 0; patternIdx < patternCnt; ++patternIdx) {
                        if (patterns[patternIdx] != null && patterns[patternIdx].matcher(key).matches()) {
                            continue outer;
                        }
                    }
                    filteredMap.put(key, map.valueAt(entryIdx));
                }
                map.clear();
                map.putAll(filteredMap);
            };
        }

        if (mutator != null) {
            return modifyKvCursor(origCursor, projection, mutator);
        }

        return null;
    }

    private static Cursor modifyKvCursor(@Nullable Cursor origCursor, @Nullable String[] projection,
                                         Consumer<ArrayMap<String, String>> mutator) {
        final int keyIndex = 0;
        final int valueIndex = 1;
        final int projectionLength = 2;

        if (origCursor != null) {
            try {
                projection = origCursor.getColumnNames();
            } catch (Exception e) { return null; } // Handle cursor errors
        }

        boolean expectedProjection = projection != null && projection.length == projectionLength
                && "key".equals(projection[keyIndex]) && "value".equals(projection[valueIndex]);

        if (!expectedProjection) {
            return null;
        }

        final ArrayMap<String, String> map;
        if (origCursor == null) {
            map = new ArrayMap<>();
        } else {
            map = new ArrayMap<>(origCursor.getCount() + 10);
            try (Cursor orig = origCursor) {
                while (orig.moveToNext()) {
                    String key = orig.getString(keyIndex);
                    String value = orig.getString(valueIndex);
                    if (key != null) { // Avoid null keys
                        map.put(key, value);
                    }
                }
            } catch (Exception e) {
                // Handle potential cursor exceptions during iteration
                return null;
            }
        }

        try {
            mutator.accept(map);
        } catch (Exception e) {
            // Handle potential exceptions within the mutator
            return null;
        }

        final int mapSize = map.size();
        MatrixCursor result = new MatrixCursor(projection, mapSize);

        for (int i = 0; i < mapSize; ++i) {
            Object[] row = new Object[projectionLength];
            row[keyIndex] = map.keyAt(i);
            row[valueIndex] = map.valueAt(i);
            result.addRow(row);
        }

        return result;
    }

    public static void onActivityStart(int resultCode, Intent intent, int requestCode, Bundle options) {
        if (resultCode != ActivityManager.START_ABORTED || intent == null) {
            return;
        }

        if (requestCode >= 0) {
            return;
        }

        intent.setIdentifier(UUID.randomUUID().toString());

        Context ctx = GmsCompat.appContext();
        PendingIntent pendingIntent = PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_IMMUTABLE, options);
        try {
            IGms2Gca gmsInterface = GmsCompatApp.iGms2Gca();
            if (gmsInterface != null) {
                gmsInterface.startActivityFromTheBackground(ctx.getPackageName(), pendingIntent);
            }
        } catch (RemoteException e) {
        }
    }

    public static void activityOnCreate(Activity activity) {
        // Hook placeholder
    }

    public static void filterContentValues(Uri url, ContentValues values) {
        if (values != null && Downloads.Impl.CONTENT_URI.equals(url)) {
            if (values.containsKey(Downloads.Impl.COLUMN_OTHER_UID)) {
                 Integer otherUid = values.getAsInteger(Downloads.Impl.COLUMN_OTHER_UID);
                 if (otherUid == null || otherUid.intValue() != Process.SYSTEM_UID) {
                     throw new IllegalStateException("unexpected COLUMN_OTHER_UID " + otherUid);
                 }
                 values.remove(Downloads.Impl.COLUMN_OTHER_UID);
            }
        }
    }

    private static boolean hasNearbyDevicesPermission() {
        return GmsCompat.hasPermission(Manifest.permission.BLUETOOTH_SCAN);
    }

    public static Bundle filterBroadcastOptions(Intent intent, Bundle options) {
        if (options == null || intent == null) {
            return options;
        }

        String targetPkg = intent.getPackage();

        if (targetPkg == null) {
            ComponentName cn = intent.getComponent();
            if (cn != null) {
                targetPkg = cn.getPackageName();
            }
        }

        if (targetPkg == null) {
            return options;
        }

        return filterBroadcastOptions(options, targetPkg);
    }

    public static Bundle filterBroadcastOptions(Bundle options, String targetPkg) {
        if (options == null || targetPkg == null) return options;

        BroadcastOptions bo = new BroadcastOptions(options);

        if (bo.getTemporaryAppAllowlistType() == PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_NONE) {
            return options;
        }

        long duration = bo.getTemporaryAppAllowlistDuration();
        if (duration <= 0) {
            return options;
        }

        GmsCompatApp.raisePackageToForeground(targetPkg, duration,
                bo.getTemporaryAppAllowlistReason(), bo.getTemporaryAppAllowlistReasonCode());

        bo.setTemporaryAppAllowlist(0, PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_NONE,
                PowerExemptionManager.REASON_UNKNOWN, null);
        return bo.toBundle();
    }

    public static boolean interceptException(Exception e, Parcel p) {
        if (!(e instanceof SecurityException) || p == null) {
            return false;
        }

        if (p.dataAvail() != 0) {
            return false;
        }

        GmsCompatConfig currentConfig = config();
        if (currentConfig == null) {
            return false;
        }

        StubDef stub = StubDef.find(e.getStackTrace(), currentConfig, StubDef.FIND_MODE_Parcel);
        if (stub == null) {
            return false;
        }

        return stub.stubOutMethod(p);
    }

    public static void onSQLiteOpenHelperConstructed(SQLiteOpenHelper h, @Nullable Context context) {
        if (context == null || h == null) {
            return;
        }

        if (GmsCompat.isGmsCore()) {
            if (inPersistentGmsCoreProcess) {
                if ("phenotype.db".equals(h.getDatabaseName()) && !context.isDeviceProtectedStorage()) {
                    phenotypeDb = h;
                }
            }
        }
    }

    @Nullable
    public static Service maybeInstantiateService(String className) {
        if (className == null) return null;

        if (GmsCompatClientService.class.getName().equals(className)) {
            return new GmsCompatClientService();
        }

        if (GmsCompat.isEnabled()) {
            if (GmsCompat.isGmsCore()) {
                if (GmcMediaProjectionService.class.getName().equals(className)) {
                    return new GmcMediaProjectionService();
                }
            }
            if (GmsCompat.isGCarrierSettings()) {
                if (TestCarrierConfigService.class.getName().equals(className)) {
                    return new TestCarrierConfigService();
                }
            }
        }

        return null;
    }

    private static volatile SQLiteOpenHelper phenotypeDb;
    public static SQLiteOpenHelper getPhenotypeDb() { return phenotypeDb; }

    private static ThreadLocal<ArraySet<String>> tlPermissionsToSpoof;

    public static boolean shouldSpoofSelfPermissionCheck(String perm) {
        if (perm == null) return false;
        ArraySet<String> set = tlPermissionsToSpoof.get();
        return set != null && set.contains(perm);
    }

    public static final String GMS_SERVICE_BROKER_INTERFACE_DESCRIPTOR =
            "com.google.android.gms.common.internal.IGmsServiceBroker";

    public static boolean onBeginGmsServiceBrokerCall(int transactionCode, Parcel data) {
        if (transactionCode != 46 || data == null) { // getService() method
            return false;
        }

        int origPos = data.dataPosition();
        try {
            data.enforceInterface(GMS_SERVICE_BROKER_INTERFACE_DESCRIPTOR);
            data.readStrongBinder(); // IGmsCallbacks binder

            if (data.readInt() == 1) { // GetServiceRequest is present
                data.readInt(); // GetServiceRequest object header
                data.readInt();
                data.readInt(); // version
                data.readInt();
                data.readInt(); // id of serviceId property

                int serviceId = data.readInt();

                GmsCompatConfig currentConfig = config();
                ArraySet<String> permsToSpoof = null;
                if (currentConfig != null && currentConfig.gmsServiceBrokerPermissionBypasses != null) {
                    permsToSpoof = currentConfig.gmsServiceBrokerPermissionBypasses.get(serviceId);
                }

                if (permsToSpoof != null) {
                    tlPermissionsToSpoof.set(permsToSpoof);
                    GmcPackageManager.notifyPermissionsChangeListeners();
                    return true;
                }
            }
        } catch (Exception e) {
             // Ignore parcel reading errors
        } finally {
            data.setDataPosition(origPos);
        }

        return false;
    }

    public static void onEndGmsServiceBrokerCall() {
        tlPermissionsToSpoof.set(null);
        GmcPackageManager.notifyPermissionsChangeListeners();
    }

    public static IBinder maybeOverrideBinder(IBinder binder) {
        if (binder == null) return null;

        boolean proceed = GmsCompat.isEnabled() || GmsCompat.isClientOfGmsCore();
        if (!proceed) {
            return null;
        }

        String ifaceName = null;
        try {
            ifaceName = binder.getInterfaceDescriptor();
        } catch (RemoteException e) {
            // Ignore
        }

        if (ifaceName == null) {
            return null;
        }

        return GmcBinderDefs.maybeOverrideBinder(binder, ifaceName);
    }

    private GmsHooks() {}
}
