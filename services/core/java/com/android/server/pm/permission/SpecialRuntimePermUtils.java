package com.android.server.pm.permission;

import android.Manifest;
import android.app.ActivityManager;
import android.companion.virtual.VirtualDeviceManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.SrtPermissions;
import android.ext.settings.ExtSettings;
import android.os.Build;
import android.os.Bundle;
import android.util.ArraySet;
import android.util.EmptyArray;
import android.util.LruCache;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.pm.pkg.component.ParsedUsesPermission;
import com.android.server.LocalServices;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;

import java.util.List;

public class SpecialRuntimePermUtils {
    private static final String TAG = SpecialRuntimePermUtils.class.getSimpleName();

    private static final ArraySet<String> specialRuntimePermissions = new ArraySet<>(new String[] {
            Manifest.permission.INTERNET,
            Manifest.permission.OTHER_SENSORS,
    });

    public static boolean isSpecialRuntimePermission(String permission) {
        return specialRuntimePermissions.contains(permission);
    }

    public static String[] getAll() {
        return specialRuntimePermissions.toArray(EmptyArray.STRING);
    }

    public static boolean shouldAutoGrant(Context ctx, String packageName, int userId, String perm) {
        if (!isSpecialRuntimePermission(perm)) {
            return false;
        }

        if (Manifest.permission.OTHER_SENSORS.equals(perm)) {
            if (ActivityManager.getService() == null) {
                // a failsafe: should never happen
                Slog.d(TAG, "AMS is null");
                if (Build.isDebuggable()) {
                    throw new IllegalStateException();
                }
                return false;
            }

            var um = LocalServices.getService(UserManagerInternal.class);
            // use parent profile settings for work profile
            int userIdForSettings = um.getProfileParentId(userId);

            return ExtSettings.AUTO_GRANT_OTHER_SENSORS_PERMISSION.get(ctx, userIdForSettings);
        }

        return true;
    }

    public static int getFlags(PackageManagerService pm, AndroidPackage pkg, PackageState pkgState, int userId) {
        int flags = 0;

        for (ParsedUsesPermission perm : pkg.getUsesPermissionMapping().values()) {
            String name = perm.getName();
            switch (name) {
                case Manifest.permission.INTERNET:
                    if (shouldEnableInternetCompat(pkg, pkgState, userId)) {
                        flags |= SrtPermissions.FLAG_INTERNET_COMPAT_ENABLED;
                    }
                    continue;
                default:
                    continue;
            }
        }

        return flags;
    }

    private static boolean shouldEnableInternetCompat(AndroidPackage pkg, PackageState pkgState, int userId) {
        if (pkgState.isSystem() || pkgState.isUpdatedSystemApp()) {
            // system packages should be aware of runtime INTERNET permission
            return false;
        }

        Bundle metadata = pkg.getMetaData();
        if (metadata != null) {
            String key = Manifest.permission.INTERNET + ".mode";
            if ("runtime".equals(metadata.getString(key))) {
                // AndroidManifest has
                // <meta-data android:name="android.permission.INTERNET.mode" android:value="runtime" />
                // declaration inside the <application> element
                return false;
            }
        }

        var permManager = LocalServices.getService(PermissionManagerServiceInternal.class);
        // enable InternetCompat if package doesn't have the INTERNET permission
        return permManager.checkPermission(pkg.getPackageName(),
                Manifest.permission.INTERNET, VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT, userId)
                != PackageManager.PERMISSION_GRANTED;
    }

    private SpecialRuntimePermUtils() {}
}
