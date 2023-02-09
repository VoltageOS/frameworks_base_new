package com.android.server.pm.permission;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.ext.settings.ExtSettings;
import android.os.Build;
import android.util.ArraySet;
import android.util.EmptyArray;

import com.android.internal.pm.pkg.component.ParsedUsesPermission;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;

public class SpecialRuntimePermUtils {
    private static final String TAG = SpecialRuntimePermUtils.class.getSimpleName();

    private static final ArraySet<String> specialRuntimePermissions = new ArraySet<>(new String[] {
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

    public static int getFlags(AndroidPackage pkg, PackageState pkgState, int userId) {
        int flags = 0;

        for (ParsedUsesPermission perm : pkg.getUsesPermissionMapping().values()) {
            String name = perm.getName();
            switch (name) {
                default:
                    continue;
            }
        }

        return flags;
    }

    private SpecialRuntimePermUtils() {}
}
