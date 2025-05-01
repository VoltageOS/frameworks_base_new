/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.gmscompat.sysservice;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.Application;
import android.app.ApplicationPackageManager;
import android.app.compat.gms.GmsCompat;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageManager;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.VersionedPackage;
import android.ext.PackageId;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.PackageUtils;

import com.android.internal.gmscompat.GmsCompatConfig;
import com.android.internal.gmscompat.GmsHooks;
import com.android.internal.gmscompat.GmsInfo;
import com.android.internal.gmscompat.PlayStoreHooks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@SuppressLint("WrongConstant")
public class GmcPackageManager extends ApplicationPackageManager {
    private static final String TAG = GmcPackageManager.class.getSimpleName();

    public GmcPackageManager(Context context, IPackageManager pm) {
        super(context, pm);
    }

    public static void init(Context ctx) {
        initPseudoDisabledPackages();
        initForceDisabledComponents(ctx);
        if (GmsCompat.isPlayStore()) {
            ArraySet<String> hiddenPkgs = HIDDEN_PACKAGES;

            if (Application.getProcessName().equals(PackageId.PLAY_STORE_NAME)) {
                PackageInstaller installerWrapper = ctx.getPackageManager().getPackageInstaller();
                IPackageInstaller installer = installerWrapper.getIPackageInstaller();

                for (PackageInstaller.SessionInfo si : installerWrapper.getAllSessions()) {
                    try {
                        installer.abandonSession(si.sessionId);
                    } catch (RemoteException | SecurityException e) {
                    }
                }
            }
        }
    }

    public static void maybeAdjustPackageInfo(PackageInfo pi) {
        if (pi == null) return;
        ApplicationInfo ai = pi.applicationInfo;
        if (ai != null) {
            maybeAdjustApplicationInfo(ai);
        }
    }

    public static void maybeAdjustApplicationInfo(ApplicationInfo ai) {
        if (ai == null) return;
        String packageName = ai.packageName;

        if (GmsInfo.PACKAGE_GMS_CORE.equals(packageName)) {
            if (GmsCompat.isGmsCore() || GmsCompat.isClientOfGmsCore(ai)) {
                ai.flags |= ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            }
        }

        if (!ai.enabled) {
            if (shouldHideDisabledState(packageName)) {
                ai.enabled = true;
            }
        }
    }

    @Override
    public void deletePackage(String packageName, IPackageDeleteObserver observer, int flags) {
        if (GmsCompat.isPlayStore()) {
            PlayStoreHooks.deletePackage(this, packageName, observer, flags);
            return;
        }
        super.deletePackage(packageName, observer, flags);
    }

    @Override
    public void freeStorageAndNotify(String volumeUuid, long idealStorageSize, IPackageDataObserver observer) {
        if (GmsCompat.isPlayStore()) {
            PlayStoreHooks.freeStorageAndNotify(volumeUuid, idealStorageSize, observer);
            return;
        }
        super.freeStorageAndNotify(volumeUuid, idealStorageSize, observer);
    }

    @Override
    public void setApplicationEnabledSetting(String packageName, int newState, int flags) {
        if (GmsCompat.isPlayStore()) {
            if (isPseudoDisabledPackage(packageName)) {
                try {
                    super.getApplicationInfoAsUser(packageName, ApplicationInfoFlags.of(0L), getUserId());
                } catch (NameNotFoundException e) {
                    Context ctx = GmsCompat.appContext();
                    if (ctx != null && ctx.getMainThreadHandler() != null) {
                        ctx.getMainThreadHandler().post(() -> {
                            PlayStoreHooks.InternalBroadcastReceiver.removePseudoDisabledPackage(ctx, packageName);
                            PlayStoreHooks.updatePackageState(packageName, Intent.ACTION_PACKAGE_CHANGED, Intent.ACTION_PACKAGE_REMOVED);
                        });
                    }
                    return;
                }
            }
            PlayStoreHooks.setApplicationEnabledSetting(packageName, newState);
            return;
        }

        try {
            super.setApplicationEnabledSetting(packageName, newState, flags);
        } catch (SecurityException e) {
        }
    }

    @Override
    public boolean hasSystemFeature(String name) {
        switch (name) {
            case "android.hardware.uwb":
                return false;
        }
        return super.hasSystemFeature(name);
    }

    @Override
    public void addOnPermissionsChangeListener(OnPermissionsChangedListener listener) {
        synchronized (onPermissionsChangedListeners) {
            onPermissionsChangedListeners.add(listener);
        }
    }

    @Override
    public void removeOnPermissionsChangeListener(OnPermissionsChangedListener listener) {
        synchronized (onPermissionsChangedListeners) {
            onPermissionsChangedListeners.remove(listener);
        }
    }

    public static void notifyPermissionsChangeListeners() {
        int myUid = Process.myUid();
        synchronized (onPermissionsChangedListeners) {
            for (OnPermissionsChangedListener l : onPermissionsChangedListeners) {
                l.onPermissionsChanged(myUid);
            }
        }
    }

    private static final ArrayList<OnPermissionsChangedListener> onPermissionsChangedListeners =
            new ArrayList<>();

    private static PackageInfoFlags filterFlags(PackageInfoFlags flags) {
        long v = flags.getValue();
        if ((v & MATCH_ANY_USER) != 0) {
            return PackageInfoFlags.of(v & ~MATCH_ANY_USER);
        }
        return flags;
    }

    @Override
    public @NonNull List<SharedLibraryInfo> getSharedLibraries(PackageInfoFlags flags) {
        return super.getSharedLibraries(filterFlags(flags));
    }

    private static final ArraySet<String> HIDDEN_PACKAGES = new ArraySet<>(new String[] {
            "app.attestation.auditor",
    });

    private static void throwIfHidden(String pkgName) throws NameNotFoundException {
        if (HIDDEN_PACKAGES.contains(pkgName)) {
            throw new NameNotFoundException();
        }
    }

    @Override
    public PackageInfo getPackageInfo(VersionedPackage versionedPackage, PackageInfoFlags flags) throws NameNotFoundException {
        throwIfHidden(versionedPackage.getPackageName());
        flags = filterFlags(flags);

        PackageInfo pdi = makePseudoDisabledPackageInfoOrThrow(versionedPackage.getPackageName(), flags);
        if (pdi != null) {
            return pdi;
        }

        PackageInfo pi = super.getPackageInfo(versionedPackage, flags);
        maybeAdjustPackageInfo(pi);
        return pi;
    }

    @Override
    public PackageInfo getPackageInfoAsUser(String packageName, PackageInfoFlags flags, int userId) throws NameNotFoundException {
        throwIfHidden(packageName);
        flags = filterFlags(flags);

        PackageInfo pdi = makePseudoDisabledPackageInfoOrThrow(packageName, flags);
        if (pdi != null) {
            return pdi;
        }

        PackageInfo pi = super.getPackageInfoAsUser(packageName, flags, userId);
        maybeAdjustPackageInfo(pi);
        return pi;
    }

    @Override
    public ApplicationInfo getApplicationInfoAsUser(String packageName, ApplicationInfoFlags flags, int userId) throws NameNotFoundException {
        ApplicationInfo adi = makePseudoDisabledApplicationInfoOrThrow(packageName, flags);
        if (adi != null) {
            return adi;
        }

        ApplicationInfo ai = super.getApplicationInfoAsUser(packageName, flags, userId);
        maybeAdjustApplicationInfo(ai);
        return ai;
    }

    @Override
    public List<ApplicationInfo> getInstalledApplicationsAsUser(ApplicationInfoFlags flags, int userId) {
        List<ApplicationInfo> ret = super.getInstalledApplicationsAsUser(flags, userId);
        List<ApplicationInfo> res = new ArrayList<>(ret.size());
        ArraySet<String> pseudoDisabledPackages = clonePseudoDisabledPackages();

        for (ApplicationInfo ai : ret) {
            String pkgName = ai.packageName;
            if (HIDDEN_PACKAGES.contains(pkgName)) {
                continue;
            }
            pseudoDisabledPackages.remove(pkgName);
            maybeAdjustApplicationInfo(ai);
            res.add(ai);
        }

        for (String pkg : pseudoDisabledPackages) {
            ApplicationInfo ai = maybeMakePseudoDisabledApplicationInfo(pkg, flags);
            if (ai != null) {
                res.add(ai);
            }
        }
        return res;
    }

    @Override
    public List<PackageInfo> getInstalledPackagesAsUser(PackageInfoFlags flags, int userId) {
        flags = filterFlags(flags);
        List<PackageInfo> ret = super.getInstalledPackagesAsUser(flags, userId);
        List<PackageInfo> res = new ArrayList<>(ret.size());
        ArraySet<String> pseudoDisabledPackages = clonePseudoDisabledPackages();

        for (PackageInfo pi : ret) {
            String pkgName = pi.packageName;
            if (HIDDEN_PACKAGES.contains(pkgName)) {
                continue;
            }
            pseudoDisabledPackages.remove(pkgName);
            maybeAdjustPackageInfo(pi);
            res.add(pi);
        }

        for (String pkg : pseudoDisabledPackages) {
            PackageInfo pi = maybeMakePseudoDisabledPackageInfo(pkg, flags);
            if (pi != null) {
                res.add(pi);
            }
        }
        return res;
    }

    @Override
    public String[] getPackagesForUid(int uid) {
        int userId = UserHandle.getUserId(uid);
        int myUserId = UserHandle.myUserId();
        if (userId != myUserId) {
            if (userId != 0) {
                throw new IllegalArgumentException("uid from unexpected userId: " + uid);
            }
            uid = UserHandle.getUid(myUserId, UserHandle.getAppId(uid));
        }
        return super.getPackagesForUid(uid);
    }

    @SuppressLint("SwitchIntDef")
    @Override
    public int getApplicationEnabledSetting(String packageName) {
        try {
            int res = super.getApplicationEnabledSetting(packageName);
            switch (res) {
                case COMPONENT_ENABLED_STATE_DISABLED:
                case COMPONENT_ENABLED_STATE_DISABLED_USER:
                case COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                    if (shouldHideDisabledState(packageName)) {
                        res = COMPONENT_ENABLED_STATE_DEFAULT;
                    }
            }
            return res;
        } catch (Exception e) {
            if (isPseudoDisabledPackage(packageName)) {
                return COMPONENT_ENABLED_STATE_DISABLED_USER;
            }
            throw e;
        }
    }

    @Override
    public String getInstallerPackageName(String packageName) {
        try {
            return super.getInstallerPackageName(packageName);
        } catch (Exception e) {
            if (isPseudoDisabledPackage(packageName)) {
                return PackageId.PLAY_STORE_NAME;
            }
            throw e;
        }
    }

    @NonNull
    @Override
    public InstallSourceInfo getInstallSourceInfo(String packageName) throws NameNotFoundException {
        InstallSourceInfo res;
        try {
            res = super.getInstallSourceInfo(packageName);
        } catch (NameNotFoundException e) {
            if (isPseudoDisabledPackage(packageName)) {
                String installer = PackageId.PLAY_STORE_NAME;
                var isi = new InstallSourceInfo(installer, null, null, installer);
                return isi;
            }
            throw e;
        }

        switch (packageName) {
            case PackageId.ANDROID_AUTO_NAME:
            case PackageId.PIXEL_HEALTH_NAME:
            case PackageId.GMS_CORE_NAME:
                Context ctx = GmsCompat.appContext();
                if (ctx == null) break; // Cannot proceed without context
                ContentResolver cr = ctx.getContentResolver();
                if (cr == null) break; // Cannot proceed without resolver
                String updateOwnerPackage = PlayStoreHooks.isInstallAllowed(packageName, cr) ?
                        PackageId.PLAY_STORE_NAME :
                        PackageUtils.getFirstPartyAppSourcePackageName(ctx);
                res = new InstallSourceInfo(
                        res.getInitiatingPackageName(),
                        res.getInitiatingPackageSigningInfo(),
                        res.getOriginatingPackageName(),
                        res.getInstallingPackageName(),
                        updateOwnerPackage,
                        res.getPackageSource()
                );
                break;
        }
        return res;
    }

    @Nullable
    private PackageInfo makePseudoDisabledPackageInfoOrThrow(String pkgName, PackageInfoFlags flags) {
        if (!isPseudoDisabledPackage(pkgName)) {
            return null;
        }
        PackageInfo pi = maybeMakePseudoDisabledPackageInfo(pkgName, flags);
        return pi;
    }

    @Nullable
    private ApplicationInfo makePseudoDisabledApplicationInfoOrThrow(String pkgName, ApplicationInfoFlags flags) {
        if (!isPseudoDisabledPackage(pkgName)) {
            return null;
        }
        ApplicationInfo ai = maybeMakePseudoDisabledApplicationInfo(pkgName, flags);
        return ai;
    }

    @Nullable
    private PackageInfo maybeMakePseudoDisabledPackageInfo(String pkgName, PackageInfoFlags flags) {
        PackageInfo pi;
        try {
            pi = super.getPackageInfoAsUser(selfPkgName(), flags, getUserId());
        } catch (NameNotFoundException e) {
            return null;
        }
        pi.packageName = pkgName;
        if (pi.applicationInfo != null) {
            pi.applicationInfo.packageName = pkgName;
            pi.applicationInfo.enabled = false;
        }
        pi.setLongVersionCode(Integer.MAX_VALUE);
        return pi;
    }

    @Nullable
    private ApplicationInfo maybeMakePseudoDisabledApplicationInfo(String pkgName, ApplicationInfoFlags flags) {
        ApplicationInfo ai;
        try {
            ai = super.getApplicationInfoAsUser(selfPkgName(), flags, getUserId());
        } catch (NameNotFoundException e) {
            return null;
        }
        ai.packageName = pkgName;
        ai.enabled = false;
        ai.longVersionCode = Integer.MAX_VALUE;
        return ai;
    }

    private static String selfPkgName() {
        Context ctx = GmsCompat.appContext();
        return (ctx != null) ? ctx.getPackageName() : "com.google.android.gms"; // Fallback if context is null
    }

    private static final ArraySet<String> pseudoDisabledPackages = new ArraySet<>();

    private static void initPseudoDisabledPackages() {
        if (GmsCompat.isPlayStore()) {
            pseudoDisabledPackages.add("com.google.ar.core");
        }
        if (GmsCompat.isAndroidAuto()) {
            pseudoDisabledPackages.add(PackageId.G_SEARCH_APP_NAME);
            pseudoDisabledPackages.add("com.google.android.apps.maps");
            pseudoDisabledPackages.add("com.google.android.tts");
        }
    }

    private static boolean isPseudoDisabledPackage(String pkgName) {
        synchronized (pseudoDisabledPackages) {
            return pseudoDisabledPackages.contains(pkgName);
        }
    }

    private static ArraySet<String> clonePseudoDisabledPackages() {
        synchronized (pseudoDisabledPackages) {
            return new ArraySet<>(pseudoDisabledPackages);
        }
    }

    public static boolean removePseudoDisabledPackage(String pkgName) {
        synchronized (pseudoDisabledPackages) {
            return pseudoDisabledPackages.remove(pkgName);
        }
    }

    private static boolean shouldHideDisabledState(String pkgName) {
        if (!GmsCompat.isPlayStore()) {
            return false;
        }
        switch (pkgName) {
            case GmsInfo.PACKAGE_GMS_CORE:
                return false;
            default:
                return true;
        }
    }

    private static ArraySet<ComponentName> componentsWithForcedEnabledSetting;

    private static void initForceDisabledComponents(Context ctx) {
        if (ctx == null) return;
        final String pkgName = ctx.getPackageName();
        GmsCompatConfig config = GmsHooks.config();
        if (config == null || config.forceComponentEnabledSettingsMap == null) return;

        ArrayMap<String, Integer> forcedCes = config.forceComponentEnabledSettingsMap.get(pkgName);
        if (forcedCes == null) {
            return;
        }

        final int cnt = forcedCes.size();
        var components = new ArraySet<ComponentName>(cnt);
        var settings = new ArrayList<ComponentEnabledSetting>(cnt);
        for (int i = 0; i < cnt; ++i) {
            var name = new ComponentName(ctx, forcedCes.keyAt(i));
            components.add(name);
            int state = forcedCes.valueAt(i).intValue();
            var ces = new ComponentEnabledSetting(name, state, DONT_KILL_APP | SKIP_IF_MISSING);
            settings.add(ces);
        }
        componentsWithForcedEnabledSetting = components;

        boolean shouldUpdate;
        if (GmsCompat.isGmsCore()) {
            shouldUpdate = GmsHooks.inPersistentGmsCoreProcess;
        } else if (GmsCompat.isPlayStore()) {
            shouldUpdate = GmsInfo.PACKAGE_PLAY_STORE.equals(Application.getProcessName());
        } else {
            shouldUpdate = true;
        }

        if (shouldUpdate) {
            try {
                IPackageManager pm = ActivityThread.getPackageManager();
                if (pm != null) {
                    pm.setComponentEnabledSettings(settings, ctx.getUserId(), pkgName);
                }
            } catch (Exception e) {
            }
        }
    }

    private static boolean isSetComponentEnabledSettingAllowed(@Nullable ComponentName cn, int newState, int flags) {
        if (cn == null) {
            return true;
        }
        ArraySet<ComponentName> set = componentsWithForcedEnabledSetting;
        if (set != null && set.contains(cn)) {
            return false;
        }
        return true;
    }

    @Override
    public void setComponentEnabledSetting(ComponentName componentName,
                                           int newState, int flags) {
        if (!isSetComponentEnabledSettingAllowed(componentName, newState, flags)) {
            return;
        }
        try {
            super.setComponentEnabledSetting(componentName, newState, flags);
        } catch (SecurityException e) {
        }
    }

    @Override
    public void setComponentEnabledSettings(List<ComponentEnabledSetting> settings) {
        if (settings == null) return;
        settings = settings.stream()
                .filter(s -> isSetComponentEnabledSettingAllowed(s.getComponentName(),
                        s.getEnabledState(), s.getEnabledFlags()))
                .collect(Collectors.toUnmodifiableList());
        if (settings.isEmpty()) {
            return;
        }
        try {
            super.setComponentEnabledSettings(settings);
        } catch (SecurityException e) {
        }
    }

    @Nullable
    public static Context maybeOverrideGsfPackageContext(String packageName) {
        if (!GmsCompat.isGmsCore() || !PackageId.GSF_NAME.equals(packageName)) {
            return null;
        }

        Context ctx = GmsCompat.appContext();
        if (ctx == null) return null;
        PackageManager pkgManager = ctx.getPackageManager();
        if (pkgManager == null) return null;

        try {
            pkgManager.getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return ctx;
        }

        try {
            PackageInfo pi = pkgManager.getPackageInfo(PackageId.GMS_CORE_NAME, 0);
            if (pi.sharedUserId == null) {
                return ctx;
            }
            return null;
        } catch (NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    @NonNull
    @Override
    public List<ResolveInfo> queryBroadcastReceiversAsUser(@NonNull Intent intent, @NonNull ResolveInfoFlags flags, @NonNull UserHandle userHandle) {
        if ("android.autoinstalls.config.action.PLAY_AUTO_INSTALL".equals(intent.getAction())) {
            // Play Store reads list of apps to auto-install from APK that matches this filter
            return Collections.emptyList();
        }

        return super.queryBroadcastReceiversAsUser(intent, flags, userHandle);
    }
}
